package dev.quietinbox.platform.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Per-source configuration (plan section 8: SourceConfiguration). */
@Entity(tableName = "source_configuration")
data class SourceConfigurationEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val enabled: Boolean,
    val paused: Boolean,
    /** Null = use the global default. */
    val retentionDays: Int?,
    val mediaEnabled: Boolean,
    val addedAtEpochMs: Long,
    val adapterId: String?,
)

/** A listener connection epoch. */
@Entity(tableName = "capture_session")
data class CaptureSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val generation: String,
    val bootSessionId: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val endReason: String?,
)

@Entity(tableName = "gap_interval", indices = [Index("startEpochMs")])
data class GapIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
    val reason: String,
    val precision: String,
    val createdAtEpochMs: Long,
    /** Null for a process-wide gap; never a conversation, which is unknowable for a dropped event. */
    val packageName: String? = null,
)

/**
 * `event_journal.lossRecorded`: whether the loss the event arrived with has reached the gap table,
 * and whether the row is currently out of the replay's reach because a gap write for it failed.
 *
 * Two bits, because the two questions are independent. Bit 1 is *settled*: the event's own loss
 * — the one it arrived with, or the one a release before this carried in unrecorded — is on disk,
 * and no later pass may write it again. Bit 2 is *deferred*: a gap write the row needed was refused,
 * and the row is out of every pending read until a pass puts it back. A settlement that cannot be
 * written is neither settled nor merely unsettled (round 36): calling it unsettled leaves it at the
 * head of every replay page for ever, and calling it settled throws away the only evidence of what
 * was lost. And a row whose *commit* attempts ran out needs the same parking place whether its own
 * loss was settled or not (issue #28), which is why the deferral is a bit on top of the settled
 * value rather than a third value beside it: a resume gives a row back exactly the settled state
 * it had, so a settled row cannot be claimed twice on the way back.
 *
 * The four values are 0 (unsettled), 1 (settled), 2 (unsettled, deferred), 3 (settled, deferred);
 * deferral adds 2 and a resume subtracts it, and the replay's candidate reads take `< 2`.
 */
const val LOSS_UNSETTLED = 0

/** The gap is written; no later pass may write it again. */
const val LOSS_SETTLED = 1

/** The gap write failed; retried when one is next seen to succeed, evidence untouched meanwhile. */
const val LOSS_DEFERRED = 2

/** [LOSS_DEFERRED] on a row whose own loss was already settled: a resume takes it back to [LOSS_SETTLED]. */
const val LOSS_DEFERRED_SETTLED = 3

/** The deferred bit; values at or above it are out of the replay's candidate set. */
const val LOSS_DEFERRED_BIT = 2

/** Durable, short-TTL copy of accepted input. Committed rows are pruned by retention. */
@Entity(
    tableName = "event_journal",
    indices = [
        Index("state"),
        Index("expiresAtEpochMs"),
        // The settle walk's whole predicate and its order, so a source with many pending rows is
        // read by seeking to the cursor rather than by scanning every PENDING row again per page
        // (round 36 Codex I2 measured the scan growing with the square of the row count).
        Index("packageName", "state", "lossRecorded", "receivedAtEpochMs", "eventId"),
    ],
)
data class EventJournalEntity(
    @PrimaryKey val eventId: String,
    val generation: String,
    val receivedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    /** PENDING, COMMITTED, FAILED, SKIPPED, DISCARDED */
    val state: String,
    val attempts: Int,
    val failureCode: String?,
    /**
     * JSON of [dev.quietinbox.core.model.NotificationSnapshot] while the row is PENDING; cleared
     * (empty string) the moment the row reaches a terminal state, so notification text never
     * outlives its commit (QI-DATA-004).
     */
    val payload: String,
    /** Source package, so a disabled or removed source can discard its pending rows (schema v3). */
    val packageName: String? = null,
    /**
     * How far this event's own loss — content the framework had already dropped when the
     * notification reached us — has got towards the gap table (schema v4):
     *
     * - [LOSS_UNSETTLED]: nothing written yet. Every row a release up to 0.1.3 left pending is
     *   here, because that release wrote the loss nowhere.
     * - [LOSS_SETTLED]: written. Set in the same transaction as the insert for an event this
     *   release accepts with a loss, and claimed exactly once for a carried-over row. It is the
     *   idempotency boundary: the claim is a conditional update, so a replayed row cannot record
     *   the same loss a second time.
     * - [LOSS_DEFERRED] and [LOSS_DEFERRED_SETTLED]: a gap write the row needed failed, and the row
     *   is out of the replay's candidate set until a gap write is seen to succeed. Without a state
     *   of its own such a row is either charged for the failure (three tries file it FAILED and
     *   clear the payload — round 35) or left at the head of every page for ever, starving the rows
     *   behind it (round 36 Codex I1). The transition back — to whichever of the first two it came
     *   from — is what makes it a deferral rather than a verdict: the payload, the evidence and the
     *   settled bit are untouched throughout. Two gap writes can put a row here: its own loss
     *   (only from [LOSS_UNSETTLED]), and the record of its commit attempts running out (from
     *   either, issue #28).
     */
    val lossRecorded: Int = LOSS_UNSETTLED,
)

