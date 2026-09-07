package dev.quietinbox.core.model

/** Capture health — the first user-facing dimension. Connected never implies the source posts. */
enum class ListenerState {
    NOT_GRANTED,
    GRANTED_DISCONNECTED,
    CONNECTED,
    PAUSED,
    RECONNECTING,
    DEGRADED,
}

enum class GapReason {
    LISTENER_DISCONNECTED,
    PROCESS_RESTART,
    NOT_GRANTED,
    PAUSED_BY_USER,
    QUEUE_OVERFLOW,
    BEFORE_FIRST_UNLOCK,

    /** A reset or restore held the vault; events during that window were not captured (QI-SEC-003). */
    MAINTENANCE,

    /** Notifications arrived before the source list was known and the vault did not open in time; nothing was read from them (QI-CAPTURE-013). */
    COLD_START,

    /** The user switched this source off. Its own reason, not the bucket that also holds a revoke. */
    SOURCE_DISABLED_BY_USER,

    /** The user paused this source. */
    SOURCE_PAUSED_BY_USER,

    /**
     * A notification carried more messages than [Limits.MAX_MESSAGES] and the oldest were discarded
     * before anything was parsed. The ingest that followed succeeded, so nothing else would ever
     * have said that content was lost — a gap hidden inside a success.
     */
    MESSAGES_DROPPED,
    UNKNOWN,
}

enum class GapPrecision { EXACT, BOUNDED, UNKNOWN }

/**
 * A possible capture gap. Either bound may be null when no reliable timestamp exists; the
 * pipeline never fabricates precise times.
 */
data class GapInterval(
    val id: Long,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
    val reason: GapReason,
    val precision: GapPrecision,
    /**
     * The source the gap belongs to, when one is known. Most gaps are process-wide — a disconnect,
     * a restart, a maintenance run — and for those it is null and must stay null. It can never
     * carry a conversation: identity is resolved during ingest, which is exactly what did not
     * happen for an event that was dropped.
     */
    val packageName: String? = null,
)

data class CaptureHealth(
    val listenerState: ListenerState,
    val connectedSinceEpochMs: Long?,
    val lastEventAtEpochMs: Long?,
    val queueDepth: Int,
    val overflowCount: Long,
    val acceptedCount: Long,
    val gaps: List<GapInterval>,
    val activeGeneration: String?,
)

data class SourceConfiguration(
    val packageName: String,
    val displayName: String,
    val enabled: Boolean,
    val paused: Boolean,
    val retentionDays: Int?,
    val mediaEnabled: Boolean,
    val addedAtEpochMs: Long,
    val adapterId: String?,
)

/** Well-known sources with versioned adapters. Anything else goes through the standard parser. */
object KnownSources {
    const val LINE = "jp.naver.line.android"
    const val WHATSAPP = "com.whatsapp"
    const val TELEGRAM = "org.telegram.messenger"
    const val INSTAGRAM = "com.instagram.android"
    const val MESSENGER = "com.facebook.orca"

    val ALL: List<String> = listOf(LINE, WHATSAPP, TELEGRAM, INSTAGRAM, MESSENGER)
}
