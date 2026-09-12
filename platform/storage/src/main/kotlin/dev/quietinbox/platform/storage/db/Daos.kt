package dev.quietinbox.platform.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM source_configuration ORDER BY addedAtEpochMs")
    fun observeAll(): Flow<List<SourceConfigurationEntity>>

    @Query("SELECT * FROM source_configuration ORDER BY addedAtEpochMs")
    suspend fun all(): List<SourceConfigurationEntity>

    @Query("SELECT * FROM source_configuration WHERE packageName = :packageName")
    suspend fun get(packageName: String): SourceConfigurationEntity?

    @Upsert
    suspend fun upsert(entity: SourceConfigurationEntity)

    @Query("UPDATE source_configuration SET enabled = :enabled WHERE packageName = :packageName")
    suspend fun setEnabled(packageName: String, enabled: Boolean)

    @Query("UPDATE source_configuration SET paused = :paused WHERE packageName = :packageName")
    suspend fun setPaused(packageName: String, paused: Boolean)

    @Query("UPDATE source_configuration SET retentionDays = :days WHERE packageName = :packageName")
    suspend fun setRetention(packageName: String, days: Int?)

    @Query("UPDATE source_configuration SET mediaEnabled = :enabled WHERE packageName = :packageName")
    suspend fun setMediaEnabled(packageName: String, enabled: Boolean)

    @Query("DELETE FROM source_configuration WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

/**
 * The settle walk's statement, hoisted so the query-plan test explains the statement the DAO runs
 * rather than a copy of it. A copy can keep its pretty plan while the real one degrades: Codex
 * demonstrated it with `(receivedAtEpochMs + 0, eventId) > (?, ?)`, which walks correctly, keeps
 * every plan assertion true, and costs 2,152,898 VM instructions where the real statement costs
 * 177,461 (round 37 I3). Room needs a compile-time constant here, so this is one.
 */
internal const val PENDING_FOR_PACKAGE_AFTER = """
    SELECT * FROM event_journal
    WHERE state = 'PENDING' AND packageName = :packageName AND lossRecorded = 0
      AND (receivedAtEpochMs, eventId) > (:afterTime, :afterId)
    ORDER BY receivedAtEpochMs, eventId LIMIT :limit
"""

@Dao
interface JournalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: EventJournalEntity): Long

    /**
     * The replay's candidate set. A row whose settlement was deferred is not in it: it stays
     * PENDING with its payload intact, but it may not hold a place on the page, or 200 of them
     * starve row 201 for ever (round 36 Codex I1). [resumeDeferredLosses] puts them back.
     */
    @Query("SELECT * FROM event_journal WHERE state = 'PENDING' AND lossRecorded < 2 ORDER BY receivedAtEpochMs LIMIT :limit")
    suspend fun pending(limit: Int): List<EventJournalEntity>

    /**
     * Pending rows except those of [excludedPackages] (paused sources wait; they must not occupy
     * the whole page and starve every other source, QI-SEC-001 round-10). Rows without a package
     * (pre-v3) are included and decided by the commit fence.
     */
    @Query("SELECT * FROM event_journal WHERE state = 'PENDING' AND lossRecorded < 2 AND (packageName IS NULL OR packageName NOT IN (:excludedPackages)) ORDER BY receivedAtEpochMs LIMIT :limit")
    suspend fun pendingExcluding(limit: Int, excludedPackages: List<String>): List<EventJournalEntity>

    /** A terminal state (anything but PENDING) also clears the payload: the text must not outlive its commit. */
    @Query(
        """
        UPDATE event_journal
        SET state = :state, attempts = attempts + 1, failureCode = :failure,
            payload = CASE WHEN :state = 'PENDING' THEN payload ELSE '' END
        WHERE eventId = :eventId
        """,
    )
    suspend fun setState(eventId: String, state: String, failure: String?)

    /**
     * A page of one source's pending rows, so the upgrade path can settle them before they are
     * discarded. Bounded like every other pending read: a source that has been paused for a long
     * time accumulates rows — replay skips it and retention never deletes a `PENDING` row — and
     * this runs inside the policy write transaction, so an unbounded read would hold the pipeline
     * lock for as long as the decode took (round 35 subagent I2).
     *
     * Paged by `(receivedAtEpochMs, eventId)` rather than by offset: a row with no loss to settle
     * is left exactly as it was, so a repeated identical query would return it for ever. The
     * comparison is written as a row value so SQLite can seek to the cursor inside
     * `index_event_journal_packageName_state_lossRecorded_receivedAtEpochMs_eventId` instead of
     * re-reading every earlier row of the same source and filtering (round 36 Codex I2). The
     * `eventId` tiebreak is not decoration: rows sharing a millisecond are common — a batched
     * re-post arrives as several notifications with one timestamp — and ordering by the timestamp
     * alone leaves their relative order to the query plan, so a cursor built from the last row of
     * one page can skip a sibling that the next page would then never return.
     *
     * `lossRecorded = 0` narrows it to the rows the walk exists for. A row this release accepted
     * settled its loss with the insert, and one an earlier page settled is done; neither needs
     * reading, and skipping them in the index costs nothing.
     */
    @Query(PENDING_FOR_PACKAGE_AFTER)
    suspend fun pendingForPackageAfter(packageName: String, afterTime: Long, afterId: String, limit: Int): List<EventJournalEntity>

    /**
     * Claims the right to record this event's own loss, returning 1 only for the caller that won.
     *
     * The conditional update is the whole guarantee: however often a pending row is replayed, and
     * whichever path reaches it first — the replay, or the discard that follows disabling or
     * removing its source — exactly one caller sees a 1 and writes the gap. `state = 'PENDING'`
     * keeps a stale snapshot from claiming a row that has already been committed or discarded.
     *
     * Two exits never reach here at all, both on purpose: a payload that will not decode (filed
     * `FAILED` / `DECODE`, with nothing readable to settle) and a row whose commit attempts run out —
     * that one records a loss of its own, the whole event, in the transaction that files it
     * (`fileFailed`'s caller, issue #28), and this claim is not the gate for it: a settled
     * row's own loss being on disk says nothing about whether its commit failure is.
     */
    @Query("UPDATE event_journal SET lossRecorded = 1 WHERE eventId = :eventId AND lossRecorded = 0 AND state = 'PENDING'")
    suspend fun claimLoss(eventId: String): Int

    /**
     * Takes a row whose gap write failed out of the replay's candidate set, without spending the
     * claim, without touching the payload, and without forgetting whether its own loss is settled.
     *
     * Adds the deferred bit rather than writing a value: a row at 0 goes to 2 and a row at 1 to 3,
     * so a resume can give each back exactly the settled state it had. `lossRecorded < 2` keeps a
     * row from being deferred twice, and `state = 'PENDING'` from deferring one that has since
     * been committed or discarded. Returns 1 only when the row really left the candidate set — the
     * caller may not treat a failed deferral (a vault that refuses this write too) as progress, or
     * the replay loop would re-read the same page until its round limit.
     */
    @Query("UPDATE event_journal SET lossRecorded = lossRecorded + 2 WHERE eventId = :eventId AND lossRecorded < 2 AND state = 'PENDING'")
    suspend fun deferLoss(eventId: String): Int

    /**
     * Puts every deferred row back into the candidate set, for the pass that is about to run.
     *
     * Called once a replay pass has drained everything else, and at the head of a settle walk —
     * those are the only two readers of pending rows. Whatever made the earlier gap write fail may
     * be gone, and the only way to find out is to try. A row that fails again defers itself again
     * and stops holding a place on the page, so a pass tries each parked row once after its drain
     * — a row that was not parked when the pass began can fail once before the drain and once
     * after it, which is two attempts in one pass and still bounded.
     *
     * `state = 'PENDING'` because a row that has left it is owed nothing: a discard can strip a
     * deferred row's payload without touching this column, and resuming that row would count it in
     * the number returned and say a pass had work to do when it has none (round 37 agy).
     *
     * Subtracts the deferred bit rather than writing 0: a row deferred from 1 comes back as 1, and
     * a carried-over claim that was spent stays spent — the resume must never be what lets one loss
     * be written twice.
     */
    @Query("UPDATE event_journal SET lossRecorded = lossRecorded - 2 WHERE lossRecorded >= 2 AND state = 'PENDING'")
    suspend fun resumeDeferredLosses(): Int

    /**
     * The same, for one source. The settle walk runs inside that source's policy transaction, and
     * a transaction opened to switch one app off has no business putting another app's deferred
     * rows back — nor taking them away again when it rolls back (round 37, Codex and the subagent
     * independently).
     */
    @Query("UPDATE event_journal SET lossRecorded = lossRecorded - 2 WHERE lossRecorded >= 2 AND state = 'PENDING' AND packageName = :packageName")
    suspend fun resumeDeferredLossesForPackage(packageName: String): Int

    /**
     * The row's settlement state while it is still PENDING; null once it has left.
     *
     * Read inside the claim's own transaction when the claim finds nothing to take, because a
     * conditional update that changed no row does not say *why*. The two reasons are opposite: the
     * gap is already on disk, or it is not written at all and the row is waiting to try again. A 3
     * is read as deferred, not as settled: the row is out of the replay's reach either way, and a
     * caller that took "settled" for "safe to commit" is round 37's Critical.
     */
    @Query("SELECT lossRecorded FROM event_journal WHERE eventId = :eventId AND state = 'PENDING'")
    suspend fun pendingLossState(eventId: String): Int?

    /** Whether the replay would pick this row up: exactly [pending]'s predicate, for one row. */
    @Query("SELECT COUNT(*) FROM event_journal WHERE eventId = :eventId AND state = 'PENDING' AND lossRecorded < 2")
    suspend fun isReplayCandidate(eventId: String): Int

    /**
     * Files a row FAILED with its payload cleared, exactly once. The caller writes the record of
     * the loss — the whole event, [dev.quietinbox.core.model.GapReason.COMMIT_FAILED] — in the same
     * transaction, before this; `state = 'PENDING'` is what keeps a second exhaustion from writing
     * a second record for a row already filed. Returns the rows changed.
     */
    @Query("UPDATE event_journal SET state = 'FAILED', attempts = attempts + 1, failureCode = :failure, payload = '' WHERE eventId = :eventId AND state = 'PENDING'")
    suspend fun fileFailed(eventId: String, failure: String?): Int

    /** Pending rows of a source that was disabled or removed are discarded for good (QI-SEC-001). */
    @Query("UPDATE event_journal SET state = 'DISCARDED', failureCode = 'SOURCE_DISABLED', payload = '' WHERE state = 'PENDING' AND packageName = :packageName")
    suspend fun discardPending(packageName: String): Int

    @Query("SELECT payload FROM event_journal WHERE eventId = :eventId")
    suspend fun payload(eventId: String): String?

    @Query("SELECT attempts FROM event_journal WHERE eventId = :eventId")
    suspend fun attempts(eventId: String): Int?

    @Query("SELECT state FROM event_journal WHERE eventId = :eventId")
    suspend fun state(eventId: String): String?

    @Query("DELETE FROM event_journal WHERE expiresAtEpochMs < :now AND state != 'PENDING'")
    suspend fun deleteExpired(now: Long): Int

    @Query("SELECT COUNT(*) FROM event_journal WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM event_journal")
    suspend fun count(): Int

    /**
     * `deleteAllExpired` and `clear` used to sit here, both without the `state != 'PENDING'` guard
     * the sweep above carries, and neither with a caller anywhere in the tree. A statement that
     * deletes a pending row deletes an accepted event and the evidence of what it lost, in a file
     * whose own KDoc says retention never does that; leaving two of them within easy reach of the
     * next person to need "clean up the journal" was a loaded gun rather than dead weight
     * (round 36 subagent M2).
     */
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM notification_checkpoint WHERE streamKey = :streamKey")
    suspend fun get(streamKey: String): CheckpointEntity?

    @Upsert
    suspend fun upsert(entity: CheckpointEntity)

    @Query("UPDATE notification_checkpoint SET closed = 1, updatedAtEpochMs = :now WHERE streamKey = :streamKey")
    suspend fun close(streamKey: String, now: Long)

    @Query("UPDATE notification_checkpoint SET closed = 1, updatedAtEpochMs = :now WHERE closed = 0")
    suspend fun closeAll(now: Long)

    @Query("DELETE FROM notification_checkpoint WHERE packageName = :packageName")
    suspend fun deleteForPackage(packageName: String)

    @Query("DELETE FROM notification_checkpoint WHERE updatedAtEpochMs < :before")
    suspend fun deleteStale(before: Long): Int
}

