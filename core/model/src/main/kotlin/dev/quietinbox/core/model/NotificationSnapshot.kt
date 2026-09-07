package dev.quietinbox.core.model

import kotlinx.serialization.Serializable

/** How a snapshot entered the pipeline. */
@Serializable
enum class CaptureOrigin {
    /** Delivered live by `onNotificationPosted`. */
    LIVE,

    /** Read from the active notification list after (re)connecting; this is *not* history. */
    ACTIVE_RESYNC,

    /** Posted by QuietInbox itself as an explicitly marked synthetic test notification. */
    SYNTHETIC,

    /** Re-played from the durable event journal after a crash or restart. */
    REPLAY,
}

/** A text value that has passed the size limit, with a flag if it was cut. */
@Serializable
data class BoundedText(
    val value: String,
    val truncated: Boolean = false,
) {
    companion object {
        fun of(raw: CharSequence?, max: Int = Limits.MAX_TEXT_CHARS): BoundedText? {
            if (raw == null) return null
            val s = raw.toString()
            if (s.isEmpty()) return null
            return if (s.length > max) BoundedText(s.substring(0, max), truncated = true) else BoundedText(s)
        }
    }
}

@Serializable
enum class NotificationTemplate { MESSAGING, BIG_TEXT, INBOX, BIG_PICTURE, MEDIA, CALL, BASE, UNKNOWN }

@Serializable
/**
 * What had to be cut to fit the snapshot inside [Limits].
 *
 * `MESSAGES` and `MESSAGES_DROPPED` are deliberately separate, and so are their historic twins.
 * They used to be one flag raised by two different losses in the same function — a kept message
 * whose text was shortened, and whole messages discarded because the batch exceeded
 * [Limits.MAX_MESSAGES]. Only the second is content the vault never sees, so only the second may
 * become a gap; conflating them would have manufactured gaps that never happened.
 *
 * [LINES] and [LINES_DROPPED] were split for the same reason, one release later: the single flag
 * meant "lines were dropped" while its neighbours meant "text was shortened", so reading it right
 * depended on knowing which function had raised it.
 */
enum class TruncationFlag {
    TITLE,
    TEXT,
    BIG_TEXT,

    /**
     * Legacy only. Releases up to 0.1.3 raised this when an InboxStyle notification carried more
     * lines than the snapshot may hold — a whole-line loss, despite the name reading like the
     * shortening flags above it. This release never writes it; [LINES_DROPPED] says the same thing
     * in the same vocabulary as the message flags. In a stored payload it therefore identifies a
     * journal row written before the split, which is how the upgrade path recognises a loss those
     * releases recorded nowhere else.
     */
    LINES,

    /** Whole lines were discarded from an InboxStyle notification: content the vault never sees. */
    LINES_DROPPED,

    /** A message was kept, but its text was shortened. */
    MESSAGES,

    /** Whole messages were discarded from this batch: content nothing downstream will ever see. */
    MESSAGES_DROPPED,
    HISTORIC_MESSAGES,
    HISTORIC_MESSAGES_DROPPED,
    ACTIONS,
    EXTRAS,
    URI,
}

/** One entry of a `MessagingStyle` notification, reduced to allow-listed fields. */
@Serializable
data class MessagingMessageShape(
    val text: BoundedText? = null,
    val timestampEpochMs: Long? = null,
    val senderName: BoundedText? = null,
    /** Stable key the source attached to the `Person`, if any. */
    val senderKey: String? = null,
    val senderUri: String? = null,
    val isSelf: Boolean = false,
    val dataMimeType: String? = null,
    /** Only `content://` URIs are retained; anything else is dropped and flagged. */
    val dataUri: String? = null,
    val isRemoteInputHistory: Boolean = false,
)

@Serializable
data class ActionShape(
    val title: BoundedText? = null,
    val hasRemoteInput: Boolean = false,
    val semanticAction: Int = 0,
)

/**
 * The allow-listed, size-bounded projection of one Android notification. Everything here is
 * *untrusted input*; parsers may only read these fields. There is deliberately no `Bundle`,
 * `PendingIntent`, `Bitmap` or `RemoteViews` in this contract.
 */
@Serializable
data class NotificationShape(
    val id: Int = 0,
    val tag: String? = null,
    val channelId: String? = null,
    val category: String? = null,
    val groupKey: String? = null,
    val isGroupSummary: Boolean = false,
    val shortcutId: String? = null,
    val template: NotificationTemplate = NotificationTemplate.UNKNOWN,
    val title: BoundedText? = null,
    val titleBig: BoundedText? = null,
    val text: BoundedText? = null,
    val bigText: BoundedText? = null,
    val subText: BoundedText? = null,
    val summaryText: BoundedText? = null,
    val infoText: BoundedText? = null,
    val textLines: List<BoundedText> = emptyList(),
    val conversationTitle: BoundedText? = null,
    val isGroupConversation: Boolean? = null,
    val selfDisplayName: BoundedText? = null,
    val messages: List<MessagingMessageShape> = emptyList(),
    val historicMessages: List<MessagingMessageShape> = emptyList(),
    val hasLargeIcon: Boolean = false,
    val hasPicture: Boolean = false,
    val pictureUri: String? = null,
    val actions: List<ActionShape> = emptyList(),
    /** Names only — values of unknown extras are never copied. */
    val extraKeys: List<String> = emptyList(),
    val whenEpochMs: Long? = null,
    val visibility: Int = 0,
    val isOngoing: Boolean = false,
    val isLocalOnly: Boolean = false,
    val truncated: Set<TruncationFlag> = emptySet(),
)

/**
 * Immutable, durable capture event. `observedAtEpochMs` (wall clock), `elapsedRealtimeMs`
 * (monotonic) and `postedAtEpochMs` (what the source claims) are distinct on purpose.
 */
@Serializable
data class NotificationSnapshot(
    val eventId: String,
    val source: SourceScope,
    val notificationKey: String,
    val notificationGeneration: String,
    val observedAtEpochMs: Long,
    val elapsedRealtimeMs: Long,
    val bootSessionId: String,
    val postedAtEpochMs: Long?,
    val origin: CaptureOrigin,
    val shape: NotificationShape,
    val parserInputVersion: Int = PARSER_INPUT_VERSION,
) {
    companion object {
        /** Bump when [NotificationShape] changes incompatibly. */
        const val PARSER_INPUT_VERSION: Int = 1
    }
}
