package dev.quietinbox.platform.storage.repo

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import dev.quietinbox.core.identity.ConversationIdentity
import dev.quietinbox.core.model.DedupState
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.TruncationFlag
import dev.quietinbox.core.model.ParsedBatch
import dev.quietinbox.core.model.SearchNormalizer
import dev.quietinbox.core.model.TimestampQuality
import dev.quietinbox.core.reconcile.Decision
import dev.quietinbox.core.reconcile.KnownKind
import dev.quietinbox.core.reconcile.KnownMessage
import dev.quietinbox.core.reconcile.MessageWindow
import dev.quietinbox.core.reconcile.ReconcileNote
import dev.quietinbox.core.reconcile.ReconcileResult
import dev.quietinbox.core.reconcile.WindowItem
import dev.quietinbox.platform.storage.db.CheckpointEntity
import dev.quietinbox.platform.storage.db.ConversationEntity
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.DiagnosticEventEntity
import dev.quietinbox.platform.storage.db.EventJournalEntity
import dev.quietinbox.platform.storage.db.LOSS_SETTLED
import dev.quietinbox.platform.storage.db.LOSS_UNSETTLED
import dev.quietinbox.platform.storage.db.MessageEntity
import dev.quietinbox.platform.storage.db.MessageRevisionEntity
import dev.quietinbox.platform.storage.db.ObservationLinkEntity
import dev.quietinbox.platform.storage.db.SearchTokenEntity
import dev.quietinbox.platform.storage.db.SummaryObservationEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a committed snapshot; ids are used to kick off media work. */
/**
 * What `message.truncationFlags` holds: one name when this row's own body was shortened, null
 * otherwise. The column keeps its name and type — schema 4 is unchanged — but it stopped being a
 * copy of the notification's flag set, which is what made a truncated title mark every message in
 * the batch (round 33).
 */
private fun truncationColumn(textTruncated: Boolean): String? =
    if (textTruncated) TruncationFlag.TEXT.name else null

data class CommitOutcome(
    val conversationId: Long?,
    val newMessageIds: List<Long>,
    val ambiguousMessageIds: List<Long>,
    val pendingMediaMessageIds: List<Long>,
    val suppressedCount: Int,
    val summaryRecorded: Boolean,
    /** Messages whose body this event replaced. A revision is a write like any other. */
    val revisedMessageIds: List<Long> = emptyList(),
)

@Serializable
private data class WindowItemJson(val fp: String, val sid: String? = null, val mid: Long? = null)

/**
 * Everything the capture pipeline needs from the vault: journal durability, checkpoints, id
 * lookups and the single-transaction projection commit (plan section 5).
 */