@Dao
interface ConversationDao {
    @Query(
        """
        SELECT * FROM conversation
        WHERE archived = :archived
          AND (:allPackages = 1 OR packageName IN (:packages))
        ORDER BY pinned DESC, lastActivityEpochMs DESC
        """,
    )
    fun observeInbox(archived: Boolean, allPackages: Boolean, packages: List<String>): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE id = :id")
    fun observe(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Query(
        """
        SELECT * FROM conversation
        WHERE packageName = :packageName AND profileKey = :profileKey
          AND accountKey IS :accountKey AND identityKey = :identityKey
        """,
    )
    suspend fun find(packageName: String, profileKey: String, accountKey: String?, identityKey: String): ConversationEntity?

    @Insert
    suspend fun insert(entity: ConversationEntity): Long

    @Update
    suspend fun update(entity: ConversationEntity)

    @Query("UPDATE conversation SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE conversation SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("UPDATE conversation SET lastViewedEpochMs = :now WHERE id = :id")
    suspend fun markViewed(id: Long, now: Long)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversation WHERE packageName = :packageName")
    suspend fun deleteForPackage(packageName: String)

    @Query("SELECT COUNT(*) FROM conversation")
    fun observeCount(): Flow<Int>

    /** Conversations with copies newer than the last time they were opened, optionally limited to some sources. */
    @Query(
        """
        SELECT COUNT(*) FROM conversation
        WHERE archived = 0 AND messageCount > 0 AND lastActivityEpochMs > COALESCE(lastViewedEpochMs, 0)
          AND (:allPackages = 1 OR packageName IN (:packages))
        """,
    )
    suspend fun unviewedCount(allPackages: Boolean, packages: List<String>): Int

    /**
     * Recomputes the projection (counts, preview, last sender, last activity) from the messages
     * that are still visible at [now]. The single source of truth after any deletion, expiry or
     * restore (QI-DATA-004); the live commit path only increments.
     */
    @Query(
        """
        UPDATE conversation SET
          messageCount = (SELECT COUNT(*) FROM message m WHERE m.conversationId = conversation.id
                          AND m.dedupState != 'AMBIGUOUS_REPEAT' AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now)),
          ambiguousCount = (SELECT COUNT(*) FROM message m WHERE m.conversationId = conversation.id
                          AND m.dedupState = 'AMBIGUOUS_REPEAT' AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now)),
          lastMessagePreview = (SELECT substr(m.body, 1, 200) FROM message m WHERE m.conversationId = conversation.id
                          AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now) ORDER BY m.sortKey DESC, m.id DESC LIMIT 1),
          lastSenderName = (SELECT m.senderName FROM message m WHERE m.conversationId = conversation.id
                          AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now) ORDER BY m.sortKey DESC, m.id DESC LIMIT 1),
          lastActivityEpochMs = COALESCE((SELECT MAX(m.observedAtEpochMs) FROM message m WHERE m.conversationId = conversation.id
                          AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now)), conversation.createdAtEpochMs)
        WHERE id IN (:ids)
        """,
    )
    suspend fun rebuildProjection(ids: List<Long>, now: Long)

    @Query(
        """
        SELECT id FROM conversation
        WHERE createdAtEpochMs < :before
          AND NOT EXISTS (SELECT 1 FROM message m WHERE m.conversationId = conversation.id)
        """,
    )
    suspend fun emptyOlderThan(before: Long): List<Long>

    /**
     * Removes old conversations that have *no message rows* at the moment of the delete.
     * `messageCount = 0` is not enough: that projection excludes `AMBIGUOUS_REPEAT`, and a
     * scan-then-delete would still drop a copy committed between the two statements.
     */
    @Query(
        """
        DELETE FROM conversation
        WHERE createdAtEpochMs < :before
          AND NOT EXISTS (SELECT 1 FROM message m WHERE m.conversationId = conversation.id)
        """,
    )
    suspend fun deleteEmptyOlderThan(before: Long): Int

    @Query(
        """
        DELETE FROM conversation
        WHERE id = :id
          AND createdAtEpochMs < :before
          AND NOT EXISTS (SELECT 1 FROM message m WHERE m.conversationId = conversation.id)
        """,
    )
    suspend fun deleteIfEmptyAndOlderThan(id: Long, before: Long): Int

    @Query("SELECT DISTINCT packageName FROM conversation")
    fun observePackages(): Flow<List<String>>

    @Query("SELECT * FROM conversation WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun exportPage(afterId: Long, limit: Int): List<ConversationEntity>

    @Query("SELECT COUNT(*) FROM conversation")
    suspend fun count(): Int
}

@Dao
interface MessageDao {
    /**
     * Expired rows are hidden here, not only deleted by retention later (QI-DATA-007). [now] is
     * fixed when the flow is collected; a screen that stays open across an expiry boundary shows
     * the row until it is re-collected or retention removes it.
     */
    @Query(
        """
        SELECT * FROM message WHERE conversationId = :conversationId
          AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now)
        ORDER BY sortKey ASC, id ASC
        """,
    )
    fun observeForConversation(conversationId: Long, now: Long): Flow<List<MessageEntity>>

    @Query("SELECT DISTINCT conversationId FROM message WHERE id IN (:ids)")
    suspend fun conversationIdsOf(ids: List<Long>): List<Long>

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query("SELECT * FROM message WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<MessageEntity>

    @Query("SELECT * FROM message WHERE conversationId = :conversationId")
    suspend fun forConversation(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM message WHERE conversationId = :conversationId AND sourceMessageId = :sourceMessageId LIMIT 1")
    suspend fun findBySourceId(conversationId: Long, sourceMessageId: String): MessageEntity?

    @Query("SELECT id FROM message WHERE conversationId = :conversationId AND fingerprint = :fingerprint ORDER BY id DESC LIMIT 1")
    suspend fun findIdByFingerprint(conversationId: Long, fingerprint: String): Long?

    /** The newest [limit] stored rows with this fingerprint, newest first (checkpoint-loss guard, one link per row). */
    @Query("SELECT id FROM message WHERE conversationId = :conversationId AND fingerprint = :fingerprint ORDER BY id DESC LIMIT :limit")
    suspend fun findLatestIdsByFingerprint(conversationId: Long, fingerprint: String, limit: Int): List<Long>

    @Insert
    suspend fun insert(entity: MessageEntity): Long

    @Update
    suspend fun update(entity: MessageEntity)

    @Query("UPDATE message SET observationCount = observationCount + 1 WHERE id = :id")
    suspend fun incrementObservation(id: Long)

    /** A revision replaces the body, so it replaces what that body lost as well. */
    @Query("UPDATE message SET body = :body, revisionCount = revisionCount + 1, eventId = :eventId, truncationFlags = :truncationFlags WHERE id = :id")
    suspend fun applyRevision(id: Long, body: String, eventId: String, truncationFlags: String?)

    /**
     * Records that a stored body was shortened, when a later observation of the same text proves
     * it. Only ever sets the flag, never clears it: a repost whose identical text happened not to
     * be cut does not unmake the observation that it once was, and letting an old replay clear the
     * flag would erase a newer, true loss (round 34 I2, round 35 Codex I4).
     *
     * The model this makes is **historical**: the flag says "this text was cut in at least one
     * observation of this message", not "the version on screen is cut". The two diverge whenever
     * the same stored body is observed more than once and only some of those notifications were
     * shortened: identical text is a repost on this path, so the flag set by the shortened one
     * stays even though the observation after it was complete. (A *longer* text is not this case —
     * that is a revision, and the line below says what happens to the flag there.) Set-only is not a database-wide invariant
     * either: it is the rule for the `Known` path. A revision replaces the body and recomputes the
     * flag from scratch through `applyRevision`, including back to null.
     */
    @Query("UPDATE message SET truncationFlags = :truncationFlags WHERE id = :id AND truncationFlags IS NULL")
    suspend fun markTruncated(id: Long, truncationFlags: String)

    /** Returns the rows updated: 0 when the message is gone, which a linking write must notice. */
    @Query("UPDATE message SET mediaState = :state, mediaBlobId = :blobId WHERE id = :id")
    suspend fun setMedia(id: Long, state: String, blobId: Long?): Int

    /**
     * Settles a row only while it is still PENDING. The retention sweep and a media copy both run
     * as `maintenance.work`, which permits concurrency, so an unguarded update could overwrite a
     * `LOCAL_COPY` that committed in between — and the next sweep would then delete its file as an
     * orphan.
     */
    @Query("UPDATE message SET mediaState = :state, mediaBlobId = NULL WHERE id = :id AND mediaState = 'PENDING'")
    suspend fun settlePendingMedia(id: Long, state: String): Int

    @Query("UPDATE message SET dedupState = :state WHERE id = :id")
    suspend fun setDedupState(id: Long, state: String)

    @Query("DELETE FROM message WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT id FROM message WHERE expiresAtEpochMs IS NOT NULL AND expiresAtEpochMs < :now LIMIT :limit")
    suspend fun expiredIds(now: Long, limit: Int): List<Long>

    @Query("UPDATE message SET expiresAtEpochMs = observedAtEpochMs + :ttlMs WHERE conversationId IN (SELECT id FROM conversation WHERE packageName = :packageName)")
    suspend fun recomputeExpiryForPackage(packageName: String, ttlMs: Long)

    @Query("UPDATE message SET expiresAtEpochMs = observedAtEpochMs + :ttlMs")
    suspend fun recomputeExpiryAll(ttlMs: Long)

    @Query("SELECT COUNT(*) FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now")
    fun observeCount(now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM message WHERE dedupState = 'AMBIGUOUS_REPEAT' AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now)")
    fun observeAmbiguousCount(now: Long): Flow<Int>

    /**
     * Messages of one package observed since [since]. Onboarding's capture test used the whole
     * vault's message count, which is already non-zero on a re-run or after a restore, so the step
     * reported success without having captured anything.
     */
    @Query(
        """
        SELECT COUNT(*) FROM message m JOIN conversation c ON c.id = m.conversationId
        WHERE c.packageName = :packageName AND m.observedAtEpochMs >= :since
        """,
    )
    fun observeCapturedSince(packageName: String, since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM message WHERE mediaState = 'PENDING'")
    suspend fun pendingMediaCount(): Int

    @Query("SELECT * FROM message WHERE mediaState = 'PENDING' ORDER BY observedAtEpochMs LIMIT :limit")
    suspend fun pendingMedia(limit: Int): List<MessageEntity>

    /**
     * Newest [limit] rows of the period, newest first. No index is led by `sortKey`, so SQLite scans
     * the period and sorts at most [limit] rows: CPU traded for a bounded heap on purpose (the caller
     * samples vault changes at 400 ms). A `sortKey` index is a candidate for schema v3.
     */
    @Query(
        """
        SELECT m.conversationId, m.sortKey, m.dedupState, m.contentStatus, m.body, m.senderName, m.senderKey, m.isSelf, c.packageName
        FROM message m JOIN conversation c ON c.id = m.conversationId
        WHERE m.sortKey >= :since AND m.sortKey <= :until
          AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now)
        ORDER BY m.sortKey DESC
        LIMIT :limit
        """,
    )
    suspend fun statsBetween(since: Long, until: Long, limit: Int, now: Long): List<MessageStatRow>

    /** Export pages: keyset by id, and never a copy that is already expired (QI-DATA-007 round-10). */
    @Query("SELECT * FROM message WHERE id > :afterId AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now) ORDER BY id LIMIT :limit")
    suspend fun exportPage(afterId: Long, limit: Int, now: Long): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now")
    suspend fun exportCount(now: Long): Int

    @Query("SELECT MIN(sortKey) FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now")
    suspend fun earliestSortKey(now: Long): Long?
}

@Dao
interface RevisionDao {
    @Insert
    suspend fun insert(entity: MessageRevisionEntity): Long

    @Query("SELECT * FROM message_revision WHERE messageId = :messageId ORDER BY observedAtEpochMs")
    fun observeForMessage(messageId: Long): Flow<List<MessageRevisionEntity>>

    @Query("SELECT * FROM message_revision WHERE id > :afterId AND messageId IN (SELECT id FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now) ORDER BY id LIMIT :limit")
    suspend fun exportPage(afterId: Long, limit: Int, now: Long): List<MessageRevisionEntity>

    @Query("SELECT COUNT(*) FROM message_revision WHERE messageId IN (SELECT id FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now)")
    suspend fun exportCount(now: Long): Int
}

@Dao
interface ObservationLinkDao {
    @Insert
    suspend fun insert(entity: ObservationLinkEntity): Long

    @Query("SELECT COUNT(*) FROM observation_link WHERE kind = :kind")
    suspend fun countByKind(kind: String): Int
}

@Dao
interface MediaDao {
    @Insert
    suspend fun insert(entity: MediaBlobEntity): Long

    @Query("SELECT * FROM media_blob WHERE id = :id")
    suspend fun get(id: Long): MediaBlobEntity?

    /** Blobs whose message is gone, or which the message no longer points at (a link that never completed). */
    @Query("SELECT b.* FROM media_blob b LEFT JOIN message m ON m.id = b.messageId WHERE m.id IS NULL OR m.mediaBlobId IS NULL OR m.mediaBlobId != b.id")
    suspend fun orphans(): List<MediaBlobEntity>

    @Query("SELECT * FROM media_blob WHERE messageId IN (:messageIds)")
    suspend fun forMessages(messageIds: List<Long>): List<MediaBlobEntity>

    @Query("SELECT * FROM media_blob WHERE messageId IN (SELECT id FROM message WHERE conversationId = :conversationId)")
    suspend fun forConversation(conversationId: Long): List<MediaBlobEntity>

    @Query(
        """
        SELECT b.* FROM media_blob b JOIN message m ON m.id = b.messageId
        JOIN conversation c ON c.id = m.conversationId WHERE c.packageName = :packageName
        """,
    )
    suspend fun forPackage(packageName: String): List<MediaBlobEntity>

    /** Every file name any blob row still points at — the set a directory sweep must never delete. */
    @Query("SELECT fileName FROM media_blob UNION SELECT thumbFileName FROM media_blob WHERE thumbFileName IS NOT NULL")
    suspend fun allFileNames(): List<String>

    @Query("DELETE FROM media_blob WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("UPDATE media_blob SET messageId = :messageId WHERE id = :blobId")
    suspend fun setMessageId(blobId: Long, messageId: Long)

    @Query("SELECT COALESCE(SUM(byteCount), 0) FROM media_blob")
    fun observeTotalBytes(): Flow<Long>

    /** Highest blob id at the time of the call; an export bounds its media pages to it so a picture committed later is never exported without its message. */
    @Query("SELECT COALESCE(MAX(id), 0) FROM media_blob")
    suspend fun maxId(): Long

    @Query("SELECT * FROM media_blob WHERE id > :afterId AND id <= :maxId AND messageId IN (SELECT id FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now) ORDER BY id LIMIT :limit")
    suspend fun exportPage(afterId: Long, maxId: Long, limit: Int, now: Long): List<MediaBlobEntity>

    @Query("SELECT COUNT(*) FROM media_blob WHERE messageId IN (SELECT id FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now)")
    suspend fun exportCount(now: Long): Int
}

@Dao
interface SuppressionDao {
    @Query("SELECT COUNT(*) FROM deletion_suppression WHERE scopeKey = :scopeKey AND fingerprint = :fingerprint AND expiresAtEpochMs > :now")
    suspend fun isSuppressed(scopeKey: String, fingerprint: String, now: Long): Int

    @Query("SELECT * FROM deletion_suppression WHERE scopeKey = :scopeKey AND fingerprint = :fingerprint AND expiresAtEpochMs > :now LIMIT 1")
    suspend fun token(scopeKey: String, fingerprint: String, now: Long): DeletionSuppressionEntity?

    @Upsert
    suspend fun upsert(entity: DeletionSuppressionEntity)

    @Query("DELETE FROM deletion_suppression WHERE expiresAtEpochMs < :now")
    suspend fun deleteExpired(now: Long): Int

    /** Exact prefix match (no LIKE: a package name may contain `_`, a LIKE wildcard). */
    @Query("DELETE FROM deletion_suppression WHERE substr(scopeKey, 1, length(:prefix)) = :prefix")
    suspend fun deleteForScopePrefix(prefix: String): Int
}

@Dao
interface SearchDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTokens(tokens: List<SearchTokenEntity>)

    @Query("DELETE FROM search_token WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: Long)

    @Query(
        """
        SELECT m.* FROM message m
        WHERE m.id IN (
            SELECT messageId FROM search_token WHERE token IN (:tokens)
            GROUP BY messageId HAVING COUNT(DISTINCT token) = :tokenCount
        )
        AND (:allPackages = 1 OR m.conversationId IN (SELECT id FROM conversation WHERE packageName IN (:packages)))
        AND (:fromMs IS NULL OR m.sortKey >= :fromMs)
        AND (:toMs IS NULL OR m.sortKey <= :toMs)
        AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now)
        AND (m.sortKey < :beforeSortKey OR (m.sortKey = :beforeSortKey AND m.id < :beforeId))
        ORDER BY m.sortKey DESC, m.id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchCandidates(
        tokens: List<String>,
        tokenCount: Int,
        allPackages: Boolean,
        packages: List<String>,
        fromMs: Long?,
        toMs: Long?,
        beforeSortKey: Long,
        beforeId: Long,
        limit: Int,
        now: Long,
    ): List<MessageEntity>
}

@Dao
interface HealthDao {
    @Insert
    suspend fun insertSession(entity: CaptureSessionEntity): Long

    @Query("UPDATE capture_session SET endedAtEpochMs = :endedAt, endReason = :reason WHERE id = :id")
    suspend fun endSession(id: Long, endedAt: Long?, reason: String?)

    @Query("SELECT * FROM capture_session ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeSessions(limit: Int): Flow<List<CaptureSessionEntity>>

    @Query("SELECT * FROM capture_session WHERE endedAtEpochMs IS NULL AND id != :exceptId ORDER BY startedAtEpochMs DESC")
    suspend fun openSessionsExcept(exceptId: Long): List<CaptureSessionEntity>

    @Insert
    suspend fun insertGap(entity: GapIntervalEntity): Long

    @Query("UPDATE gap_interval SET endEpochMs = :endEpochMs WHERE id = :id")
    suspend fun closeGap(id: Long, endEpochMs: Long?)

    @Query("SELECT * FROM gap_interval ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun observeGaps(limit: Int): Flow<List<GapIntervalEntity>>

    @Query("SELECT * FROM gap_interval WHERE endEpochMs IS NULL AND reason IN (:reasons)")
    suspend fun openGaps(reasons: List<String>): List<GapIntervalEntity>

    /**
     * "Remove and delete this source's data" takes the name, not the interval: the gap is the
     * honest record that capture stopped, and deleting it would hide a loss the user was shown.
     */
    @Query("UPDATE gap_interval SET packageName = NULL WHERE packageName = :packageName")
    suspend fun forgetGapSource(packageName: String): Int

    /**
     * Expires closed gaps only. An interval with no end is still happening — a source the user
     * disabled months ago is still not being captured — and deleting it would take the one thing
     * on the health page that says so.
     */
    @Query("DELETE FROM gap_interval WHERE createdAtEpochMs < :before AND endEpochMs IS NOT NULL")
    suspend fun deleteGapsBefore(before: Long): Int

    @Query("DELETE FROM capture_session WHERE startedAtEpochMs < :before AND endedAtEpochMs IS NOT NULL")
    suspend fun deleteSessionsBefore(before: Long): Int

    @Insert
    suspend fun insertSummary(entity: SummaryObservationEntity): Long

    @Query("SELECT COUNT(*) FROM summary_observation WHERE observedAtEpochMs >= :since")
    suspend fun summaryCountSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM summary_observation WHERE observedAtEpochMs >= :since AND observedAtEpochMs <= :until")
    suspend fun summaryCountBetween(since: Long, until: Long): Int

    @Query("SELECT COUNT(*) FROM summary_observation")
    fun observeSummaryCount(): Flow<Int>

    @Query("DELETE FROM summary_observation WHERE observedAtEpochMs < :before")
    suspend fun deleteSummariesBefore(before: Long): Int

    @Query("DELETE FROM summary_observation WHERE packageName = :packageName")
    suspend fun deleteSummariesForPackage(packageName: String): Int
}

@Dao
interface DiagnosticsDao {
    @Insert
    suspend fun insert(entity: DiagnosticEventEntity): Long

    @Query("SELECT * FROM local_diagnostic_event ORDER BY atEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiagnosticEventEntity>>

    @Query("SELECT code, COUNT(*) AS n FROM local_diagnostic_event WHERE atEpochMs >= :since GROUP BY code ORDER BY n DESC")
    suspend fun countsSince(since: Long): List<DiagnosticCount>

    @Query("DELETE FROM local_diagnostic_event WHERE atEpochMs < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("DELETE FROM local_diagnostic_event WHERE packageName = :packageName")
    suspend fun deleteForPackage(packageName: String): Int
}

data class DiagnosticCount(val code: String, val n: Int)

/**
 * Queries used only by the debug demo seeder (`DemoDataRepository`, debug source set). They stay in
 * the main Room class because a DAO cannot be variant-specific; they carry no demo content. They address rows by the
 * recognisable demo tags — a `demo.quietinbox.` package prefix, or a `demo-` capture generation —
 * so seeding and clearing can never touch captured data. No schema change: every column already
 * exists.
 */
@Dao
interface DemoDao {
    // Deletion order matters: media_blob has no cascade, so it is cleared through the join while
    // the conversations still exist.
    @Query(
        """
        DELETE FROM media_blob WHERE messageId IN (
            SELECT m.id FROM message m JOIN conversation c ON c.id = m.conversationId
            WHERE c.packageName LIKE :packagePrefix
        )
        """,
    )
    suspend fun deleteMediaBlobs(packagePrefix: String): Int

    @Query("DELETE FROM deletion_suppression WHERE scopeKey LIKE :packagePrefix")
    suspend fun deleteSuppression(packagePrefix: String): Int

    @Query("DELETE FROM notification_checkpoint WHERE packageName LIKE :packagePrefix")
    suspend fun deleteCheckpoints(packagePrefix: String): Int

    /** Cascades to message, and from there to message_revision, observation_link and search_token. */
    @Query("DELETE FROM conversation WHERE packageName LIKE :packagePrefix")
    suspend fun deleteConversations(packagePrefix: String): Int

    @Query("DELETE FROM source_configuration WHERE packageName LIKE :packagePrefix")
    suspend fun deleteSources(packagePrefix: String): Int

    @Query("DELETE FROM local_diagnostic_event WHERE packageName LIKE :packagePrefix")
    suspend fun deleteDiagnostics(packagePrefix: String): Int

    @Query("DELETE FROM summary_observation WHERE packageName LIKE :packagePrefix")
    suspend fun deleteSummaries(packagePrefix: String): Int

    /**
     * `gap_interval` has no taggable column (`reason` and `precision` are mapped onto enums, so an
     * invented value would render as "unknown"), hence demo gaps are stamped with the demo
     * session's start time and deleted through that join — before the sessions themselves.
     */
    @Query(
        "DELETE FROM gap_interval WHERE createdAtEpochMs IN " +
            "(SELECT startedAtEpochMs FROM capture_session WHERE generation LIKE :generationPrefix)",
    )
    suspend fun deleteGaps(generationPrefix: String): Int

    @Query("DELETE FROM capture_session WHERE generation LIKE :generationPrefix")
    suspend fun deleteSessions(generationPrefix: String): Int

    @Query("SELECT COUNT(*) FROM source_configuration WHERE packageName LIKE :packagePrefix")
    suspend fun countSources(packagePrefix: String): Int

    @Query("SELECT COUNT(*) FROM conversation WHERE packageName LIKE :packagePrefix")
    suspend fun countConversations(packagePrefix: String): Int

    @Query(
        "SELECT COUNT(*) FROM message m JOIN conversation c ON c.id = m.conversationId " +
            "WHERE c.packageName LIKE :packagePrefix",
    )
    suspend fun countMessages(packagePrefix: String): Int

    @Query(
        "SELECT COUNT(*) FROM message m JOIN conversation c ON c.id = m.conversationId " +
            "WHERE c.packageName LIKE :packagePrefix AND m.dedupState = :dedupState",
    )
    suspend fun countMessagesWithDedupState(packagePrefix: String, dedupState: String): Int

    @Query(
        "SELECT COUNT(*) FROM message_revision r JOIN message m ON m.id = r.messageId " +
            "JOIN conversation c ON c.id = m.conversationId WHERE c.packageName LIKE :packagePrefix",
    )
    suspend fun countRevisions(packagePrefix: String): Int

    @Query(
        "SELECT COUNT(*) FROM observation_link l JOIN message m ON m.id = l.messageId " +
            "JOIN conversation c ON c.id = m.conversationId WHERE c.packageName LIKE :packagePrefix AND l.kind = :kind",
    )
    suspend fun countObservationLinks(packagePrefix: String, kind: String): Int

    @Query(
        "SELECT COUNT(*) FROM search_token t JOIN message m ON m.id = t.messageId " +
            "JOIN conversation c ON c.id = m.conversationId WHERE c.packageName LIKE :packagePrefix",
    )
    suspend fun countSearchTokens(packagePrefix: String): Int

    @Query("SELECT COUNT(*) FROM capture_session WHERE generation LIKE :generationPrefix")
    suspend fun countSessions(generationPrefix: String): Int

    @Query("SELECT COUNT(*) FROM local_diagnostic_event WHERE packageName LIKE :packagePrefix")
    suspend fun countDiagnostics(packagePrefix: String): Int

    @Query("SELECT COUNT(*) FROM gap_interval")
    suspend fun countAllGaps(): Int
}