/** Last visible window per notification stream (plan section 8: NotificationCheckpoint). */
@Entity(tableName = "notification_checkpoint")
data class CheckpointEntity(
    @PrimaryKey val streamKey: String,
    val packageName: String,
    val notificationKey: String,
    /** JSON list of window items. */
    val windowJson: String,
    val closed: Boolean,
    val parserId: String,
    val parserVersion: String,
    val generation: String,
    val updatedAtEpochMs: Long,
    /** Post time of the notification that produced the window (resync detection). */
    val postedAtEpochMs: Long? = null,
)

@Entity(
    tableName = "conversation",
    indices = [
        Index(value = ["packageName", "profileKey", "accountKey", "identityKey"], unique = true),
        Index("lastActivityEpochMs"),
        Index("archived"),
    ],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val profileKey: String,
    val accountKey: String?,
    val identityKey: String,
    val identityConfidence: String,
    val title: String?,
    val isGroup: Boolean?,
    val pinned: Boolean,
    val archived: Boolean,
    val createdAtEpochMs: Long,
    val lastActivityEpochMs: Long,
    val lastViewedEpochMs: Long?,
    val messageCount: Int,
    val ambiguousCount: Int,
    /** Reserved: summary-only observations are not attributable to a conversation in v0.1, so this stays 0. */
    val summaryOnlyCount: Int,
    val lastMessagePreview: String?,
    val lastSenderName: String?,
)

@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["conversationId", "sortKey"]),
        Index(value = ["conversationId", "sourceMessageId"]),
        Index("fingerprint"),
        Index("expiresAtEpochMs"),
        Index("observedAtEpochMs"),
        Index("mediaState"),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val sourceMessageId: String?,
    val senderName: String?,
    val senderKey: String?,
    val isSelf: Boolean,
    val body: String,
    val kind: String,
    val sourceTimestampEpochMs: Long?,
    val timestampQuality: String,
    val observedAtEpochMs: Long,
    val postedAtEpochMs: Long?,
    val origin: String,
    val contentStatus: String,
    val dedupState: String,
    val revisionCount: Int,
    val observationCount: Int,
    val mediaState: String,
    val mediaBlobId: Long?,
    val mediaUri: String?,
    val mediaMimeType: String?,
    val fingerprint: String,
    val eventId: String,
    val sortKey: Long,
    val expiresAtEpochMs: Long?,
    /**
     * `TruncationFlag.TEXT` when this row's own body was shortened, otherwise null. It is a column
     * of names rather than a boolean because "the text was cut" and "whole messages were dropped"
     * are different losses that must stay distinguishable — but only the first is ever a property
     * of one message, so only the first is ever stored here. The second is a gap.
     */
    val truncationFlags: String? = null,
)

@Entity(
    tableName = "message_revision",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("messageId")],
)
data class MessageRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val body: String,
    val observedAtEpochMs: Long,
    val eventId: String,
)

/** Links extra observations (re-posts, stale windows, ambiguous repeats) to a stored message. */
@Entity(
    tableName = "observation_link",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("messageId")],
)
data class ObservationLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val eventId: String,
    /** REPOST, STALE_WINDOW, SAME_ID, AMBIGUOUS_REPEAT */
    val kind: String,
    val observedAtEpochMs: Long,
)

@Entity(tableName = "media_blob", indices = [Index("messageId"), Index("state")])
data class MediaBlobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long?,
    /** Opaque file name inside files/media; never derived from content. */
    val fileName: String,
    val thumbFileName: String?,
    val mimeType: String?,
    val byteCount: Long,
    val width: Int?,
    val height: Int?,
    val state: String,
    val failureReason: String?,
    val createdAtEpochMs: Long,
)

/**
 * Prevents replay of active notifications from resurrecting user-deleted content. Keyed by the
 * stable conversation identity (`SourceScope.key + "#" + identityKey`), not the row id, so it still
 * applies after the whole conversation row was deleted and a replay re-creates it.
 */
@Entity(tableName = "deletion_suppression", primaryKeys = ["scopeKey", "fingerprint"], indices = [Index("expiresAtEpochMs")])
data class DeletionSuppressionEntity(
    val scopeKey: String,
    val fingerprint: String,
    val expiresAtEpochMs: Long,
    /** Source id of the deleted message when the parser proved one (schema v3, QI-DEDUP-009). */
    val sourceMessageId: String? = null,
    /** Post time of the notification the deleted message came from (schema v3, QI-DEDUP-009). */
    val postedAtEpochMs: Long? = null,
)

@Entity(
    tableName = "search_token",
    primaryKeys = ["token", "messageId"],
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("messageId")],
)
data class SearchTokenEntity(
    val token: String,
    val messageId: Long,
)

/** Summary-only observations ("5 new messages") that have no conversation body. */
@Entity(tableName = "summary_observation", indices = [Index("observedAtEpochMs")])
data class SummaryObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val observedAtEpochMs: Long,
    val messageCount: Int?,
    val conversationCount: Int?,
    val eventId: String,
)

/** Body-free diagnostics: codes, versions and counts only. */
@Entity(tableName = "local_diagnostic_event", indices = [Index("atEpochMs")])
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val detail: String?,
    val packageName: String?,
    val atEpochMs: Long,
)

/** Row shape for analytics: no ids are needed beyond conversation grouping. */
data class MessageStatRow(
    val conversationId: Long,
    val sortKey: Long,
    val dedupState: String,
    val contentStatus: String,
    val body: String,
    val senderName: String?,
    val senderKey: String?,
    val isSelf: Boolean,
    @ColumnInfo(name = "packageName") val packageName: String,
)