@Singleton
class IngestRepository @Inject constructor(
    private val holder: DatabaseHolder,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        /**
         * The failed commit at which an event is given up on and its loss recorded. Not a ceiling
         * on physical attempts: a row whose loss could not be recorded at this point is deferred
         * with its attempts unchanged and tried once more when a pass resumes it (issue #28).
         */
        const val MAX_ATTEMPTS = 3

        /** Same unit as `substr(body, 1, 200)` in `rebuildProjection`: code points, so emoji are never cut in half. */
        const val PREVIEW_CODE_POINTS = 200

        internal fun String.takeCodePoints(n: Int): String {
            if (codePointCount(0, length) <= n) return this
            return substring(0, offsetByCodePoints(0, n))
        }
    }
    private val windowSerializer = ListSerializer(WindowItemJson.serializer())

    /**
     * Durable acceptance: the snapshot is only "accepted" once this returns.
     *
     * [lossOnAccept] runs inside the same transaction as the journal insert, and only when the
     * insert actually created the row. A loss the event already carries — messages the framework
     * dropped before we ever saw them — is therefore committed with the event or not at all, and
     * every terminal path afterwards (committed, skipped, discarded by a disabled source, or left
     * pending by a pause) inherits it without having to know it exists. Because `eventId` is the
     * primary key and the insert ignores conflicts, a replay of the same event is a no-op here and
     * cannot record the same loss twice.
     */
    suspend fun journal(
        snapshot: NotificationSnapshot,
        generation: String,
        ttlMs: Long,
        lossOnAccept: (suspend () -> Unit)? = null,
    ): Boolean {
        val db = holder.db()
        val row = EventJournalEntity(
            eventId = snapshot.eventId,
            generation = generation,
            receivedAtEpochMs = snapshot.observedAtEpochMs,
            expiresAtEpochMs = snapshot.observedAtEpochMs + ttlMs,
            state = "PENDING",
            attempts = 0,
            failureCode = null,
            payload = json.encodeToString(NotificationSnapshot.serializer(), snapshot),
            packageName = snapshot.source.packageName,
            // Set with the insert, so the upgrade path can tell a row this release already
            // accounted for from one carried over from a release that did not.
            lossRecorded = if (lossOnAccept != null) LOSS_SETTLED else LOSS_UNSETTLED,
        )
        return db.withTransaction {
            val accepted = db.journalDao().insert(row) != -1L
            if (accepted) lossOnAccept?.invoke()
            accepted
        }
    }

    /** Pending rows of a source the user disabled or removed: discarded, payload cleared (QI-SEC-001). */
    suspend fun discardPendingJournal(packageName: String): Int = holder.db().journalDao().discardPending(packageName)

    /**
     * Records a loss an event carried in from a release that recorded it nowhere, exactly once,
     * and says what it found — which of the four is the caller's whole basis for deciding whether
     * the row may now go on to a terminal state.
     *
     * [writeGap] runs only for the caller that wins the claim, and in the same transaction as the
     * claim, so the two cannot come apart: a row whose gap write fails keeps its claim unspent and
     * is tried again by the next pass. The answer is one of four: [LossClaim.RECORDED] (this call
     * wrote the gap), [LossClaim.ALREADY_RECORDED] (an earlier one did), [LossClaim.DEFERRED] (the
     * row is parked and the gap does not exist) and [LossClaim.NOT_PENDING] (the row has left).
     *
     * A `Boolean` here was the round-37 Critical. `false` used to carry a guarantee — "another
     * caller already wrote this gap" — so ignoring it and committing was safe. The deferred state
     * gave `false` a second, opposite meaning, and every caller kept the old reading: a replay
     * holding a batch from before another pass deferred the row would commit it and clear the
     * payload, with no gap anywhere. The enum is the guarantee made explicit.
     *
     * A failure also defers the row and then rethrows, because whether the *caller's* work may
     * continue is the caller's decision: the replay leaves the event uncommitted, and a source
     * policy change aborts, because a policy change that discarded the row would destroy the
     * evidence the deferral exists to keep. Called at the top level — the replay — the rollback has
     * already undone the claim by the time the catch runs, so the deferral is a durable write of
     * its own afterwards. Called *inside* a source policy transaction — the settle walk — it is a
     * write inside a transaction that is about to roll back with everything else, so nothing is
     * durably deferred there and the row simply stays as it was; that is the right answer too,
     * since the source stays switched on and the walk will run again. If the deferral cannot be
     * written either, the row stays in the candidate set and [isReplayCandidate] says so; nothing
     * here pretends otherwise.
     */
    suspend fun claimEventLoss(eventId: String, writeGap: suspend () -> Unit): LossClaim {
        val db = holder.db()
        try {
            return db.withTransaction {
                if (db.journalDao().claimLoss(eventId) == 1) {
                    writeGap()
                    LossClaim.RECORDED
                } else {
                    // The claim took nothing, and the reasons are opposite. Read them apart in the
                    // same transaction: anything else is a guess about a row another pass may have
                    // moved since.
                    when (db.journalDao().pendingLossState(eventId)) {
                        null -> LossClaim.NOT_PENDING
                        LOSS_SETTLED -> LossClaim.ALREADY_RECORDED
                        else -> LossClaim.DEFERRED
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            runCatching { db.journalDao().deferLoss(eventId) }
            throw e
        }
    }

    /**
     * Puts every deferred row back into the replay's candidate set, returning how many.
     *
     * Called by a replay pass once it has drained everything else — not at its head, where a
     * failing prefix would be re-inserted in front of everything on every trigger — and by a
     * settle walk before it reads, scoped to its source. A deferral is not a verdict: it says only
     * that the gap table refused the write at the time, and this is what makes the row try again
     * rather than wait for a user to change a source.
     */
    suspend fun resumeDeferredSettlements(): Int = holder.db().journalDao().resumeDeferredLosses()

    /** The same, scoped to one source — what a source policy transaction is entitled to change. */
    suspend fun resumeDeferredSettlements(packageName: String): Int =
        holder.db().journalDao().resumeDeferredLossesForPackage(packageName)

    /** Whether the replay would still pick this row up — the honest test for "did it move on". */
    suspend fun isReplayCandidate(eventId: String): Boolean = holder.db().journalDao().isReplayCandidate(eventId) > 0

    /**
     * One page of a source's pending rows, decoded. Used before those rows are discarded outright:
     * whatever loss they arrived with is settled while the payload still says what it was.
     *
     * A row that cannot be decoded is skipped rather than failed — this runs inside a source policy
     * transaction that is about to discard it anyway, and the replay path is what owns the decision
     * to mark a payload unreadable — but it still moves the cursor, or the page after it would
     * never be reached.
     */
    suspend fun pendingJournalForPackage(
        packageName: String,
        after: JournalCursor = JournalCursor.START,
        limit: Int = 200,
    ): JournalPage {
        val rows = holder.db().journalDao().pendingForPackageAfter(packageName, after.receivedAtEpochMs, after.eventId, limit)
        val snapshots = rows.mapNotNull { row ->
            runCatching { json.decodeFromString(NotificationSnapshot.serializer(), row.payload) }.getOrNull()
        }
        val last = rows.lastOrNull()
        // Only a full page can have more behind it; a short one is the end.
        val next = if (rows.size == limit && last != null) JournalCursor(last.receivedAtEpochMs, last.eventId) else null
        return JournalPage(snapshots, next)
    }

    suspend fun isJournalPending(eventId: String): Boolean = holder.db().journalDao().state(eventId) == "PENDING"

    suspend fun pendingJournal(limit: Int = 200, excludingPackages: Collection<String> = emptyList()): List<Pair<String, NotificationSnapshot>> {
        val db = holder.db()
        val rows = if (excludingPackages.isEmpty()) db.journalDao().pending(limit) else db.journalDao().pendingExcluding(limit, excludingPackages.toList())
        return rows.mapNotNull { row ->
            runCatching { row.generation to json.decodeFromString(NotificationSnapshot.serializer(), row.payload) }
                .getOrElse {
                    db.journalDao().setState(row.eventId, "FAILED", "DECODE")
                    null
                }
        }
    }

    suspend fun markJournal(eventId: String, state: String, failure: String? = null) {
        holder.db().journalDao().setState(eventId, state, failure)
    }

    /**
     * Charges a failed commit to the event. Within [MAX_ATTEMPTS] the row stays PENDING and the
     * replay retries it. At the threshold the event is given up on — filed FAILED, payload cleared —
     * and because that is the moment the last evidence of it goes, [lossOnExhaust] records the
     * loss first, in the same transaction: the row leaves PENDING with its record or not at all.
     * Before this, a lasting error lost an accepted event and said nothing (issue #28).
     *
     * When the record cannot be written the transaction rolls back — attempts untouched, payload
     * and settled state intact — and the row is then *deferred* by a separate write, so it leaves
     * the page the way a refused settlement does and the rows behind it are reached; a pass puts
     * it back once it has drained, and the commit gets another go. Which is why [MAX_ATTEMPTS] is
     * the threshold at which the loss must be recorded, not a ceiling on how many times a commit
     * is physically tried: a resumed row tries again, and if that succeeds nothing was lost.
     *
     * The record is not gated on the row's own loss being settled: `lossRecorded = 1` says the
     * event's *arrival* loss is on disk, and nothing about its commit failing. What keeps a second
     * exhaustion from writing a second record is the state check at the head of the transaction.
     *
     * Settling a carried-over loss deliberately does not go through here: bookkeeping must not
     * spend the event's attempts.
     */
    suspend fun markJournalRetryable(eventId: String, failure: String, lossOnExhaust: suspend () -> Unit): JournalRetry {
        val db = holder.db()
        var exhausting = false
        try {
            return db.withTransaction {
                if (db.journalDao().state(eventId) != "PENDING") return@withTransaction JournalRetry.NOT_PENDING
                val attempts = (db.journalDao().attempts(eventId) ?: 0) + 1
                if (attempts < MAX_ATTEMPTS) {
                    db.journalDao().setState(eventId, "PENDING", failure)
                    JournalRetry.RETRYABLE
                } else {
                    exhausting = true
                    lossOnExhaust()
                    // The gap is written; the row must go with it or the gap must go with the row.
                    check(db.journalDao().fileFailed(eventId, failure) == 1) { "journal row $eventId left PENDING under the exhaustion transaction" }
                    JournalRetry.FAILED_RECORDED
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!exhausting) throw e
            // The rollback is complete: this is a write of its own, and it can fail too. A row it
            // cannot park stays a candidate — attempts unchanged, payload intact — and holds its
            // place on the page until a pass can park it; nothing here says otherwise.
            val parked = runCatching { db.journalDao().deferLoss(eventId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(0)
            return if (parked == 1) JournalRetry.FAILED_DEFERRED else JournalRetry.RETRYABLE
        }
    }

    suspend fun checkpoint(streamKey: String): MessageWindow? {
        val row = holder.db().checkpointDao().get(streamKey) ?: return null
        val items = runCatching { json.decodeFromString(windowSerializer, row.windowJson) }.getOrDefault(emptyList())
        return MessageWindow(row.notificationKey, items.map { WindowItem(it.fp, it.sid, it.mid) }, row.closed, row.postedAtEpochMs)
    }

    suspend fun closeWindow(streamKey: String, now: Long) {
        holder.db().checkpointDao().close(streamKey, now)
    }

    suspend fun closeAllWindows(now: Long) {
        holder.db().checkpointDao().closeAll(now)
    }

    suspend fun lookupById(conversationId: Long?, sourceMessageId: String): KnownMessage? {
        if (conversationId == null) return null
        val m = holder.db().messageDao().findBySourceId(conversationId, sourceMessageId) ?: return null
        return KnownMessage(m.id, m.fingerprint, m.body)
    }

    suspend fun findConversationId(identity: ConversationIdentity): Long? =
        holder.db().conversationDao().find(identity.scope.packageName, identity.scope.profileKey, identity.scope.accountKey, identity.identityKey)?.id

    /** Settles rows whose media copy will never start, while they are still PENDING. */
    suspend fun settlePendingMedia(messageIds: List<Long>, state: String) {
        val dao = holder.db().messageDao()
        for (id in messageIds) dao.settlePendingMedia(id, state)
    }

    suspend fun diagnostic(code: String, detail: String? = null, packageName: String? = null, now: Long) {
        runCatching { holder.db().diagnosticsDao().insert(DiagnosticEventEntity(code = code, detail = detail, packageName = packageName, atEpochMs = now)) }
            .onFailure { if (it is CancellationException) throw it }
    }

    /**
     * Applies parser + reconciler output atomically. Never deletes; never rewrites source times.
     * A message whose fingerprint the user deleted (suppression) is skipped and counted.
     *
     * [lossOnCommit] is a loss the parser found — a whole row cut away from the body, which
     * exists only relative to the batch being stored ([ParsedBatch.wholeMessagesLost]). It runs
     * inside this transaction, immediately before the row leaves PENDING, on *both* exits: the
     * one that stores messages and the one that stores nothing because the identity or every
     * decision was empty. Either way the event is COMMITTED with its record or the whole write
     * rolls back and the replay tries again; a record written after the commit could be lost
     * between the two, and one written before could stand for a commit that never happened.
     */
    suspend fun commit(
        snapshot: NotificationSnapshot,
        batch: ParsedBatch,
        identity: ConversationIdentity?,
        reconcile: ReconcileResult?,
        generation: String,
        retentionMs: Long?,
        mediaAllowed: Boolean,
        lossOnCommit: (suspend () -> Unit)? = null,
    ): CommitOutcome {
        val db = holder.db()
        val now = snapshot.observedAtEpochMs
        return db.withTransaction {
            var summaryRecorded = false
            if (batch.summary != null) {
                db.healthDao().insertSummary(
                    SummaryObservationEntity(
                        packageName = snapshot.source.packageName,
                        observedAtEpochMs = now,
                        messageCount = batch.summary?.messageCount,
                        conversationCount = batch.summary?.conversationCount,
                        eventId = snapshot.eventId,
                    ),
                )
                summaryRecorded = true
            }

            if (identity == null || reconcile == null || reconcile.decisions.isEmpty()) {
                lossOnCommit?.invoke()
                db.journalDao().setState(snapshot.eventId, "COMMITTED", null)
                return@withTransaction CommitOutcome(null, emptyList(), emptyList(), emptyList(), 0, summaryRecorded)
            }

            val convDao = db.conversationDao()
            val existing = convDao.find(identity.scope.packageName, identity.scope.profileKey, identity.scope.accountKey, identity.identityKey)
            // The conversation row is created only when something is actually stored, so a replay
            // whose items are all suppressed or already deleted cannot resurrect a deleted
            // conversation as an empty row that still carries the other party's name.
            var conversationId: Long? = existing?.id
            suspend fun conversationIdOrCreate(): Long = conversationId ?: convDao.insert(
                ConversationEntity(
                    packageName = identity.scope.packageName,
                    profileKey = identity.scope.profileKey,
                    accountKey = identity.scope.accountKey,
                    identityKey = identity.identityKey,
                    identityConfidence = identity.confidence.name,
                    title = identity.displayTitle,
                    isGroup = batch.conversation?.isGroup,
                    pinned = false,
                    archived = false,
                    createdAtEpochMs = now,
                    lastActivityEpochMs = now,
                    lastViewedEpochMs = null,
                    messageCount = 0,
                    ambiguousCount = 0,
                    summaryOnlyCount = 0,
                    lastMessagePreview = null,
                    lastSenderName = null,
                ),
            ).also { conversationId = it }

            val suppressionKey = suppressionScopeKey(identity.scope, identity.identityKey)
            // Checkpoint-loss guard input: rows that existed BEFORE this batch, one consumable id
            // per stored row, so equal items inside one window keep their multiplicity and a batch
            // with two identical items links at most as many rows as already exist. The newest k
            // rows are the ones a k-item window can be showing; they are consumed oldest first.
            val ownerId = existing?.id
            val preExisting: Map<String, ArrayDeque<Long>> = if (ownerId != null && ReconcileNote.NO_PREVIOUS_WINDOW in reconcile.notes) {
                reconcile.decisions.filter { it is Decision.New && !it.confirmedById }
                    .groupingBy { it.fingerprint }.eachCount()
                    .mapValues { (fp, k) -> ArrayDeque(db.messageDao().findLatestIdsByFingerprint(ownerId, fp, k).asReversed()) }
                    .filterValues { it.isNotEmpty() }
            } else {
                emptyMap()
            }
            val newIds = ArrayList<Long>()
            val ambiguousIds = ArrayList<Long>()
            val pendingMedia = ArrayList<Long>()
            val revisedIds = ArrayList<Long>()
            var suppressed = 0
            // Decision index -> message id; an explicit null means "observed, but no stored row"
            // (e.g. a window id that no longer exists) so the checkpoint never keeps a dangling id.
            val storedIds = HashMap<Int, Long?>()
            var lastStored: MessageCandidate? = null

            for ((index, decision) in reconcile.decisions.withIndex()) {
                val c = decision.candidate
                when (decision) {
                    is Decision.New, is Decision.AmbiguousRepeat -> {
                        val token = db.suppressionDao().token(suppressionKey, decision.fingerprint, now)
                        if (token != null && SuppressionRule.applies(token.sourceMessageId, token.postedAtEpochMs, c.sourceMessageId, snapshot.postedAtEpochMs)) {
                            suppressed++
                            // Nothing stored for this position; keep a verified link only.
                            storedIds[index] = (decision as? Decision.AmbiguousRepeat)?.existingMessageId?.takeIf { db.messageDao().get(it) != null }
                            continue
                        }
                        // Checkpoint loss guard: with no previous window (e.g. checkpoint pruned) an
                        // already-stored identical message is linked, not inserted again.
                        if (decision is Decision.New && !decision.confirmedById) {
                            val existingId = preExisting[decision.fingerprint]?.removeFirstOrNull()
                            if (existingId != null) {
                                storedIds[index] = existingId
                                db.observationLinkDao().insert(ObservationLinkEntity(messageId = existingId, eventId = snapshot.eventId, kind = KnownKind.STALE_WINDOW.name, observedAtEpochMs = now))
                                db.messageDao().incrementObservation(existingId)
                                continue
                            }
                        }
                        val dedup = when (decision) {
                            is Decision.AmbiguousRepeat -> DedupState.AMBIGUOUS_REPEAT
                            is Decision.New -> if (decision.confirmedById) DedupState.CONFIRMED else DedupState.CANDIDATE
                            else -> DedupState.CANDIDATE
                        }
                        val wantsMedia = c.media != null
                        val id = db.messageDao().insert(
                            MessageEntity(
                                conversationId = conversationIdOrCreate(),
                                sourceMessageId = c.sourceMessageId,
                                senderName = c.sender?.displayName,
                                senderKey = c.sender?.senderKey,
                                isSelf = c.sender?.isSelf ?: false,
                                body = c.body,
                                kind = c.kind.name,
                                sourceTimestampEpochMs = c.sourceTimestampEpochMs,
                                timestampQuality = c.timestampQuality.name,
                                observedAtEpochMs = now,
                                postedAtEpochMs = snapshot.postedAtEpochMs,
                                origin = snapshot.origin.name,
                                contentStatus = c.contentStatus.name,
                                dedupState = dedup.name,
                                revisionCount = 0,
                                observationCount = 1,
                                mediaState = when {
                                    !wantsMedia -> MediaState.NONE.name
                                    !mediaAllowed -> MediaState.DISABLED_BY_USER.name
                                    c.media?.uri == null && c.media?.fromNotificationBitmap != true -> MediaState.PLACEHOLDER_ONLY.name
                                    else -> MediaState.PENDING.name
                                },
                                mediaBlobId = null,
                                mediaUri = c.media?.uri,
                                mediaMimeType = c.media?.mimeType,
                                fingerprint = decision.fingerprint,
                                eventId = snapshot.eventId,
                                // This message's own body, not the notification's. The snapshot
                                // flag is about the notification: a batch in which only the second
                                // message was cut — or only the title — used to mark every row.
                                truncationFlags = truncationColumn(c.textTruncated),
                                sortKey = sortKey(c, snapshot),
                                expiresAtEpochMs = retentionMs?.let { now + it },
                            ),
                        )
                        storedIds[index] = id
                        indexTokens(db, id, c.body)
                        if (decision is Decision.AmbiguousRepeat) {
                            ambiguousIds += id
                            // The linked message may have been deleted since the window was written.
                            decision.existingMessageId?.takeIf { db.messageDao().get(it) != null }?.let {
                                db.observationLinkDao().insert(ObservationLinkEntity(messageId = it, eventId = snapshot.eventId, kind = "AMBIGUOUS_REPEAT", observedAtEpochMs = now))
                            }
                        } else {
                            newIds += id
                        }
                        if (wantsMedia && mediaAllowed && (c.media?.uri != null || c.media?.fromNotificationBitmap == true)) pendingMedia += id
                        lastStored = c
                    }
                    is Decision.Known -> {
                        // Window ids can point at messages deleted by the user or by retention; an
                        // FK violation here would roll back the whole batch, so verify first.
                        val id = decision.existingMessageId?.takeIf { db.messageDao().get(it) != null }
                        storedIds[index] = id
                        if (id != null) {
                            // A repost of the same text can still carry evidence the first
                            // observation did not have: the body is unchanged, and this time the
                            // snapshot says it was cut. The fingerprint does not include that —
                            // adding it would split one message into two rows — so it is applied
                            // here instead of being dropped with the decision (round 34 I2).
                            truncationColumn(c.textTruncated)?.let { db.messageDao().markTruncated(id, it) }
                            if (decision.kind != KnownKind.REPOST) {
                                db.observationLinkDao().insert(ObservationLinkEntity(messageId = id, eventId = snapshot.eventId, kind = decision.kind.name, observedAtEpochMs = now))
                                db.messageDao().incrementObservation(id)
                            }
                        }
                    }
                    is Decision.Revision -> {
                        val id = decision.existingMessageId
                        val old = db.messageDao().get(id)
                        if (old != null) {
                            db.revisionDao().insert(MessageRevisionEntity(messageId = id, body = old.body, observedAtEpochMs = now, eventId = snapshot.eventId))
                            db.messageDao().applyRevision(id, c.body, snapshot.eventId, truncationColumn(c.textTruncated))
                            db.searchDao().deleteForMessage(id)
                            indexTokens(db, id, c.body)
                            revisedIds += id
                            storedIds[index] = id
                        } else {
                            storedIds[index] = null
                        }
                    }
                }
            }

            // Checkpoint with real ids so later windows can link back. Items carried over from the
            // previous window keep their ids; items from this batch map through their decision index.
            val items = reconcile.newWindow.items.map { item ->
                val index = item.decisionIndex
                val id = if (index != null && storedIds.containsKey(index)) storedIds[index] else item.messageId
                WindowItemJson(item.fingerprint, item.sourceMessageId, id)
            }
            db.checkpointDao().upsert(
                CheckpointEntity(
                    streamKey = identity.streamKey,
                    packageName = identity.scope.packageName,
                    notificationKey = snapshot.notificationKey,
                    windowJson = json.encodeToString(windowSerializer, items),
                    closed = false,
                    parserId = batch.parserId,
                    parserVersion = batch.parserVersion,
                    generation = generation,
                    updatedAtEpochMs = now,
                    postedAtEpochMs = reconcile.newWindow.postedAtEpochMs,
                ),
            )

            // Conversation projection.
            val current = conversationId?.let { convDao.get(it) }
            if (current != null) {
                val touched = newIds.isNotEmpty() || ambiguousIds.isNotEmpty()
                convDao.update(
                    current.copy(
                        title = identity.displayTitle ?: current.title,
                        isGroup = batch.conversation?.isGroup ?: current.isGroup,
                        identityConfidence = identity.confidence.name,
                        lastActivityEpochMs = if (touched) maxOf(current.lastActivityEpochMs, now) else current.lastActivityEpochMs,
                        messageCount = current.messageCount + newIds.size,
                        ambiguousCount = current.ambiguousCount + ambiguousIds.size,
                        lastMessagePreview = if (touched) lastStored?.body?.takeCodePoints(PREVIEW_CODE_POINTS) ?: current.lastMessagePreview else current.lastMessagePreview,
                        lastSenderName = if (touched) lastStored?.sender?.displayName ?: current.lastSenderName else current.lastSenderName,
                    ),
                )
            }

            lossOnCommit?.invoke()
            db.journalDao().setState(snapshot.eventId, "COMMITTED", null)
            CommitOutcome(conversationId, newIds, ambiguousIds, pendingMedia, suppressed, summaryRecorded, revisedIds)
        }
    }

    private suspend fun indexTokens(db: dev.quietinbox.platform.storage.db.QuietInboxDatabase, messageId: Long, body: String) {
        val tokens = SearchNormalizer.tokens(SearchNormalizer.normalize(body))
        if (tokens.isEmpty()) return
        db.searchDao().insertTokens(tokens.map { SearchTokenEntity(it, messageId) })
    }

    private fun sortKey(c: MessageCandidate, snapshot: NotificationSnapshot): Long = when {
        c.sourceTimestampEpochMs != null && c.timestampQuality != TimestampQuality.OBSERVED_ONLY -> c.sourceTimestampEpochMs!!
        snapshot.postedAtEpochMs != null -> snapshot.postedAtEpochMs!!
        else -> snapshot.observedAtEpochMs
    }
}

/**
 * What a claim for an event's carried-over loss found.
 *
 * Only [RECORDED] and [ALREADY_RECORDED] mean the gap is on disk. The other two mean it is not,
 * and a caller that treats them as success destroys the payload that was the only evidence of it.
 */
/**
 * What became of a row after a failed commit was charged to it (issue #28).
 *
 * - [RETRYABLE]: still PENDING and still a replay candidate — within the attempt budget, or at the
 *   threshold but with neither the loss record nor the deferral writable, in which case it holds its
 *   place on the page with its attempts unchanged.
 * - [FAILED_RECORDED]: filed FAILED, payload cleared, and the loss of the whole event on disk in the
 *   same transaction.
 * - [FAILED_DEFERRED]: the loss could not be recorded; the row stays PENDING with its payload and is
 *   parked out of the candidate set until a pass resumes it, when the commit is tried once more.
 * - [NOT_PENDING]: the row had already left PENDING; nothing written.
 */
enum class JournalRetry { RETRYABLE, FAILED_RECORDED, FAILED_DEFERRED, NOT_PENDING }

enum class LossClaim {
    /** This call wrote the gap, in the claim's own transaction. */
    RECORDED,

    /** Another pass wrote it; the row is settled and no one may write it again. */
    ALREADY_RECORDED,

    /** The gap is not written and the row is waiting for a pass that can write it. */
    DEFERRED,

    /** The row has left PENDING since it was read; its payload is gone and nothing is owed. */
    NOT_PENDING,
    ;

    /** Whether the loss is recorded — the only question a caller about to commit may ask. */
    val gapIsDurable: Boolean get() = this == RECORDED || this == ALREADY_RECORDED
}

/** Where a paged walk over one source's pending journal rows has got to. */
data class JournalCursor(val receivedAtEpochMs: Long, val eventId: String) {
    companion object {
        val START = JournalCursor(Long.MIN_VALUE, "")
    }
}

/**
 * A page of decodable pending snapshots, and where to continue.
 *
 * A non-null [next] means "ask again", not "there is another row": a walk whose last page happens
 * to be exactly `limit` long has already returned everything, and only the empty page after it says
 * so. Null is the confirmed end (round 36 Codex M3).
 */
data class JournalPage(val snapshots: List<NotificationSnapshot>, val next: JournalCursor?)
