package dev.quietinbox.platform.capture

import android.content.Context
import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.core.identity.IdentityResolver
import dev.quietinbox.core.model.CaptureOrigin
import dev.quietinbox.core.model.GapPrecision
import dev.quietinbox.core.model.GapReason
import dev.quietinbox.core.model.Limits
import dev.quietinbox.core.model.ListenerState
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.core.model.NotificationShape
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.SourceConfiguration
import dev.quietinbox.core.model.SourceScope
import dev.quietinbox.core.model.TruncationFlag
import dev.quietinbox.core.parser.ParserRegistry
import dev.quietinbox.core.reconcile.KnownMessage
import dev.quietinbox.core.reconcile.Reconciler
import dev.quietinbox.parsers.apps.AppParsers
import dev.quietinbox.platform.media.MediaCopier
import dev.quietinbox.platform.storage.db.MaintenanceListener
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.db.VaultUnavailableException
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.repo.JournalCursor
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import dev.quietinbox.platform.storage.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory part of capture health; persisted gaps live in [HealthRepository]. */
data class CaptureStatus(
    val listenerState: ListenerState = ListenerState.NOT_GRANTED,
    val connectedSinceEpochMs: Long? = null,
    /**
     * When an event was last *accepted* — stamped inside `enqueue`, after the source filter. Not
     * "the last system callback": a callback for a disabled source, or one the bounded queue
     * dropped, never reaches it, so labelling it that way would be a claim the app cannot make.
     */
    val lastEventAtEpochMs: Long? = null,
    /**
     * When a copy was last actually written. [acceptedCount] increments right after the journal
     * write and before parsing, so it counts admitted events, not successful ones: a source whose
     * format the parser cannot read still raises it. This is stamped after `ingest.commit` returns,
     * so it is the only number on the page that means "something was saved". In memory on purpose,
     * like the rest of this type: a process restart writes a `PROCESS_RESTART` gap and resets
     * "connected since" too, so "since the listener started" is the page's existing frame.
     */
    val lastCommittedAtEpochMs: Long? = null,
    val queueDepth: Int = 0,
    val overflowCount: Long = 0,
    val acceptedCount: Long = 0,
    val droppedAfterRevoke: Long = 0,
    /** Snapshot construction failures (malformed extras); counted, never fatal. */
    val captureErrors: Long = 0,
    val vaultLocked: Boolean = false,
    val activeGeneration: String? = null,
    val pausedByUser: Boolean = false,
)

private class Queued(val captured: CapturedNotification, val generation: String)

/**
 * A notification held back until the source policy is known (QI-CAPTURE-013). Only the framework
 * object is kept — nothing is read from it — so a notification from an app the user never
 * enabled is never materialised into a snapshot. Tests enter with a pre-built snapshot instead.
 */
private class Held(
    val sbn: StatusBarNotification?,
    val captured: CapturedNotification?,
    val origin: CaptureOrigin,
    val generation: String,
    val heldAtEpochMs: Long,
) {
    val packageName: String get() = sbn?.packageName ?: captured!!.snapshot.source.packageName

    /** The framework's identity of this post (key + post time); metadata, read only for a capturable source. */
    val postId: String? get() = sbn?.let { it.key + "|" + it.postTime }
}

/**
 * Owns the capture epoch (generation token), the bounded queue and the pipeline
 * journal -> parse -> identity -> reconcile -> commit -> media. The listener service is a thin
 * shell around this singleton so process restarts and rebinds are handled in one place.
 */
