package dev.quietinbox.platform.storage.repo

import androidx.room.withTransaction
import dev.quietinbox.core.model.Conversation
import dev.quietinbox.core.model.Message
import dev.quietinbox.core.model.MessageRevision
import dev.quietinbox.core.model.SourceScope
import dev.quietinbox.platform.storage.db.ConversationEntity
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.DeletionSuppressionEntity
import dev.quietinbox.platform.storage.db.QuietInboxDatabase
import dev.quietinbox.platform.storage.db.MediaBlobEntity
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.retention.MediaDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

data class InboxCounts(val conversations: Int, val messages: Int, val ambiguous: Int, val summaries: Int)

/** Read side for the inbox and conversation screens; local-only state changes. */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class InboxRepository @Inject constructor(
    private val holder: DatabaseHolder,
    private val mediaDir: MediaDirectory,
) {
    val vaultState: Flow<VaultState> get() = holder.state

    /**
     * Instant used for visible-copy filters. Tests replace this and call [tickVisibility] so a
     * Flow can cross an expiry boundary without a database write (Room keeps query args frozen).
     */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }
    internal var visibilityTickMs: Long = 1_000L
    private val extraTicks = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    fun tickVisibility(at: Long = nowMs()) { extraTicks.tryEmit(at) }

    /** Clock checks are cheap; only a crossed visibility boundary rebinds the read queries. */
    private fun visibilityNow(db: QuietInboxDatabase): Flow<Long> =
        extraTicks.onStart { emit(nowMs()) }.flatMapLatest { start ->
            flow {
                var bound = start
                emit(bound)
                while (true) {
                    bound = combine(
                        db.messageDao().observeNextExpiryAfter(bound),
                        flow {
                            while (true) {
                                emit(nowMs())
                                delay(visibilityTickMs.coerceAtLeast(1L))
                            }
                        },
                    ) { expiry, current -> expiry to current }
                        .first { (expiry, current) -> current < bound || (expiry != null && current >= expiry) }
                        .second
                    // Use current time, not the expired row's timestamp: a forward clock jump
                    // skips all past boundaries at once. A backward jump reopens the filter.
                    emit(bound)
                }
            }
        }

    fun observeConversations(archived: Boolean, packages: Set<String>): Flow<List<Conversation>> =
        holder.flowWithDb { db ->
            visibilityNow(db).flatMapLatest { t ->
                db.conversationDao().observeInboxAt(archived, packages.isEmpty(), packages.toList(), t)
            }.map { rows -> rows.map { it.toDomain() } }
        }

    fun observeConversation(id: Long): Flow<Conversation?> =
        holder.flowWithDb { db ->
            visibilityNow(db).flatMapLatest { t -> db.conversationDao().observeAt(id, t) }
        }.map { it?.toDomain() }

    /**
     * Expired copies are hidden from the moment of each emission, not only once retention ran
     * (QI-DATA-007). [now] if passed is a one-shot bound (tests); otherwise the clock can move
     * without a write.
     */
    fun observeMessages(conversationId: Long, now: Long? = null): Flow<List<Message>> =
        holder.flowWithDb { db ->
            if (now != null) db.messageDao().observeForConversation(conversationId, now)
            else visibilityNow(db).flatMapLatest { t -> db.messageDao().observeForConversation(conversationId, t) }
        }.map { rows -> rows.map { it.toDomain() } }

    fun observeRevisions(messageId: Long): Flow<List<MessageRevision>> =
        holder.flowWithDb { db -> db.revisionDao().observeForMessage(messageId) }.map { rows -> rows.map { it.toDomain() } }

    fun observePackagesWithData(): Flow<List<String>> = holder.flowWithDb { db -> db.conversationDao().observePackages() }

    fun observeCounts(now: Long? = null): Flow<InboxCounts> = holder.flowWithDb { db ->
        val times: Flow<Long> = if (now != null) flowOf(now) else visibilityNow(db)
        times.flatMapLatest { t ->
            combine(
                db.conversationDao().observeCount(),
                db.messageDao().observeCount(t),
                db.messageDao().observeAmbiguousCount(t),
                db.healthDao().observeSummaryCount(),
            ) { c, m, a, s -> InboxCounts(c, m, a, s) }
        }
    }

    /** Messages of one package observed since [since] — onboarding's proof that a capture worked. */
    fun observeCapturedSince(packageName: String, since: Long): Flow<Int> =
        holder.flowWithDb { db -> db.messageDao().observeCapturedSince(packageName, since) }

    suspend fun markViewed(conversationId: Long, now: Long) = holder.db().conversationDao().markViewed(conversationId, now)

    /** How many conversations (of [packages], or all when empty) have copies the user has not looked at yet (QI-REMIND-015). */
    suspend fun unviewedConversationCount(packages: Set<String> = emptySet()): Int =
        holder.db().conversationDao().unviewedCount(packages.isEmpty(), packages.toList(), nowMs())
    suspend fun setPinned(conversationId: Long, pinned: Boolean) = holder.db().conversationDao().setPinned(conversationId, pinned)
    suspend fun setArchived(conversationId: Long, archived: Boolean) = holder.db().conversationDao().setArchived(conversationId, archived)

    /**
     * Deletes messages and records a body-free suppression token so an active-notification
     * replay cannot resurrect them (plan section 7.3). The token carries the deleted message's
     * source id and post time so a later, genuinely new message with the same text is not
     * swallowed (QI-DEDUP-009). Media rows go in the same transaction and their files right
     * after it; the conversation projection is rebuilt from what remains (QI-DATA-004).
     */
    suspend fun deleteMessages(ids: List<Long>, now: Long, suppressionTtlMs: Long) {
        if (ids.isEmpty()) return
        val db = holder.db()
        val files = db.withTransaction {
            val rows = db.messageDao().getAll(ids)
            val scopeKeys = HashMap<Long, String>()
            for (row in rows) {
                val key = scopeKeys.getOrPut(row.conversationId) { db.conversationDao().get(row.conversationId)?.suppressionScopeKey() ?: "" }
                if (key.isNotEmpty()) db.suppressionDao().upsert(DeletionSuppressionEntity(key, row.fingerprint, now + suppressionTtlMs, row.sourceMessageId, row.postedAtEpochMs))
            }
            val blobs = db.mediaDao().forMessages(ids)
            if (blobs.isNotEmpty()) db.mediaDao().delete(blobs.map { it.id })
            db.messageDao().delete(ids)
            db.conversationDao().rebuildProjection(rows.map { it.conversationId }.distinct(), now)
            blobs.fileNames()
        }
        for (f in files) mediaDir.delete(f)
    }

    /** Deletes a conversation and every message in it, with the same replay suppression. */
    suspend fun deleteConversation(conversationId: Long, now: Long, suppressionTtlMs: Long) {
        val db = holder.db()
        val files = db.withTransaction {
            val key = db.conversationDao().get(conversationId)?.suppressionScopeKey()
            if (key != null) {
                for (row in db.messageDao().forConversation(conversationId)) {
                    db.suppressionDao().upsert(DeletionSuppressionEntity(key, row.fingerprint, now + suppressionTtlMs, row.sourceMessageId, row.postedAtEpochMs))
                }
            }
            val blobs = db.mediaDao().forConversation(conversationId)
            if (blobs.isNotEmpty()) db.mediaDao().delete(blobs.map { it.id })
            db.conversationDao().delete(conversationId)
            blobs.fileNames()
        }
        for (f in files) mediaDir.delete(f)
    }
}

/** Every file a set of blob rows owns: the blob and, when present, its thumbnail. */
internal fun List<MediaBlobEntity>.fileNames(): List<String> = flatMap { listOfNotNull(it.fileName, it.thumbFileName) }

/** Stable identity of a conversation for suppression: scope + identity key, independent of the row id. */
internal fun ConversationEntity.suppressionScopeKey(): String =
    suppressionScopeKey(SourceScope(packageName, profileKey, accountKey), identityKey)

internal fun suppressionScopeKey(scope: SourceScope, identityKey: String): String = scope.key + "#" + identityKey