@Singleton
class CaptureCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ingest: IngestRepository,
    private val sources: SourceRepository,
    private val health: HealthRepository,
    private val settings: SettingsRepository,
    private val vault: VaultRepository,
    private val mediaCopier: MediaCopier,
    private val listenerAccess: ListenerAccess,
    private val maintenance: VaultMaintenance,
) {
    /** Pipeline failures must never crash the process: they are recorded as diagnostics instead. */
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        _status.update { it.copy(listenerState = if (it.listenerState == ListenerState.CONNECTED) ListenerState.DEGRADED else it.listenerState) }
        lastError = e::class.java.simpleName
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)

    @Volatile
    var lastError: String? = null
        private set

    private val bootSessionId = UUID.randomUUID().toString()

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var snapshotFactory: SnapshotFactory = SnapshotFactory(bootSessionId)

    /** How long a cold start waits for the vault before the held buffer is dropped; a test seam. */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var coldStartTimeoutMs: Long = COLD_START_TIMEOUT_MS
    private val registry = ParserRegistry(AppParsers.all())
    private val identity = IdentityResolver()
    private val reconciler = Reconciler()
    private val queue = Channel<Queued>(capacity = Limits.MAX_QUEUE_DEPTH)

    private val _status = MutableStateFlow(CaptureStatus(listenerState = if (listenerAccess.isGranted()) ListenerState.GRANTED_DISCONNECTED else ListenerState.NOT_GRANTED))
    val status: StateFlow<CaptureStatus> = _status

    @Volatile
    private var activeGeneration: String? = null

    /** Sources the user enabled (paused ones included; see [pausedPackages]). */
    @Volatile
    private var enabledPackages: Set<String> = emptySet()

    /** Enabled sources the user paused: nothing new is accepted, accepted events wait (like a global pause). */
    @Volatile
    private var pausedPackages: Set<String> = emptySet()

    /** False until the first source list arrived; before that nothing is filtered at offer time. */
    @Volatile
    private var sourcesLoaded: Boolean = false

    @Volatile
    private var maintenanceStartedAt: Long? = null

    /** Notifications waiting for the source policy; bounded, oldest dropped first. Guarded by itself. */
    private val held = ArrayDeque<Held>()
    private var heldDropped = 0

    /** Arrival time of the first notification the buffer had to evict; the gap starts there, not at a survivor. */
    private var heldDroppedSince: Long? = null

    @Volatile
    private var coldStartJob: Job? = null

    /** A COLD_START gap row is open; while the policy stays unknown further drops extend it instead of adding rows. */
    @Volatile
    private var coldStartGapId: Long? = null

    /**
     * Start of a cold-start loss the locked vault could not record at the time. Written as a
     * bounded gap as soon as the vault can be written again (round-12 finding: a drop while the
     * vault is locked must not vanish just because the gap table was unreachable).
     */
    @Volatile
    private var coldStartLossSince: Long? = null

    /** Same for the pipeline's own lock-out gap: the time `openGap` could not be written. */
    @Volatile
    private var vaultGapSince: Long? = null

    /**
     * Same again for an event the journal would not take and whose fallback gap would not write
     * either. A failure that lasts — a full disk — hits both statements, and "less precise, never
     * absent" has to survive that, not only a single transient one (round 34 I3).
     */
    @Volatile
    private var journalLossSince: Long? = null

    /**
     * Whether a replay left a row's settlement deferred, so that the next gap write seen to
     * succeed can put it back. RAM only on purpose: the deferral itself is on disk, where every
     * later pass finds it; this is only what saves the retry from waiting for a user to change a
     * source (round 36 Codex I1).
     */
    @Volatile
    private var deferredSettlements: Boolean = false

    /** One replay pass at a time; the rest is [replayRequested]'s coalescing. */
    private val replayGate = Mutex()

    /**
     * A pass reads [replayPageSize] rows at a time and gives up after [replayRounds] of them.
     *
     * Constants in production (200 and 100). A test needs to cross the *product* of the two — that
     * is the boundary at which a failing prefix used to re-exhaust the budget on every trigger,
     * round 37 Codex I1 — and twenty thousand snapshots is not something a unit test can hold. The
     * mechanism is the same at 2 × 3, so these are settable there and nowhere else.
     */
    internal var replayPageSize: Int = 200

    internal var replayRounds: Int = 100

    @Volatile
    private var replayRequested: Boolean = false

    @Volatile
    private var paused: Boolean = false

    @Volatile
    private var listenerBound: Boolean = false

    @Volatile
    private var sessionId: Long? = null

    /**
     * Serialises live processing, journal replay and source-policy changes so one event is never
     * committed twice and no event is committed for a source the user just switched off. Shared
     * with [VaultMaintenance], which holds it for the whole of a reset or restore (QI-SEC-003).
     */
    private val pipelineMutex get() = maintenance.pipelineMutex

    /** Bitmaps waiting in the queue; bounded so a burst of BigPicture notifications cannot OOM. */
    private val queuedBitmaps = AtomicInteger(0)

    /**
     * Media copies handed to [mediaCopier] and not finished. The bitmap bound above never covered
     * a URI-only copy, so an arbitrary number of them could pile up behind the copier's two
     * permits — each one a job registered in `VaultMaintenance.workers`, which `exclusive` has to
     * join before it can start (QI-MEDIA-018).
     */
    private val queuedMediaCopies = AtomicInteger(0)

    @Volatile
    private var vaultGapOpen: Boolean = false

    init {
        // Consumer loop restarts itself after any throwable (including OOM from a huge bitmap).
        scope.launch {
            while (true) {
                try {
                    for (item in queue) process(item)
                    break
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t::class.java.simpleName
                    _status.update { it.copy(listenerState = if (it.listenerState == ListenerState.CONNECTED) ListenerState.DEGRADED else it.listenerState) }
                }
            }
        }
        scope.launch {
            // The emitted list is only a trigger: the policy is re-read from the vault under the
            // pipeline lock, so a stale emission can never overwrite a change made through
            // [changeSourcePolicy] (QI-SEC-001).
            sources.observeSources().catch { emit(emptyList()) }.collectLatest {
                pipelineMutex.withLock { guarded { loadSourcePolicy() } }
            }
        }
        // Explicit callbacks, not a StateFlow: a fast start → end pair would be conflated and the
        // start (generation rotation, gap) never seen.
        maintenance.addListener(object : MaintenanceListener {
            override suspend fun onMaintenanceStarted() = onMaintenance(true)
            override suspend fun onMaintenanceEnded() = onMaintenance(false)
        })
        scope.launch {
            vault.state.collectLatest { s ->
                _status.update { it.copy(vaultLocked = s is VaultState.Locked) }
                if (s is VaultState.Ready) {
                    if (vaultGapOpen) {
                        val now = System.currentTimeMillis()
                        val since = vaultGapSince
                        var written = false
                        guarded {
                            health.closeOpenGaps(now, GapReason.UNKNOWN)
                            // The row could not be opened while the vault was locked: recorded now, bounded.
                            if (since != null) health.recordGap(since, now, GapReason.UNKNOWN, GapPrecision.BOUNDED, now)
                            written = true
                        }
                        // Forgotten only once it is on disk: a write that fails here (the vault locked
                        // again, a reset cancelling this collector) is retried on the next Ready (round-13).
                        if (written) {
                            vaultGapOpen = false
                            vaultGapSince = null
                        }
                    }
                    replayJournal()
                }
            }
        }
    }

    // ---- listener callbacks -------------------------------------------------------------

    fun onConnected(service: NotificationListenerService) {
        val now = System.currentTimeMillis()
        val generation = UUID.randomUUID().toString()
        listenerBound = true
        activeGeneration = if (paused) null else generation
        _status.update {
            it.copy(
                listenerState = if (paused) ListenerState.PAUSED else ListenerState.CONNECTED,
                connectedSinceEpochMs = now,
                activeGeneration = if (paused) null else generation,
            )
        }
        val resync = runCatching { service.activeNotifications?.toList() }.getOrNull().orEmpty()
        // One coroutine: windows must be closed before the active-notification resync is queued.
        scope.launch {
            guarded {
                sessionId = health.startSession(generation, bootSessionId, now)
                health.closeOpenGaps(now, GapReason.LISTENER_DISCONNECTED, GapReason.NOT_GRANTED, GapReason.PROCESS_RESTART)
                ingest.closeAllWindows(now)
            }
            if (settings.current().captureActiveOnConnect) {
                for (sbn in resync) offer(sbn, CaptureOrigin.ACTIVE_RESYNC)
            }
        }
    }

    fun onDisconnected() {
        val now = System.currentTimeMillis()
        val gen = activeGeneration
        activeGeneration = null
        listenerBound = false
        _status.update { it.copy(listenerState = if (listenerAccess.isGranted()) ListenerState.RECONNECTING else ListenerState.NOT_GRANTED, activeGeneration = null) }
        // Cleared synchronously so a reconnect that starts a new session cannot be wiped by this
        // coroutine, and a failing endSession cannot leave the old id behind.
        val endedSession = sessionId
        sessionId = null
        scope.launch {
            guarded {
                endedSession?.let { health.endSession(it, now, "DISCONNECTED") }
                health.openGap(now, if (listenerAccess.isGranted()) GapReason.LISTENER_DISCONNECTED else GapReason.NOT_GRANTED, GapPrecision.BOUNDED, now)
                ingest.closeAllWindows(now)
            }
        }
        if (gen != null && listenerAccess.isGranted()) listenerAccess.requestRebind()
    }

    fun onPosted(sbn: StatusBarNotification) = offer(sbn, CaptureOrigin.LIVE)

    fun onRemoved(sbn: StatusBarNotification, reason: Int) {
        val now = System.currentTimeMillis()
        if (!isCapturable(sbn)) return
        scope.launch {
            guarded {
                val streamKey = identity.streamKey(SourceScope(sbn.packageName, "user:${sbn.user.hashCode()}", null), sbn.tag?.take(Limits.MAX_KEY_CHARS), sbn.id)
                ingest.closeWindow(streamKey, now)
                if (reason == REASON_LOCKDOWN) ingest.diagnostic("LOCKDOWN_REMOVAL", null, sbn.packageName, now)
            }
        }
    }

    // ---- user controls ------------------------------------------------------------------

    fun setPaused(value: Boolean) {
        paused = value
        val now = System.currentTimeMillis()
        // Commit fence: pausing rotates the generation so anything already queued is discarded;
        // resuming while still bound starts a fresh generation (and a matching capture session).
        val resumedGeneration = if (!value && listenerBound) UUID.randomUUID().toString() else null
        activeGeneration = if (value) null else resumedGeneration
        scope.launch {
            guarded {
                if (value) {
                    sessionId?.let { health.endSession(it, now, "PAUSED") }
                    sessionId = null
                } else if (resumedGeneration != null) {
                    sessionId = health.startSession(resumedGeneration, bootSessionId, now)
                }
            }
        }
        _status.update {
            it.copy(
                pausedByUser = value,
                listenerState = when {
                    !listenerAccess.isGranted() -> ListenerState.NOT_GRANTED
                    value -> ListenerState.PAUSED
                    activeGeneration != null -> ListenerState.CONNECTED
                    else -> ListenerState.GRANTED_DISCONNECTED
                },
                activeGeneration = activeGeneration,
            )
        }
        scope.launch {
            guarded {
                if (value) health.openGap(now, GapReason.PAUSED_BY_USER, GapPrecision.EXACT, now) else health.closeOpenGaps(now, GapReason.PAUSED_BY_USER)
            }
            // Accepted events waited out the pause in the journal; they are committed now.
            if (!value) replayJournal()
        }
    }

    fun refreshPermissionState() {
        _status.update {
            it.copy(
                listenerState = when {
                    !listenerAccess.isGranted() -> ListenerState.NOT_GRANTED
                    paused -> ListenerState.PAUSED
                    activeGeneration != null -> ListenerState.CONNECTED
                    else -> ListenerState.GRANTED_DISCONNECTED
                },
            )
        }
        if (listenerAccess.isGranted() && activeGeneration == null) listenerAccess.requestRebind()
    }

    /** Called from a synthetic publisher so a test notification is accepted even if unlisted. */
    fun isCapturable(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        if (pkg == context.packageName) {
            return sbn.notification.extras?.getBoolean(SyntheticNotifications.EXTRA_SYNTHETIC, false) == true
        }
        return pkg in enabledPackages && pkg !in pausedPackages
    }

    // ---- source policy (QI-SEC-001) ----------------------------------------------------------

    /**
     * Every change to which sources are captured goes through here: the vault write and the
     * in-memory policy update happen under the pipeline lock, so an event waiting for the lock
     * sees the new policy in its second fence, never the old one.
     */
    /**
     * Closes the source gaps the policy contradicts. Must be called under [pipelineMutex], with
     * [configured] the source table as it now stands.
     *
     * The flag and its gap are written in one transaction now, so this cannot fire for anything
     * this version wrote. It exists for the rows earlier versions left behind — a gap opened after
     * its setting had already committed, then a process death — and for a source removed while a
     * gap of its own was open. The policy is the truth: it is what capture actually does, and an
     * interval that says otherwise reads on the health page as capture still being missing.
     *
     * Each reason is answered by its own setting, read off the configuration rather than off
     * [pausedPackages] and [enabledPackages]. Those two are the allow-lists the pipeline consults,
     * and `pausedPackages` deliberately holds only *enabled* sources — so asking it whether an app
     * is paused says "no" for one that is paused and also disabled. Round 34 found both halves of
     * that: pausing a disabled source had its gap closed by the very load that followed, and
     * pause → disable → re-enable ended with a source that was still paused and had nothing open
     * to say capture had stopped.
     */
    private suspend fun reconcileSourceGaps(configured: List<SourceConfiguration>) {
        val now = System.currentTimeMillis()
        val byPackage = configured.associateBy { it.packageName }
        guarded {
            for (gap in health.openSourceGaps()) {
                val pkg = gap.packageName ?: continue
                val source = byPackage[pkg]
                val contradicted = when {
                    // The source is gone. Nothing can ever close this, so nothing ever would.
                    source == null -> true
                    gap.reason == GapReason.SOURCE_DISABLED_BY_USER.name -> source.enabled
                    gap.reason == GapReason.SOURCE_PAUSED_BY_USER.name -> !source.paused
                    else -> false
                }
                if (contradicted) health.closeGap(gap.id, now)
            }
        }
    }

    private suspend fun changeSourcePolicy(block: suspend () -> Unit) {
        pipelineMutex.withLock {
            block()
            loadSourcePolicy()
        }
    }

    /**
     * Must be called under [pipelineMutex]. Once the policy is known, held notifications are
     * decided — *before* the flag flips, so a notification arriving during the release still goes
     * through the buffer and cannot overtake the ones held before it (CI caught the reordering).
     */
    private suspend fun loadSourcePolicy() {
        val list = sources.sources()
        enabledPackages = list.filter { it.enabled }.map { it.packageName }.toSet()
        pausedPackages = list.filter { it.enabled && it.paused }.map { it.packageName }.toSet()
        reconcileSourceGaps(list)
        settleColdStartGap()
        releaseHeld()
        sourcesLoaded = true
        // Held in the instant before the flag flipped: released now, still in arrival order.
        if (synchronized(held) { held.isNotEmpty() }) releaseHeld()
        // A cold-start row that landed between the settle above and the flag flip: `dropHeld`
        // writes the id then reads the flag, this reads the id after writing the flag, so one of
        // the two sees the other (both volatile) and closes the row; closing twice is idempotent.
        val late = coldStartGapId
        if (late != null) guarded {
            health.closeOpenGaps(System.currentTimeMillis(), GapReason.COLD_START)
            coldStartGapId = null
        }
    }

    /**
     * The policy is known and the vault writable: close the lock-out's open COLD_START row (by
     * reason, idempotent — after a restore the row survived, after a reset it is gone) and write
     * the loss the locked vault could not record at the time. Must run under [pipelineMutex].
     */
    private suspend fun settleColdStartGap() {
        val now = System.currentTimeMillis()
        coldStartGapId = null
        val since = coldStartLossSince
        var written = false
        guarded {
            health.closeOpenGaps(now, GapReason.COLD_START)
            if (since != null) health.recordGap(since, now, GapReason.COLD_START, GapPrecision.BOUNDED, now)
            written = true
        }
        // Forgotten only once the loss is on disk; a write that fails (the vault locked again, a
        // reset in between) leaves it for the next policy load (round-13 finding).
        if (written && since != null) coldStartLossSince = null
        settleUnrecordedJournalLoss(now)
    }

    /**
     * Writes the loss of an event that neither the journal nor its fallback gap could record, once
     * the vault will take it. Bounded by the time it happened and the time it was written, and
     * forgotten only then — the same rule as the two losses above (round 34 I3).
     *
     * Called from the policy load and from the first event accepted after the failure, because
     * those are the two moments something has just proved the vault is writable again; the second
     * matters most, since a device that recovers its disk space may see no policy change for days
     * (round 35 agy I1).
     */
    private suspend fun settleUnrecordedJournalLoss(now: Long) {
        val since = journalLossSince ?: return
        var written = false
        guarded {
            health.recordGap(since, now, GapReason.UNKNOWN, GapPrecision.BOUNDED, now)
            written = true
        }
        if (written) {
            journalLossSince = null
            retryDeferredSettlements()
        }
    }

    /**
     * A gap write has just succeeded, so rows whose settlement was deferred may try again.
     *
     * The trigger is proof rather than a timer: a deferral means the gap table refused the write,
     * and the one thing worth waiting for is another write it did take. That no storm can follow
     * rests on one premise, so it is worth stating: the gap an acceptance writes is in the
     * acceptance's own transaction, so a gap table that is still refusing cannot accept a lossy
     * event at all — the event is rejected and nothing is armed. A partially broken table that
     * takes some writes and not others would arm retries at the rate of the writes it takes, which
     * is bounded by the event rate and not by this code (round 37 Codex M1). A timer would retry into a
     * vault still refusing everything, and the retry itself is what re-defers the row. At most one
     * replay per deferral — the flag is cleared before the launch and set again only by a pass that
     * defers something — so a vault that keeps refusing cannot turn a stream of events into a
     * stream of replays. Launched rather than awaited because the caller holds `pipelineMutex`,
     * which the replay takes once per event.
     *
     * The read and the clear are two statements, not one, and that is safe here rather than by
     * construction: all three accesses to the flag — both writes and this read — happen under
     * `pipelineMutex`. A lost update would cost a retry that the next lifecycle trigger makes
     * anyway, never a lost row; the deferral itself is on disk (round 37 subagent, Minor).
     */
    private fun retryDeferredSettlements() {
        if (!deferredSettlements) return
        deferredSettlements = false
        scope.launch { replayJournal() }
    }

    // ---- cold start (QI-CAPTURE-013) ---------------------------------------------------------

    /**
     * Before the source list is known nothing is read from a third-party notification: the
     * framework object waits in a small bounded buffer. The policy is loaded right away (which
     * waits for the vault); once known, held notifications from enabled sources are snapshotted
     * and queued, all others are dropped unread. If the vault does not open in time the buffer is
     * dropped and the window is recorded as a bounded gap — fail closed, never fail open.
     */
    private fun hold(item: Held) {
        // The job check and the launch sit inside the same lock as the buffer, so two callback
        // threads cannot start two cold-start jobs and leave an item between them (round-11).
        synchronized(held) {
            if (held.size >= MAX_HELD) {
                val evicted = held.removeFirst()
                heldDropped++
                if (heldDroppedSince == null) heldDroppedSince = evicted.heldAtEpochMs
            }
            held += item
            if (coldStartJob?.isActive != true) coldStartJob = scope.launch { coldStart() }
        }
    }

    private suspend fun coldStart() {
        val loaded = withTimeoutOrNull(coldStartTimeoutMs) {
            pipelineMutex.withLock {
                // Policy already known (another path loaded it while we were held): decide now,
                // otherwise the buffered items would sit there for good (round-11 finding).
                if (!sourcesLoaded) guarded { loadSourcePolicy() } else releaseHeld()
                sourcesLoaded
            }
        } == true
        if (!loaded) {
            dropHeld(System.currentTimeMillis())
            return
        }
        // Anything held in the instant between the release above and this job ending is released too.
        if (synchronized(held) { held.isNotEmpty() }) pipelineMutex.withLock { releaseHeld() }
    }

    /**
     * Decides every held notification against the now-known policy. Safe under the pipeline lock.
     * Notifications the bounded buffer had to drop before the policy was known are recorded as a
     * bounded gap: a dropped notification is never hidden (round-11 finding). The held objects are
     * the framework's own (`onConnected`'s resync list holds the same ones), so the bitmap bound
     * of the queue does not apply until they are snapshotted here.
     */
    private fun releaseHeld() {
        val (items, dropped, since) = synchronized(held) {
            Triple(held.toList().also { held.clear() }, heldDropped.also { heldDropped = 0 }, heldDroppedSince.also { heldDroppedSince = null })
        }
        if (dropped > 0) {
            // The evicted ones arrived before every survivor: the gap starts at the first eviction.
            recordColdStartLoss(since ?: items.minOfOrNull { it.heldAtEpochMs })
        }
        // Each item is decided against the generation and pause of *its* moment: one that a
        // disconnect or pause overtakes mid-loop is stale and gets its gap below, one queued
        // before that is fenced by the consumer like any other queued event.
        val stale = ArrayList<Held>()
        // The posts actually queued in this release (framework key + post time, metadata like
        // the package name, read only for a capturable source): a stale copy of one of them was
        // offered again by the reconnect's resync, so it is no loss. Built from what was queued,
        // never predicted up front, so an item can never be suppressed by itself (round-15/16).
        val queuedPosts = HashSet<String>()
        for (h in items) {
            if (h.generation != activeGeneration || paused) {
                stale += h
                continue
            }
            val pkg = h.packageName
            if (!(pkg in enabledPackages && pkg !in pausedPackages)) continue
            // Observed when it arrived, not when the policy finally let it through.
            val captured = h.captured ?: runCatching { snapshotFactory.create(h.sbn!!, h.origin, h.generation, h.heldAtEpochMs) }
                .onFailure {
                    _status.update { it.copy(captureErrors = it.captureErrors + 1) }
                    lastError = it::class.java.simpleName
                }
                .getOrNull() ?: continue
            if (enqueue(captured, h.generation, h.heldAtEpochMs)) h.postId?.let { queuedPosts += it }
        }
        // Held under an older generation, or while paused: a disconnect, pause or maintenance
        // happened meanwhile. Its gap starts at that event, later than this arrival, so a
        // capturable source's held window is recorded on its own (round-13 finding); an overflow
        // gap above already starts before every survivor.
        if (dropped == 0) {
            var staleSince: Long? = null
            for (h in stale) {
                val pkg = h.packageName
                if (!(pkg in enabledPackages && pkg !in pausedPackages)) continue
                if (h.postId in queuedPosts) continue
                staleSince = minOf(staleSince ?: h.heldAtEpochMs, h.heldAtEpochMs)
            }
            if (staleSince != null) recordColdStartLoss(staleSince)
        }
    }

    /**
     * Writes a bounded COLD_START gap off the pipeline; a write that fails keeps the loss in
     * [coldStartLossSince] so the next policy load writes it — a loss is only forgotten once it
     * is on disk (round-14 finding).
     */
    private fun recordColdStartLoss(start: Long?) {
        val now = System.currentTimeMillis()
        scope.launch {
            var written = false
            guarded {
                health.recordGap(start, now, GapReason.COLD_START, GapPrecision.BOUNDED, now)
                written = true
            }
            if (!written && coldStartLossSince == null) coldStartLossSince = start ?: now
        }
    }

    /**
     * Drops what is held and records the loss as one open COLD_START gap per lock-out: while the
     * vault stays locked every new notification would otherwise add a row of its own (round-11
     * finding). The gap is closed when the policy finally loads.
     */
    private suspend fun dropHeld(now: Long) {
        val (items, dropped, since) = synchronized(held) {
            Triple(held.toList().also { held.clear() }, heldDropped.also { heldDropped = 0 }, heldDroppedSince.also { heldDroppedSince = null })
        }
        if (items.isEmpty() && dropped == 0) return
        if (coldStartGapId != null) return
        val start = since ?: items.minOfOrNull { it.heldAtEpochMs }
        var written = false
        guarded {
            coldStartGapId = health.openGap(start, GapReason.COLD_START, GapPrecision.BOUNDED, now)
            written = true
        }
        // A locked vault cannot take the row: remember the loss, it is written once the vault opens.
        if (!written && coldStartLossSince == null) coldStartLossSince = start ?: now
        // `openGap` waited for the vault to open; if the policy loaded meanwhile, its settle ran
        // before this row existed, so the row is closed here and not at the next policy load (round-13).
        if (written && sourcesLoaded) guarded {
            health.closeOpenGaps(System.currentTimeMillis(), GapReason.COLD_START)
            coldStartGapId = null
        }
    }

    suspend fun addSource(packageName: String, displayName: String, adapterId: String?, now: Long) =
        changeSourcePolicy { sources.enable(packageName, displayName, adapterId, now) }

    /**
     * Disabling also discards the source's pending journal rows: nothing captured for it may land
     * later. It opens a gap of its own, too. Events dropped for a disabled source used to land in
     * `droppedAfterRevoke`, one counter that also holds a revoked permission, a rotated generation
     * and a maintenance run — so the one cause the user chose looked identical to three they did
     * not, and nothing recorded the window at all (QI-CAPTURE-018).
     */
    suspend fun setSourceEnabled(packageName: String, enabled: Boolean) {
        val now = System.currentTimeMillis()
        changeSourcePolicy {
            // The flag and the gap go in one transaction, under this lock. Written after the lock
            // instead, as they were, two rapid flips could commit their settings in one order and
            // their gaps in the other, leaving the source enabled with an open "disabled" gap; a
            // process death between the halves left the same contradiction (round 33).
            sources.setEnabled(packageName, enabled) {
                if (enabled) {
                    health.closeOpenGapsForSource(now, packageName, GapReason.SOURCE_DISABLED_BY_USER)
                } else {
                    health.openGap(now, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, now, packageName)
                    // Both in this transaction, and in this order: the discard clears the payloads,
                    // so whatever those events arrived already shortened by is settled while it can
                    // still be read. Discarding first, or afterwards as a second write, loses it.
                    settleCarriedOverLosses(packageName)
                    ingest.discardPendingJournal(packageName)
                }
            }
        }
    }

    suspend fun setSourcePaused(packageName: String, paused: Boolean) {
        val now = System.currentTimeMillis()
        var changed = false
        changeSourcePolicy {
            changed = sources.setPaused(packageName, paused) {
                if (paused) {
                    health.openGap(now, GapReason.SOURCE_PAUSED_BY_USER, GapPrecision.EXACT, now, packageName)
                } else {
                    health.closeOpenGapsForSource(now, packageName, GapReason.SOURCE_PAUSED_BY_USER)
                }
            }
        }
        if (changed && !paused) scope.launch { replayJournal() }
    }

    /**
     * [SourceRepository.remove] discards the pending journal and, with [deleteData], the whole
     * deletion graph. Its open gaps are closed in the same transaction: once the source is gone
     * nothing can ever close them, and re-adding it goes through `addSource`, which opens nothing.
     */
    suspend fun removeSource(packageName: String, deleteData: Boolean) {
        val now = System.currentTimeMillis()
        changeSourcePolicy {
            sources.remove(packageName, deleteData) {
                // Runs before `remove` discards this source's pending rows, in its transaction.
                settleCarriedOverLosses(packageName)
                health.closeOpenGapsForSource(
                    now,
                    packageName,
                    GapReason.SOURCE_DISABLED_BY_USER,
                    GapReason.SOURCE_PAUSED_BY_USER,
                )
                // "Remove and delete this source's data" is the user asking for the app to be
                // forgotten. The intervals stay — they are the honest record that capture stopped,
                // and deleting them would hide a loss the user was already shown — but they stop
                // naming it.
                if (deleteData) health.forgetGapSource(packageName)
            }
        }
    }

    // ---- maintenance (QI-SEC-003) -----------------------------------------------------------

    /**
     * A reset or restore is starting or finishing. Starting rotates the generation (everything
     * queued is dropped, nothing new is queued) and ends the capture session; finishing starts a
     * fresh generation and session, records the window as an exact gap and replays whatever the
     * journal still holds. Events already inside the pipeline lock are handled by the second
     * fence, which re-reads [VaultMaintenance.isActive].
     */
    private suspend fun onMaintenance(active: Boolean) {
        val now = System.currentTimeMillis()
        if (active) {
            if (maintenanceStartedAt != null) return
            maintenanceStartedAt = now
            activeGeneration = null
            // The vault may be about to disappear: a cold-start gap that was open belongs to it.
            coldStartGapId = null
            val ended = sessionId
            sessionId = null
            // The listener state is left alone: DEGRADED reads as "queue overflow" on the health
            // page, and a reset or restore is neither a failure nor a disconnect.
            _status.update { it.copy(activeGeneration = null) }
            scope.launch { guarded { ended?.let { health.endSession(it, now, "MAINTENANCE") } } }
        } else {
            val startedAt = maintenanceStartedAt ?: return
            maintenanceStartedAt = null
            // The vault may be brand new: the policy is reloaded before the next event is admitted.
            sourcesLoaded = false
            val resumed = if (listenerBound && !paused) UUID.randomUUID().toString() else null
            activeGeneration = resumed
            _status.update {
                it.copy(
                    activeGeneration = resumed,
                    listenerState = when {
                        !listenerAccess.isGranted() -> ListenerState.NOT_GRANTED
                        paused -> ListenerState.PAUSED
                        resumed != null -> ListenerState.CONNECTED
                        else -> ListenerState.GRANTED_DISCONNECTED
                    },
                )
            }
            // Bookkeeping and replay are launched, not awaited: this runs inside the maintenance
            // caller, which must not be held hostage by a vault that is slow (or failing) to reopen.
            scope.launch {
                guarded {
                    if (resumed != null) sessionId = health.startSession(resumed, bootSessionId, now)
                    health.recordGap(startedAt, now, GapReason.MAINTENANCE, GapPrecision.EXACT, now)
                }
                replayJournal()
            }
        }
    }

    // ---- pipeline -----------------------------------------------------------------------

    private fun offer(sbn: StatusBarNotification, origin: CaptureOrigin) {
        val gen = activeGeneration ?: return
        if (paused) return
        val now = System.currentTimeMillis()
        // Own package: only marked synthetic notifications. Other packages: filtered here once the
        // source list is known; before that the framework object is held unread (QI-CAPTURE-013).
        if (sbn.packageName == context.packageName) {
            if (!isCapturable(sbn)) return
        } else if (!sourcesLoaded) {
            hold(Held(sbn, null, origin, gen, now))
            return
        } else if (!isCapturable(sbn)) {
            return
        }
        val captured = runCatching { snapshotFactory.create(sbn, if (sbn.packageName == context.packageName) CaptureOrigin.SYNTHETIC else origin, gen, now) }
            .getOrElse {
                _status.update { it.copy(captureErrors = it.captureErrors + 1) }
                lastError = it::class.java.simpleName
                return
            }
        enqueue(captured, gen, now)
    }

    /**
     * Test seam. A `StatusBarNotification` cannot be built outside the framework, so JVM tests
     * enter the pipeline with an already-built snapshot. The admission rules are the ones [offer]
     * applies, restated against the snapshot: own-package events must be synthetic, and other
     * packages are filtered here only once the source list is known.
     */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun offerCaptured(captured: CapturedNotification) {
        val gen = activeGeneration ?: return
        if (paused) return
        val pkg = captured.snapshot.source.packageName
        val now = System.currentTimeMillis()
        if (pkg == context.packageName) {
            if (captured.snapshot.origin != CaptureOrigin.SYNTHETIC) return
        } else if (!sourcesLoaded) {
            hold(Held(null, captured, captured.snapshot.origin, gen, now))
            return
        } else if (!(pkg in enabledPackages && pkg !in pausedPackages)) {
            return
        }
        enqueue(captured, gen, now)
    }

    /** Queues the event; false when the bounded queue is full (recorded as an exact QUEUE_OVERFLOW gap). */
    private fun enqueue(built: CapturedNotification, gen: String, now: Long): Boolean {
        var captured = built
        if (captured.bitmap != null) {
            // Keep at most a few bitmaps in flight; later ones fall back to a placeholder state.
            if (queuedBitmaps.incrementAndGet() > MAX_QUEUED_BITMAPS) {
                queuedBitmaps.decrementAndGet()
                captured = CapturedNotification(captured.snapshot, null)
            }
        }
        val ok = queue.trySend(Queued(captured, gen)).isSuccess
        if (!ok && captured.bitmap != null) queuedBitmaps.decrementAndGet()
        _status.update {
            if (ok) {
                it.copy(queueDepth = it.queueDepth + 1, lastEventAtEpochMs = now)
            } else {
                it.copy(overflowCount = it.overflowCount + 1, listenerState = ListenerState.DEGRADED)
            }
        }
        if (!ok) scope.launch { guarded { health.recordGap(now, now, GapReason.QUEUE_OVERFLOW, GapPrecision.EXACT, now, captured.snapshot.source.packageName) } }
        return ok
    }

    /**
     * Admission fence, evaluated twice: once before waiting for the pipeline lock (cheap drop)
     * and once more inside it (QI-SEC-001), because a pause, a maintenance run, a revoke or a
     * source switched off while the event waited must win. Synthetic notifications keep the
     * own-package exception. Before the source list is known nothing is filtered here; the
     * in-lock reload makes the second fence real.
     */
    private fun admitted(item: Queued): Boolean {
        val snapshot = item.captured.snapshot
        if (paused || maintenance.isActive || item.generation != activeGeneration) return false
        if (snapshot.origin == CaptureOrigin.SYNTHETIC || !sourcesLoaded) return true
        val pkg = snapshot.source.packageName
        return pkg in enabledPackages && pkg !in pausedPackages
    }

    private suspend fun process(item: Queued) {
        _status.update { it.copy(queueDepth = (it.queueDepth - 1).coerceAtLeast(0)) }
        val snapshot = item.captured.snapshot
        // The bitmap is counted until the media copy has finished with it, not until it left the queue.
        var bitmapHandedOver = false
        var journaled = false
        try {
            if (!admitted(item)) {
                _status.update { it.copy(droppedAfterRevoke = it.droppedAfterRevoke + 1) }
                return
            }
            pipelineMutex.withLock {
                try {
                    if (!sourcesLoaded) loadSourcePolicy()
                    if (!admitted(item)) {
                        _status.update { it.copy(droppedAfterRevoke = it.droppedAfterRevoke + 1) }
                        return
                    }
                    val ttl = settings.current().journalTtlHours * 60L * 60L * 1000L
                    // A batch that arrived already shortened lost whole messages before the parser
                    // ever ran, so the loss belongs to the event, not to what is later made of it.
                    // It is written in the acceptance transaction: once the event is durable the gap
                    // is durable with it, and every way the event can end afterwards — committed,
                    // skipped, discarded because the source was disabled, or left pending by a pause
                    // and then discarded — keeps the record. Round 33 found each of those paths
                    // losing it when the gap was written after the commit fence instead.
                    val lossOnAccept: (suspend () -> Unit)? =
                        if (snapshot.shape.truncated.any { it in DROPPED_MESSAGES }) {
                            {
                                health.recordGap(
                                    snapshot.postedAtEpochMs ?: snapshot.observedAtEpochMs,
                                    snapshot.observedAtEpochMs,
                                    GapReason.MESSAGES_DROPPED,
                                    GapPrecision.BOUNDED,
                                    snapshot.observedAtEpochMs,
                                    snapshot.source.packageName,
                                )
                            }
                        } else {
                            null
                        }
                    if (!ingest.journal(snapshot, item.generation, ttl, lossOnAccept)) return
                    journaled = true
                    // The vault just took a write, which is the signal a remembered loss was
                    // waiting for. Hanging it on the policy load alone left it in memory for as
                    // long as the user changed no source — capture working normally the whole
                    // time — and a process death in that window lost it for good (round 35 agy I1).
                    settleUnrecordedJournalLoss(System.currentTimeMillis())
                    // The acceptance transaction wrote a gap, which is the proof a deferred
                    // settlement is waiting for. An acceptance without one proves only that the
                    // journal takes writes, and the table that refused is the gap table.
                    if (lossOnAccept != null) retryDeferredSettlements()
                    _status.update { it.copy(acceptedCount = it.acceptedCount + 1) }
                    bitmapHandedOver = processJournaled(snapshot, item.generation, item.captured.bitmap)
                } catch (e: VaultUnavailableException) {
                    // The vault went away before the commit (an event journaled first is replayed later;
                    // one not journaled is lost): record an observable gap once per lock-out.
                    if (!vaultGapOpen) {
                        vaultGapOpen = true
                        var written = false
                        guarded {
                            health.openGap(snapshot.observedAtEpochMs, GapReason.UNKNOWN, GapPrecision.BOUNDED, snapshot.observedAtEpochMs)
                            written = true
                        }
                        // The gap table is behind the same lock: remembered, written when the vault opens.
                        if (!written) vaultGapSince = snapshot.observedAtEpochMs
                    }
                    // Published last: whoever reacts to the lock-out (the vault collector on the next
                    // Ready, a test synchronising on it) must find the gap already open, or a vault that
                    // reopens at once closes nothing and the lock-out leaves no record (round 39, agy M1:
                    // the one-in-six flake of the lock-out test was this order, not the waiting gate).
                    _status.update { it.copy(vaultLocked = true, listenerState = ListenerState.DEGRADED) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (journaled) {
                        guarded { ingest.markJournalRetryable(snapshot.eventId, e::class.java.simpleName, commitFailureLoss(snapshot)) }
                    } else {
                        // The journal insert itself failed (e.g. the vault was busy): there is no row to
                        // retry, so the loss is recorded as a gap instead of vanishing (round-11 finding).
                        lastError = e::class.java.simpleName
                        var recorded = false
                        guarded {
                            ingest.diagnostic("JOURNAL_FAILED", e::class.java.simpleName, snapshot.source.packageName, snapshot.observedAtEpochMs)
                            health.recordGap(snapshot.observedAtEpochMs, snapshot.observedAtEpochMs, GapReason.UNKNOWN, GapPrecision.EXACT, snapshot.observedAtEpochMs, snapshot.source.packageName)
                            recorded = true
                        }
                        // Whatever stopped the acceptance write does not stop at one statement, so
                        // this fallback can fail too — and then nothing anywhere says the event
                        // existed. Kept until a later policy load can write it, the same contract
                        // the cold-start and lock-out losses already have (round 34 I3).
                        if (!recorded && journalLossSince == null) journalLossSince = snapshot.observedAtEpochMs
                    }
                }
            }
        } finally {
            if (item.captured.bitmap != null && !bitmapHandedOver) queuedBitmaps.decrementAndGet()
        }
    }

    /**
     * True when the event must not be committed now. A source disabled or removed since the
     * event was accepted discards it for good; a pause (global or per source) or a maintenance
     * run leaves it PENDING for the next replay. Synthetic notifications only honour the pause.
     */
    private suspend fun commitFenced(snapshot: NotificationSnapshot): Boolean {
        val pkg = snapshot.source.packageName
        if (snapshot.origin != CaptureOrigin.SYNTHETIC) {
            if (pkg !in enabledPackages) {
                ingest.markJournal(snapshot.eventId, "DISCARDED", "SOURCE_DISABLED")
                return true
            }
            if (pkg in pausedPackages) return true
        }
        return paused || maintenance.isActive
    }

    /**
     * Parses, reconciles and commits one journaled event. Must run under [pipelineMutex].
     * Returns true when the bitmap was handed to the media copier (which then owns its count).
     *
     * Commit fence (QI-SEC-001): a source that was disabled or removed since the event was
     * accepted discards it for good; a pause (global or per source) or a maintenance run leaves
     * it PENDING for the next replay.
     *
     * A loss the event arrived with — messages dropped before the parser ran — is already durable
     * by the time this runs: it was written in the acceptance transaction, so the fence discarding
     * or deferring the event cannot take the record with it.
     */
    private suspend fun processJournaled(snapshot: NotificationSnapshot, generation: String, bitmap: Bitmap?): Boolean {
        val now = snapshot.observedAtEpochMs
        if (commitFenced(snapshot)) return false
        val parser = registry.parserFor(snapshot)
        val batch = try {
            parser.parse(snapshot)
        } catch (e: Exception) {
            ingest.markJournal(snapshot.eventId, "FAILED", "PARSE_${e::class.java.simpleName}")
            ingest.diagnostic("PARSE_EXCEPTION", "${parser.id}@${parser.version}:${e::class.java.simpleName}", snapshot.source.packageName, now)
            return false
        }
        if (batch.messages.isEmpty() && batch.summary == null) {
            ingest.markJournal(snapshot.eventId, "SKIPPED", batch.contentStatus.name)
            ingest.diagnostic("SKIPPED_${batch.contentStatus.name}", "${parser.id}@${parser.version}", snapshot.source.packageName, now)
            return false
        }
        val id = if (batch.messages.isNotEmpty()) identity.resolve(snapshot, batch) else null
        val reconcile = id?.let { ident ->
            val previous = ingest.checkpoint(ident.streamKey)
            val conversationId = ingest.findConversationId(ident)
            val known = HashMap<String, KnownMessage?>()
            for (c in batch.messages) {
                val sid = c.sourceMessageId ?: continue
                if (sid !in known) known[sid] = ingest.lookupById(conversationId, sid)
            }
            reconciler.reconcile(snapshot.notificationKey, batch.messages, previous, { known[it] }, snapshot.postedAtEpochMs)
        }
        val source = sources.get(snapshot.source.packageName)
        val appSettings = settings.current()
        val retentionDays = source?.retentionDays ?: appSettings.retentionDays
        // Re-checked right before the write: parsing and the id lookups above took time, and a
        // pause that landed meanwhile must still win (the row stays PENDING for the replay).
        if (commitFenced(snapshot)) return false
        // A loss the parser proved — a whole row cut away from a group body on a line separator —
        // is recorded in the commit's own transaction: it exists only relative to the batch being
        // stored, and the surviving rows are all complete, so no row may carry it (round 35 Codex
        // I1). Like the acceptance loss it is per event: a repost of the same cut body is a new
        // event and records it again. A row the user's disable discards before it is ever parsed
        // drops the batch and this with it, by that same choice.
        val lossOnCommit: (suspend () -> Unit)? =
            if (batch.wholeMessagesLost) {
                {
                    health.recordGap(
                        snapshot.postedAtEpochMs ?: snapshot.observedAtEpochMs,
                        snapshot.observedAtEpochMs,
                        GapReason.MESSAGES_DROPPED,
                        GapPrecision.BOUNDED,
                        now,
                        snapshot.source.packageName,
                    )
                }
            } else {
                null
            }
        val outcome = ingest.commit(
            snapshot = snapshot,
            batch = batch,
            identity = id,
            reconcile = reconcile,
            generation = generation,
            retentionMs = retentionDays * DAY_MS,
            mediaAllowed = appSettings.mediaCopyEnabled && (source?.mediaEnabled ?: true),
            lossOnCommit = lossOnCommit,
        )
        // Only when rows were actually written. `commit` has a path that journals the event and
        // returns an empty outcome (no identity, or every decision suppressed), and `now` is the
        // event's *observed* time — a journal replay carries an old one, which would make the page
        // say a copy was saved hours ago, or walk the value backwards.
        // Every outcome that wrote a row counts, not only brand-new messages: an AMBIGUOUS_REPEAT
        // inserts a real message, and a revision replaces one and stores the body it replaced.
        if (outcome.newMessageIds.isNotEmpty() || outcome.ambiguousMessageIds.isNotEmpty() ||
            outcome.revisedMessageIds.isNotEmpty() || outcome.summaryRecorded
        ) {
            val savedAt = System.currentTimeMillis()
            _status.update { it.copy(lastCommittedAtEpochMs = maxOf(it.lastCommittedAtEpochMs ?: 0L, savedAt)) }
        }
        if (reconcile?.degraded == true) ingest.diagnostic("RECONCILE_DEGRADED", null, snapshot.source.packageName, now)
        if (batch.warnings.isNotEmpty()) ingest.diagnostic("PARSE_WARNINGS", batch.warnings.joinToString(",") { it.name }, snapshot.source.packageName, now)
        if (outcome.pendingMediaMessageIds.isNotEmpty()) {
            if (queuedMediaCopies.incrementAndGet() > MAX_QUEUED_MEDIA_COPIES) {
                queuedMediaCopies.decrementAndGet()
                // The copy never runs, so the rows are settled here rather than left showing an
                // hourglass for up to an hour until the sweep reaches them: PENDING means "a copy
                // is in flight", and none is. This runs under `pipelineMutex`, the single-writer
                // lane, so the write is legal where it stands.
                ingest.settlePendingMedia(outcome.pendingMediaMessageIds, MediaState.FAILED.name)
                ingest.diagnostic("MEDIA_QUEUE_OVERFLOW", outcome.pendingMediaMessageIds.size.toString(), snapshot.source.packageName, now)
                return false
            }
            scope.launch {
                try {
                    mediaCopier.copyPending(outcome.pendingMediaMessageIds, bitmap)
                } finally {
                    queuedMediaCopies.decrementAndGet()
                    if (bitmap != null) queuedBitmaps.decrementAndGet()
                }
            }
            return bitmap != null
        }
        return false
    }

    /**
     * Commits what the journal still holds. Runs as vault work (refused or cancelled by a
     * maintenance run) and never while capture is paused: "stop" means nothing is written, and
     * the accepted events wait for the resume (QI-SEC-001). Each event passes the commit fence
     * in [processJournaled], so a source disabled since is discarded, not replayed.
     */
    private suspend fun replayJournal() {
        // Passes are coalesced rather than run side by side. Five things start one — the vault
        // opening, a global recovery, a source being unpaused, a maintenance run ending, and a gap
        // write seen to succeed — and two of them overlapping is what makes a stale batch possible
        // at all: the page read is outside the pipeline lock, so one pass can defer a row another
        // is still holding (round 37 Codex C1). The flag is set before the gate is taken and only
        // the gate's holder clears it, so N requests during one pass are one more pass, not N.
        //
        // Every caller *waits* for the gate rather than leaving a busy one to its holder. The
        // holder can be cancelled mid-pass — a maintenance run cancels the work it is inside, a
        // vault state change cancels the collector that started it — and a request that arrived
        // meanwhile then had no one left to run it: round 38 Codex I1, reproduced against a real
        // maintenance run ending on a pass it had just cancelled. A cancelled holder releases the
        // gate on its way out; the next waiter takes it and finds the request still standing.
        // Cheap and safe to wait here: no caller holds the pipeline lock (each launches into the
        // scope), and the wait is cancellable like everything else in it.
        replayRequested = true
        replayGate.withLock {
            while (replayRequested) {
                replayRequested = false
                if (!replayPass()) {
                    // Maintenance is active and nothing ran. The request is not served, so it is
                    // not cleared: the run's end is what triggers it, and a waiter that arrives in
                    // between finds it standing rather than gone. Never clear what no pass served.
                    replayRequested = true
                    return
                }
            }
        }
    }

    /** One pass over the pending rows; false when maintenance was active and nothing ran. */
    private suspend fun replayPass(): Boolean = maintenance.work {
        withContext(Dispatchers.Default) {
            guarded {
                // Drain in batches until nothing is pending (a long lock-out can leave > 200 rows).
                var rounds = 0
                var progressed = true
                var resumed = false
                while (progressed && rounds++ < replayRounds && !paused) {
                    // Fetch and process under the pipeline mutex so a live event that was journaled
                    // but not yet committed cannot be replayed concurrently.
                    // Paused sources are excluded at the query so they cannot occupy the whole page
                    // and starve everyone else; their rows are replayed when they are unpaused.
                    val batch = ingest.pendingJournal(limit = replayPageSize, excludingPackages = pausedPackages)
                    if (batch.isEmpty()) {
                        // Everything that is not deferred has been dealt with. Only now are the
                        // deferred rows put back, and only once per pass. Doing it at the *head*
                        // of the pass put a failing prefix in front of everything on every
                        // trigger: 20,000 rows whose gap writes keep failing re-exhaust the
                        // hundred-round budget each time, and row 20,001 is never read, however
                        // many times a replay runs (round 37 Codex I1). Draining first means the
                        // rows behind them are committed before the cohort is retried.
                        if (resumed) break
                        resumed = true
                        var putBack = 0
                        guarded { putBack = ingest.resumeDeferredSettlements() }
                        if (putBack == 0) break
                        continue
                    }
                    progressed = false
                    for ((generation, snapshot) in batch) {
                        if (paused) break
                        // One event per lock acquisition so live capture is never starved; the
                        // PENDING re-check inside the lock prevents double processing.
                        pipelineMutex.withLock {
                            if (!sourcesLoaded) loadSourcePolicy()
                            if (!ingest.isJournalPending(snapshot.eventId)) {
                                progressed = true
                                return@withLock
                            }
                            val replay = snapshot.copy(origin = if (snapshot.origin == CaptureOrigin.SYNTHETIC) snapshot.origin else CaptureOrigin.REPLAY)
                            // Before the fence, so a row discarded for a disabled source keeps
                            // its record exactly as a live event's does (round 33) — and
                            // outside the try below, because this is bookkeeping and must not
                            // spend one of the event's three commit attempts. Inside it, three
                            // failed gap writes would file the row FAILED and clear its
                            // payload, losing the survivors *and* the record (round 35
                            // subagent C2). Nor may a failure fall through to the commit:
                            // storing the batch while the record of what it lost goes missing
                            // is round 33's finding. The symmetry with live capture is exact —
                            // an event whose loss cannot be written is not accepted, and a
                            // carried-over row whose loss cannot be written is not committed.
                            val settled = runCatching { recordCarriedOverLoss(replay) }
                            settled.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                            // False and failed are the same answer to the only question that
                            // matters here: is the loss on disk. A page is read outside this
                            // lock, so between that read and this claim another pass can have
                            // deferred the row — the claim then takes nothing and returns
                            // without a gap, and committing on it clears the payload that was
                            // the record (round 37 Codex C1).
                            if (settled.getOrDefault(false) != true) {
                                // The repository deferred the row, which frees its place on the
                                // next page: 200 rows that cannot settle used to sit at the head
                                // of every page and starve row 201 for ever (round 36 Codex I1).
                                // Progress is claimed only if the row really left the candidate
                                // set — a vault that refused the deferral too still holds its
                                // place, and saying otherwise would re-read the same page until
                                // the round limit.
                                if (!ingest.isReplayCandidate(snapshot.eventId)) {
                                    deferredSettlements = true
                                    progressed = true
                                }
                                return@withLock
                            }
                            try {
                                processJournaled(replay, generation, null)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                ingest.markJournalRetryable(snapshot.eventId, "REPLAY_${e::class.java.simpleName}", commitFailureLoss(replay))
                            }
                            // A row left PENDING on purpose (paused source, maintenance) must not spin the loop.
                            if (!ingest.isJournalPending(snapshot.eventId)) {
                                progressed = true
                            } else if (!ingest.isReplayCandidate(snapshot.eventId)) {
                                // Its commit attempts ran out and the record of that could not be
                                // written, so the repository parked it (issue #28). Same accounting
                                // as a deferred settlement, and the same retry: a *commit* failure
                                // waits on a *gap* write succeeding because the gap table is what
                                // refused — the commit itself is tried again when the row is back.
                                deferredSettlements = true
                                progressed = true
                            }
                        }
                    }
                }
            }
        }
    } != null

    /**
     * Settles a loss carried in from a release that recorded it nowhere, once.
     *
     * The claim and the gap are one transaction inside the repository, so the row can never be
     * marked settled without the record appearing; and because the claim is conditional, the two
     * paths that carry a readable payload — the replay, and the discard that follows disabling or
     * removing the source — cannot both record it. Whichever gets there first writes it.
     *
     * Two other exits from PENDING do not settle *this* loss: an undecodable payload has nothing to
     * settle, and a row whose commit attempts run out records a loss of its own — the whole event,
     * `COMMIT_FAILED`, written by [commitFailureLoss] in the transaction that files it (issue #28).
     * That record is not gated on this claim: a settled arrival loss says nothing about the commit.
     *
     * Returns whether the row may now go on to a terminal state — true when there was nothing to
     * settle, and when the gap is on disk. It is false when another pass deferred the row between
     * this batch being read and this claim being made: the claim then takes nothing, and the gap
     * does not exist. Committing on that is round 37's Critical, and the reason the repository
     * answers with four cases rather than a Boolean.
     */
    /**
     * The record of an accepted event being given up on: its commit failed on every attempt it
     * had, and the row is about to be filed FAILED with its payload cleared. Bounded by the
     * notification's post and the observation, scoped to the source, and written by the
     * repository *inside* the transaction that files the row — the last moment the event exists
     * (issue #28). Reason `COMMIT_FAILED`, so the health page says what actually happened rather
     * than "unknown".
     */
    private fun commitFailureLoss(snapshot: NotificationSnapshot): suspend () -> Unit = {
        health.recordGap(
            snapshot.postedAtEpochMs ?: snapshot.observedAtEpochMs,
            snapshot.observedAtEpochMs,
            GapReason.COMMIT_FAILED,
            GapPrecision.BOUNDED,
            snapshot.observedAtEpochMs,
            snapshot.source.packageName,
        )
    }

    private suspend fun recordCarriedOverLoss(snapshot: NotificationSnapshot): Boolean {
        if (!carriesUnrecordedLoss(snapshot.shape)) return true
        return ingest.claimEventLoss(snapshot.eventId) {
            health.recordGap(
                snapshot.postedAtEpochMs ?: snapshot.observedAtEpochMs,
                snapshot.observedAtEpochMs,
                GapReason.MESSAGES_DROPPED,
                GapPrecision.BOUNDED,
                snapshot.observedAtEpochMs,
                snapshot.source.packageName,
            )
        }.gapIsDurable
    }

    /**
     * The same, for rows about to be discarded outright because their source was disabled or
     * removed. They are never replayed, and the discard clears the payload, so this is the only
     * moment the evidence exists — which is why it runs inside the policy transaction.
     *
     * Paged, because a source paused for a long time accumulates pending rows and this holds the
     * pipeline lock while it decodes them: live capture waits for as long as it takes. That is the
     * cost of the disable the user just asked for, and it is bounded per page rather than by the
     * size of the backlog (round 35 subagent I2). The pages seek to the cursor in an index rather
     * than rescanning the source's pending rows once per page (round 36 Codex I2).
     */
    private suspend fun settleCarriedOverLosses(packageName: String) {
        // A row the replay deferred is not in the page query's set, and the discard about to run
        // would clear its payload: put it back first, or the one path that still holds the evidence
        // would be the one path that skipped it. Scoped to this source, because this runs inside
        // that source's policy transaction — a global reset here would put another app's deferred
        // rows back and then take them away again if this transaction rolled back. Inside the
        // transaction, so a settle that then fails takes the resume back with everything else.
        ingest.resumeDeferredSettlements(packageName)
        var after = JournalCursor.START
        while (true) {
            val page = ingest.pendingJournalForPackage(packageName, after)
            for (snapshot in page.snapshots) {
                // The discard this runs before clears the payload for good, so "not on disk" has
                // to abort the whole policy change rather than continue: there is no later moment
                // at which the evidence could be read again.
                check(recordCarriedOverLoss(snapshot)) {
                    "a carried-over loss for ${snapshot.eventId} is not recorded; the source may not be discarded"
                }
            }
            after = page.next ?: return
        }
    }

    /** Best-effort bookkeeping: failures are swallowed, a coroutine cancellation never is. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val REASON_LOCKDOWN = 20 // NotificationListenerService.REASON_LOCKDOWN (API 29)
        private const val MAX_QUEUED_BITMAPS = 8

        /** Truncation that means content was discarded, not merely shortened. */
        /**
         * Flags that mean content existed and was discarded before the parser saw it — a gap.
         * `LINES_DROPPED` belongs here for the same reason the other two do: an InboxStyle
         * notification with more lines than the snapshot may hold loses the oldest ones outright.
         * It is not a shortened body; each surviving line carries its own truncation separately.
         *
         * All three are recorded as `GapReason.MESSAGES_DROPPED`, so the health page says
         * "messages" for what was really a loss of lines. That is one name for two things, which
         * is the cost this release spent two rounds removing from `TruncationFlag` — recorded here
         * rather than renamed, because a `GapReason` is persisted and read by the UI in five
         * languages (round 35 subagent, Minor 3).
         */
        private val DROPPED_MESSAGES = setOf(
            TruncationFlag.MESSAGES_DROPPED,
            TruncationFlag.HISTORIC_MESSAGES_DROPPED,
            TruncationFlag.LINES_DROPPED,
        )

        /**
         * True when a payload carries a whole-content loss that the release which wrote it recorded
         * nowhere, and that the payload alone still settles.
         *
         * Releases up to 0.1.3 wrote no gap for content the framework had already dropped, and used
         * one flag for two different losses. Two shapes are nonetheless decidable:
         *
         *  - `LINES`, which those releases raised only when the line array exceeded the limit — an
         *    over-long single line was shortened silently and never raised it.
         *  - `MESSAGES` (or `HISTORIC_MESSAGES`) with no surviving message whose own text was
         *    shortened. That flag had exactly two causes; with the second ruled out by the payload,
         *    only whole messages dropped for exceeding the limit remains.
         *
         * `MESSAGES` alongside a shortened survivor stays undecidable — the batch may also have
         * been over the limit — and is deliberately left alone rather than guessed at either way.
         *
         * Nothing this release writes can match: a loss it saw became a `*_DROPPED` flag and a gap
         * at acceptance, and its `MESSAGES` is raised only by a shortened survivor. The column is
         * what makes that structural rather than a reading of the flags.
         */
        private fun carriesUnrecordedLoss(shape: NotificationShape): Boolean {
            if (shape.truncated.any { it in DROPPED_MESSAGES }) return false
            if (TruncationFlag.LINES in shape.truncated) return true
            if (TruncationFlag.MESSAGES in shape.truncated &&
                shape.messages.none { it.text?.truncated == true }
            ) {
                return true
            }
            return TruncationFlag.HISTORIC_MESSAGES in shape.truncated &&
                shape.historicMessages.none { it.text?.truncated == true }
        }

        /** In-flight media copies. Bitmaps are bounded by their bytes; URI copies by their jobs. */
        private const val MAX_QUEUED_MEDIA_COPIES = 32

        /**
         * Notifications held unread before the source policy is known; the oldest is dropped first
         * and every drop is recorded as a gap. Sized like the queue: it carries a whole
         * active-notification resync on a cold start. Memory: these are the framework's own
         * objects (a resync list holds the same ones), each possibly carrying a BigPicture bitmap;
         * the bound is on their number, not their bytes, and they are released within 15 s.
         */
        private const val MAX_HELD = 256

        /** How long a held notification waits for the vault before it is dropped and a gap recorded. */
        private const val COLD_START_TIMEOUT_MS = 15_000L
    }
}
