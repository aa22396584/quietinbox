package dev.quietinbox.platform.capture

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.quietinbox.core.model.CaptureOrigin
import dev.quietinbox.core.model.GapPrecision
import dev.quietinbox.core.model.GapReason
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.TruncationFlag
import dev.quietinbox.core.model.ListenerState
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.SourceConfiguration
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.platform.media.MediaCopier
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.db.VaultUnavailableException
import dev.quietinbox.platform.crypto.KeyFailure
import dev.quietinbox.platform.storage.db.GapIntervalEntity
import dev.quietinbox.platform.storage.repo.CommitOutcome
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.repo.JournalRetry
import dev.quietinbox.platform.storage.repo.JournalCursor
import dev.quietinbox.platform.storage.repo.LossClaim
import dev.quietinbox.platform.storage.repo.JournalPage
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import dev.quietinbox.platform.storage.settings.AppSettings
import dev.quietinbox.platform.storage.settings.SettingsRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Collections

private const val APP_PKG = "dev.quietinbox.app"
private const val ENABLED_PKG = "com.example.chat"
private const val UNLISTED_PKG = "com.example.other"
private const val PAUSED_PKG = "com.example.paused"
private const val SESSION_ID = 7L

/**
 * Pipeline behaviour of the capture singleton: the generation commit fence, the resume path and
 * cold-start source filtering.
 *
 * The coordinator owns a real `CoroutineScope` on `Dispatchers.Default`, so these tests never
 * assume an ordering the production code does not guarantee. Every hand-off is either a latch
 * inside a stubbed repository call or a poll on the observable status; the queue is drained by a
 * single consumer coroutine, so "event B was journaled" is a happens-after barrier for event A.
 */
class CaptureCoordinatorTest : FunSpec({

    /** Polls [check] until it holds; fails with the caller's assertion if the deadline passes. */
    suspend fun awaitUntil(timeoutMs: Long = 5_000, check: suspend () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                check()
                return
            } catch (e: Throwable) {
                if (e is CancellationException || System.currentTimeMillis() > deadline) throw e
                delay(10)
            }
        }
    }

    /** Asserts [check] still holds after the pipeline has had time to do something wrong. */
    suspend fun stillHolds(forMs: Long = 300, check: suspend () -> Unit) {
        check()
        delay(forMs)
        check()
    }

    fun sourceConfig(packageName: String, enabled: Boolean = true, paused: Boolean = false) =
        SourceConfiguration(
            packageName = packageName, displayName = packageName, enabled = enabled, paused = paused,
            retentionDays = null, mediaEnabled = true, addedAtEpochMs = 0L, adapterId = null,
        )

    /**
     * A snapshot with no title and no text: the standard parser produces no candidates, so the
     * pipeline stops at "journaled, then SKIPPED" and these tests never depend on parser,
     * identity or reconciler behaviour.
     */
    fun captured(eventId: String, pkg: String = ENABLED_PKG, origin: CaptureOrigin = CaptureOrigin.LIVE) =
        CapturedNotification(
            Fixtures.snapshot(
                shape = Fixtures.base(title = null, text = null),
                packageName = pkg,
                eventId = eventId,
                origin = origin,
            ),
            null,
        )

    /** Same as [captured], with the snapshot declaring what it had to cut. */
    fun capturedWithTruncation(eventId: String, truncated: Set<TruncationFlag>) =
        CapturedNotification(
            Fixtures.snapshot(
                shape = Fixtures.base(title = null, text = null).copy(truncated = truncated),
                packageName = ENABLED_PKG,
                eventId = eventId,
            ),
            null,
        )

    /**
     * Every collaborator is a relaxed mock: the coordinator runs its bookkeeping inside `guarded
     * {}`, which swallows the exception a strict mock would raise, so a missing stub would fail
     * silently. The two return values that actually steer control flow are stubbed explicitly.
     *
     * Two of the fakes below hold a guard the coordinator does not: `journal` refuses an event id
     * it has already seen, and `setEnabled` returns false when the flag already holds the value
     * asked for. That is deliberate and not a fake standing in for untested logic — those guards
     * are the repository's, they are SQL (a primary key that ignores conflicts, a conditional
     * update), and each is pinned on a real vault in `JournalLossTransactionTest` and
     * `SourcePolicyTransactionTest`. What the tests here decide is the half those cannot: that the
     * coordinator asks, and at which moment. Round 36 agy read this pair as false safety, which it
     * would be if the instrumented halves did not exist (agy I2).
     */
    class Harness {
        val context: Context = mockk(relaxed = true)
        val ingest: IngestRepository = mockk(relaxed = true)
        val sources: SourceRepository = mockk(relaxed = true)
        val health: HealthRepository = mockk(relaxed = true)
        val settings: SettingsRepository = mockk(relaxed = true)
        val vault: VaultRepository = mockk(relaxed = true)
        val mediaCopier: MediaCopier = mockk(relaxed = true)
        val listenerAccess: ListenerAccess = mockk(relaxed = true)
        val service: NotificationListenerService = mockk(relaxed = true)
        val maintenance = VaultMaintenance()

        val observedSources = MutableSharedFlow<List<SourceConfiguration>>(replay = 1)
        val vaultState = MutableStateFlow<VaultState>(VaultState.Opening)

        /** Event ids that reached [IngestRepository.journal], in the order the consumer saw them. */
        val journaled: MutableList<String> = Collections.synchronizedList(ArrayList())

        init {
            every { context.packageName } returns APP_PKG
            every { listenerAccess.isGranted() } returns true
            every { sources.observeSources() } returns observedSources
            every { vault.state } returns vaultState
            every { service.activeNotifications } returns null
            coEvery { settings.current() } returns AppSettings()
            coEvery { health.startSession(any(), any(), any()) } returns SESSION_ID
            coEvery { sources.sources() } returns listOf(
                SourceConfiguration(ENABLED_PKG, ENABLED_PKG, true, false, null, true, 0L, null),
            )
            journalAnswers { true }
            installGapStore()
            installLossClaim()
            installRetry()
        }

        /**
         * A real (if tiny) gap table, so tests about gaps can assert what the health page would
         * show rather than how many times a mock was called. Round 34 found a test asserting
         * against a fake that contained the very logic under test; state is harder to fool.
         */
        val gaps: MutableList<GapIntervalEntity> = Collections.synchronizedList(ArrayList())
        private var nextGapId = 1L

        private fun addGap(start: Long?, end: Long?, reason: GapReason, precision: GapPrecision, now: Long, pkg: String?): Long =
            synchronized(gaps) {
                val id = nextGapId++
                gaps += GapIntervalEntity(id, start, end, reason.name, precision.name, now, pkg)
                id
            }

        private fun closeWhere(end: Long?, predicate: (GapIntervalEntity) -> Boolean) = synchronized(gaps) {
            for (i in gaps.indices) {
                val g = gaps[i]
                if (g.endEpochMs == null && predicate(g)) gaps[i] = g.copy(endEpochMs = end)
            }
        }

        /** The open gaps of one source, as the health page would list them. */
        fun openGapsFor(pkg: String, reason: GapReason) =
            synchronized(gaps) { gaps.filter { it.endEpochMs == null && it.packageName == pkg && it.reason == reason.name } }

        /**
         * A gap table that refuses writes — the disk-full shape. Set it and the *real* claim fake
         * runs into a failing gap write, which is what makes the deferral its own behaviour here
         * rather than something a per-test stub of `claimEventLoss` decided.
         */
        @Volatile
        var gapWritesFail: Boolean = false

        /**
         * Makes the deferral fail as well — the vault refusing every write, not only the gap table.
         * The row then stays a replay candidate, and a pass that claimed progress anyway would
         * re-read the same page until its round limit (round 37 subagent C2-b).
         */
        @Volatile
        var deferralsFail: Boolean = false

        private fun installGapStore() {
            coEvery { health.openGap(any(), any(), any(), any(), any()) } answers {
                if (gapWritesFail) throw IllegalStateException("no space left on device")
                addGap(firstArg(), null, secondArg(), thirdArg(), arg(3), arg(4))
            }
            coEvery { health.recordGap(any(), any(), any(), any(), any(), any()) } answers {
                if (gapWritesFail) throw IllegalStateException("no space left on device")
                addGap(firstArg(), secondArg(), thirdArg(), arg(3), arg(4), arg(5))
                Unit
            }
            coEvery { health.closeOpenGapsForSource(any(), any(), *anyVararg()) } answers {
                val end = firstArg<Long?>()
                val pkg = secondArg<String>()
                val reasons = arg<Array<GapReason>>(2).map { it.name }.toSet()
                closeWhere(end) { it.packageName == pkg && it.reason in reasons }
                Unit
            }
            coEvery { health.closeOpenGaps(any(), *anyVararg()) } answers {
                val end = firstArg<Long?>()
                val reasons = arg<Array<GapReason>>(1).map { it.name }.toSet()
                closeWhere(end) { it.reason in reasons }
                Unit
            }
            coEvery { health.closeGap(any(), any()) } answers {
                val id = firstArg<Long>()
                closeWhere(secondArg()) { it.id == id }
                Unit
            }
            coEvery { health.openSourceGaps() } answers {
                synchronized(gaps) {
                    gaps.filter {
                        it.endEpochMs == null &&
                            (it.reason == GapReason.SOURCE_DISABLED_BY_USER.name || it.reason == GapReason.SOURCE_PAUSED_BY_USER.name)
                    }
                }
            }
            coEvery { health.forgetGapSource(any()) } answers {
                val pkg = firstArg<String>()
                synchronized(gaps) {
                    for (i in gaps.indices) if (gaps[i].packageName == pkg) gaps[i] = gaps[i].copy(packageName = null)
                }
                Unit
            }
        }

        /**
         * The journal's claim, behaving as the repository's conditional update does: the first
         * caller for an event id wins and writes the gap inside the claim, every later one loses,
         * and a gap write that throws leaves the claim unspent for the next pass.
         *
         * That exactly-once is SQL, and SQL is decided on a real vault in
         * `JournalLossTransactionTest`. What these tests decide is the other half: whether the
         * coordinator asks for the claim at all, and at which of the two ways out of PENDING.
         */
        val lossClaimed: MutableSet<String> = Collections.synchronizedSet(HashSet())

        private fun installLossClaim() {
            coEvery { ingest.claimEventLoss(any(), any()) } coAnswers {
                val eventId = firstArg<String>()
                // Eligibility is the SQL's: the claim takes a row only at 0. A deferred row answers
                // DEFERRED, not "already recorded" — the gap does not exist there, and a fake that
                // conflated the two is why round 37's Critical passed every test (Codex C1).
                when {
                    eventId in lossDeferred -> LossClaim.DEFERRED
                    eventId in lossClaimed -> LossClaim.ALREADY_RECORDED
                    else -> {
                        lossClaimed += eventId
                        try {
                            arg<suspend () -> Unit>(1).invoke()
                            LossClaim.RECORDED
                        } catch (e: Throwable) {
                            lossClaimed.remove(eventId)
                            // The repository's deferral, after the rollback undid the claim — and
                            // like every other write, it can fail too.
                            if (!deferralsFail) lossDeferred += eventId
                            throw e
                        }
                    }
                }
            }
            installPendingJournal()
            installPendingReplay()
        }

        /** Attempts charged per event id, as the journal's `attempts` column holds them. */
        val attempts: MutableMap<String, Int> = Collections.synchronizedMap(HashMap())

        /** Rows filed FAILED with the loss of the whole event recorded first (issue #28). */
        val exhausted: MutableSet<String> = Collections.synchronizedSet(HashSet())

        /**
         * A failed commit charged to the row, as the repository does it: within the budget the row
         * stays; at the threshold the loss callback runs first and the row is filed only if it
         * succeeded, else the row is parked — or, when the vault refuses that write too, left where
         * it was. The transaction that makes the record and the filing one thing is SQL, decided in
         * `JournalLossTransactionTest`; what these tests decide is that the coordinator hands the
         * record in at both entrances and what it does with the answer.
         */
        private fun installRetry() {
            coEvery { ingest.markJournalRetryable(any(), any(), any()) } coAnswers {
                val id = firstArg<String>()
                val loss = thirdArg<suspend () -> Unit>()
                val n = synchronized(attempts) { (attempts[id] ?: 0) + 1 }.also { attempts[id] = it }
                if (n < IngestRepository.MAX_ATTEMPTS) {
                    JournalRetry.RETRYABLE
                } else {
                    try {
                        loss()
                        attempts.remove(id)
                        exhausted += id
                        synchronized(pendingReplay) { pendingReplay.removeAll { it.second.eventId == id } }
                        JournalRetry.FAILED_RECORDED
                    } catch (e: Throwable) {
                        attempts[id] = n - 1
                        if (!deferralsFail) {
                            lossDeferred += id
                            JournalRetry.FAILED_DEFERRED
                        } else {
                            JournalRetry.RETRYABLE
                        }
                    }
                }
            }
        }

        /**
         * The rows the vault holds for the replay, and which of them a failed settlement has taken
         * out of the candidate set.
         *
         * `pendingJournal` honours the limit and skips deferred rows, as the query does, so
         * "the page moved on" is something the coordinator has to earn. A fake that returned the
         * same list every time is what made round 36's starvation invisible (Codex I1).
         */
        val pendingReplay: MutableList<Pair<String, NotificationSnapshot>> =
            Collections.synchronizedList(ArrayList())

        val lossDeferred: MutableSet<String> = Collections.synchronizedSet(HashSet())

        private fun installPendingReplay() {
            coEvery { ingest.pendingJournal(any(), any()) } answers {
                val limit = firstArg<Int>()
                val excluded = secondArg<Collection<String>>().toSet()
                synchronized(pendingReplay) {
                    pendingReplay.filter {
                        it.second.eventId !in lossDeferred && it.second.source.packageName !in excluded
                    }.take(limit)
                }
            }
            // Unmodelled rows are candidates: a fake may not report progress the code has not made.
            coEvery { ingest.isReplayCandidate(any()) } answers { firstArg<String>() !in lossDeferred }
            coEvery { ingest.resumeDeferredSettlements() } answers {
                synchronized(lossDeferred) {
                    val resumed = lossDeferred.size
                    lossDeferred.clear()
                    resumed
                }
            }
            // The per-source overload, scoped the way the query is: a policy transaction for one
            // app may not put another app's deferred rows back.
            coEvery { ingest.resumeDeferredSettlements(any<String>()) } answers {
                val pkg = firstArg<String>()
                synchronized(lossDeferred) {
                    val mine = lossDeferred.filter { id ->
                        pendingByPackage[pkg]?.any { it.eventId == id } == true ||
                            pendingReplay.any { it.second.eventId == id && it.second.source.packageName == pkg }
                    }
                    lossDeferred.removeAll(mine.toSet())
                    mine.size
                }
            }
        }

        /**
         * The pending rows the vault holds for each source. `pendingJournalForPackage` reads this
         * and `discardPendingJournal` empties it, exactly as the real pair do, so the *order* of
         * the two inside the policy transaction is something a test can decide: discarding first
         * leaves nothing to settle. Stubbing the read to a fixed list made that order untestable,
         * which is how round 35 found the commit's own headline claim uncontrolled (subagent C3).
         */
        val pendingByPackage: MutableMap<String, MutableList<NotificationSnapshot>> =
            Collections.synchronizedMap(HashMap())

        private fun installPendingJournal() {
            // Pages the way the query does — ordered by `(receivedAtEpochMs, eventId)`, seeking past
            // the cursor, `next` only when the page came back full — so the loop that drives it is
            // the thing under test. Returning the whole list with `next = null` made the caller's
            // paging unobservable, which is the shape round 34 found four times (round 36 subagent
            // C1). Whether the *query* pages correctly is decided on a real vault, in
            // `JournalLossTransactionTest`.
            coEvery { ingest.pendingJournalForPackage(any(), any(), any()) } answers {
                val pkg = firstArg<String>()
                val after = secondArg<JournalCursor>()
                val limit = thirdArg<Int>()
                val rows = synchronized(pendingByPackage) { pendingByPackage[pkg]?.toList().orEmpty() }
                    // As the query's `lossRecorded = 0` does: a deferred row is not in the walk's
                    // set either, which is why the walk resumes before it reads — the discard that
                    // follows would clear the payload it is the last reader of.
                    .filter { it.eventId !in lossDeferred }
                    .sortedWith(compareBy({ it.observedAtEpochMs }, { it.eventId }))
                    .filter {
                        it.observedAtEpochMs > after.receivedAtEpochMs ||
                            (it.observedAtEpochMs == after.receivedAtEpochMs && it.eventId > after.eventId)
                    }
                    .take(limit)
                val last = rows.lastOrNull()
                JournalPage(rows, if (rows.size == limit && last != null) JournalCursor(last.observedAtEpochMs, last.eventId) else null)
            }
            coEvery { ingest.discardPendingJournal(any()) } answers {
                val pkg = firstArg<String>()
                synchronized(pendingByPackage) { pendingByPackage.remove(pkg)?.size ?: 0 }
            }
        }

        /** Replaces the journal stub; [answer] runs on the consumer coroutine. */
        fun journalAnswers(answer: suspend (NotificationSnapshot) -> Boolean) {
            coEvery { ingest.journal(any(), any(), any(), any()) } coAnswers {
                val snapshot = firstArg<NotificationSnapshot>()
                journaled += snapshot.eventId
                val accepted = answer(snapshot)
                // The real repository runs this inside the acceptance transaction and only when the
                // insert created the row. The fake has to do the same, or a test about where a loss
                // is written would be a test about nothing.
                if (accepted) arg<(suspend () -> Unit)?>(3)?.invoke()
                accepted
            }
        }

        /** The source list the coordinator reloads under the pipeline lock; tests mutate it to flip a policy. */
        val sourceList: MutableList<SourceConfiguration> = Collections.synchronizedList(mutableListOf(sourceConfig(ENABLED_PKG)))

        init {
            coEvery { sources.sources() } answers { sourceList.toList() }
            // The real repository writes the flag and whatever records it in one transaction, and
            // only when the flag actually moves. The fakes do both: without the transition check a
            // test about "a repeated disable is not a second gap" would pass on any code at all.
            coEvery { sources.setEnabled(any(), any(), any()) } coAnswers {
                val pkg = firstArg<String>()
                val enabled = secondArg<Boolean>()
                val i = sourceList.indexOfFirst { it.packageName == pkg }
                if (i < 0 || sourceList[i].enabled == enabled) {
                    false
                } else {
                    sourceList[i] = sourceList[i].copy(enabled = enabled)
                    arg<(suspend () -> Unit)?>(2)?.invoke()
                    true
                }
            }
            coEvery { sources.setPaused(any(), any(), any()) } coAnswers {
                val pkg = firstArg<String>()
                val paused = secondArg<Boolean>()
                val i = sourceList.indexOfFirst { it.packageName == pkg }
                if (i < 0 || sourceList[i].paused == paused) {
                    false
                } else {
                    sourceList[i] = sourceList[i].copy(paused = paused)
                    arg<(suspend () -> Unit)?>(2)?.invoke()
                    true
                }
            }
            coEvery { sources.remove(any(), any(), any()) } coAnswers {
                val pkg = firstArg<String>()
                sourceList.removeAll { it.packageName == pkg }
                arg<(suspend () -> Unit)?>(2)?.invoke()
                // The repository discards this source's pending rows itself, inside the same
                // transaction and after the callback — so the callback settling first is the only
                // thing standing between a carried-over loss and a cleared payload. Leaving the
                // rows in place here made that order unobservable, and agy found the settle call
                // could be deleted outright with the suite still green (round 36 agy I1).
                synchronized(pendingByPackage) { pendingByPackage.remove(pkg) }
                Unit
            }
        }

        fun coordinator() = CaptureCoordinator(context, ingest, sources, health, settings, vault, mediaCopier, listenerAccess, maintenance)
    }

    /** Returns once the connect coroutine has finished, so `sessionId` is set. */
    suspend fun Harness.awaitConnected() = awaitUntil { coVerify { ingest.closeAllWindows(any()) } }

    test("an event queued before a pause is never committed after it") {
        val h = Harness()
        val enteredJournal = CompletableDeferred<Unit>()
        val releaseJournal = CompletableDeferred<Unit>()
        h.journalAnswers { snapshot ->
            if (snapshot.eventId == "evt-a") {
                enteredJournal.complete(Unit)
                releaseJournal.await()
            }
            true
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-a"))
        // The consumer is now parked inside journal(evt-a) and cannot reach evt-b before the pause.
        withTimeout(5_000) { enteredJournal.await() }
        coordinator.offerCaptured(captured("evt-b"))
        coordinator.setPaused(true)
        releaseJournal.complete(Unit)

        awaitUntil { coordinator.status.value.droppedAfterRevoke shouldBe 1L }
        h.journaled shouldBe listOf("evt-a")
        coordinator.status.value.acceptedCount shouldBe 1L
    }

    test("pausing rotates the generation so nothing offered afterwards is queued at all") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        coordinator.setPaused(true)

        coordinator.offerCaptured(captured("evt-while-paused"))

        stillHolds { h.journaled shouldBe emptyList() }
        coordinator.status.value.queueDepth shouldBe 0
        coordinator.status.value.droppedAfterRevoke shouldBe 0L
    }

    test("resuming while bound starts a fresh generation and a fresh capture session") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        val firstGeneration = coordinator.status.value.activeGeneration.shouldNotBeNull()
        coVerify(timeout = 5_000) { h.health.startSession(firstGeneration, any(), any()) }
        h.awaitConnected()

        coordinator.setPaused(true)
        coordinator.status.value.activeGeneration shouldBe null
        coordinator.status.value.pausedByUser shouldBe true
        coordinator.status.value.listenerState shouldBe ListenerState.PAUSED
        coVerify(timeout = 5_000) { h.health.endSession(SESSION_ID, any(), "PAUSED") }

        coordinator.setPaused(false)
        val secondGeneration = coordinator.status.value.activeGeneration.shouldNotBeNull()
        secondGeneration shouldNotBe firstGeneration
        coordinator.status.value.listenerState shouldBe ListenerState.CONNECTED
        coVerify(timeout = 5_000) { h.health.startSession(secondGeneration, any(), any()) }

        // A new event is accepted under the resumed generation.
        coordinator.offerCaptured(captured("evt-after-resume"))
        awaitUntil { h.journaled shouldBe listOf("evt-after-resume") }
    }

    test("resuming while unbound does not start a generation or a session") {
        val h = Harness()
        val coordinator = h.coordinator()
        // No onConnected: the listener was never bound, so there is nothing to resume into.
        coordinator.setPaused(true)
        coordinator.setPaused(false)

        coordinator.status.value.activeGeneration shouldBe null
        coordinator.status.value.listenerState shouldBe ListenerState.GRANTED_DISCONNECTED
        coordinator.offerCaptured(captured("evt-unbound"))
        stillHolds { h.journaled shouldBe emptyList() }
        coVerify(exactly = 0) { h.health.startSession(any(), any(), any()) }
    }

    test("cold start: the source list is loaded in the pipeline and drops what is not a source") {
        val h = Harness()
        // observeSources never emits, so sourcesLoaded stays false and nothing is filtered at
        // offer time; process() has to load the list itself and decide.
        coEvery { h.sources.sources() } returns listOf(sourceConfig(ENABLED_PKG))
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-unlisted", pkg = UNLISTED_PKG))
        coordinator.offerCaptured(captured("evt-enabled", pkg = ENABLED_PKG))

        // One consumer drains the queue in order, so seeing evt-enabled proves evt-unlisted was
        // already decided; it never reached the journal.
        awaitUntil { h.journaled shouldBe listOf("evt-enabled") }
        awaitUntil { coordinator.status.value.acceptedCount shouldBe 1L }
        coVerify(atLeast = 1) { h.sources.sources() }
    }

    test("cold start: a disabled source in the loaded list is dropped too") {
        val h = Harness()
        coEvery { h.sources.sources() } returns listOf(
            sourceConfig(UNLISTED_PKG, enabled = false),
            sourceConfig(ENABLED_PKG),
        )
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-disabled", pkg = UNLISTED_PKG))
        coordinator.offerCaptured(captured("evt-enabled", pkg = ENABLED_PKG))

        awaitUntil { h.journaled shouldBe listOf("evt-enabled") }
    }

    test("once the source list is known an unlisted package is dropped before it is queued") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        // One round trip through the pipeline: after it, the source list is loaded either way.
        coordinator.offerCaptured(captured("probe", pkg = ENABLED_PKG))
        awaitUntil { h.journaled shouldBe listOf("probe") }

        coordinator.offerCaptured(captured("evt-unlisted", pkg = UNLISTED_PKG))

        stillHolds { h.journaled shouldBe listOf("probe") }
        // Dropped at offer time, so it never entered the queue and was never counted as revoked.
        coordinator.status.value.droppedAfterRevoke shouldBe 0L
    }

    test("an own-package event is only accepted when it is marked synthetic") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-own-live", pkg = APP_PKG, origin = CaptureOrigin.LIVE))
        stillHolds { h.journaled shouldBe emptyList() }

        coordinator.offerCaptured(captured("evt-own-synthetic", pkg = APP_PKG, origin = CaptureOrigin.SYNTHETIC))
        awaitUntil { h.journaled shouldBe listOf("evt-own-synthetic") }
    }

    test("an ordinary pipeline failure after acceptance is marked retryable and the consumer keeps going") {
        val h = Harness()
        // Accepted (journaled), then the commit path fails: the row exists and is retried later.
        coEvery { h.ingest.markJournal(any(), any(), any()) } coAnswers {
            if (firstArg<String>() == "evt-boom") throw IllegalStateException("boom")
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-boom"))
        coordinator.offerCaptured(captured("evt-ok"))

        awaitUntil { h.journaled shouldBe listOf("evt-boom", "evt-ok") }
        coVerify(timeout = 5_000, exactly = 1) { h.ingest.markJournalRetryable("evt-boom", "IllegalStateException", any()) }
        awaitUntil { coordinator.status.value.acceptedCount shouldBe 2L }
    }

    test("a CancellationException is propagated, never recorded as a retryable failure") {
        val h = Harness()
        h.journalAnswers { throw CancellationException("cancelled") }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-cancelled"))
        awaitUntil { h.journaled shouldBe listOf("evt-cancelled") }

        // The consumer rethrew instead of looping, so a later event is never picked up and the
        // failure was never swallowed into the journal's retry bookkeeping.
        coordinator.offerCaptured(captured("evt-after-cancel"))
        stillHolds { h.journaled shouldBe listOf("evt-cancelled") }
        coVerify(exactly = 0) { h.ingest.markJournalRetryable(any(), any(), any()) }
    }

    test("disconnecting clears the generation, ends the session and opens a gap") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()

        coordinator.onDisconnected()

        coordinator.status.value.activeGeneration shouldBe null
        coordinator.status.value.listenerState shouldBe ListenerState.RECONNECTING
        coVerify(timeout = 5_000) { h.health.endSession(SESSION_ID, any(), "DISCONNECTED") }
        coVerify(timeout = 5_000) { h.health.openGap(any(), any(), any(), any()) }
        coVerify(timeout = 5_000) { h.listenerAccess.requestRebind() }

        coordinator.offerCaptured(captured("evt-after-disconnect"))
        stillHolds { h.journaled shouldBe emptyList() }
    }

    // ---- QI-SEC-001: policy changes are ordered against the pipeline lock -------------------

    test("a source disabled while an event waits for the pipeline lock is never journaled") {
        val h = Harness()
        val enteredJournal = CompletableDeferred<Unit>()
        val releaseJournal = CompletableDeferred<Unit>()
        h.journalAnswers { snapshot ->
            if (snapshot.eventId == "evt-a") {
                enteredJournal.complete(Unit)
                releaseJournal.await()
            }
            true
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        // One round trip so the source list is known and evt-b passes the first (cheap) fence.
        coordinator.offerCaptured(captured("probe"))
        awaitUntil { h.journaled shouldBe listOf("probe") }

        coordinator.offerCaptured(captured("evt-a"))
        withTimeout(5_000) { enteredJournal.await() }
        // evt-b passed the pre-lock fence: the source is still enabled at this point.
        coordinator.offerCaptured(captured("evt-b"))
        // The policy change queues on the pipeline lock behind evt-a and ahead of evt-b (the
        // mutex is fair). UNDISPATCHED runs it synchronously up to its first suspension — the
        // lock wait — so it is enqueued before the gate is released, with no timing assumption.
        val change = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.setSourceEnabled(ENABLED_PKG, enabled = false) }
        coVerify(exactly = 0) { h.sources.setEnabled(any(), any()) }
        releaseJournal.complete(Unit)
        change.join()

        awaitUntil { coordinator.status.value.droppedAfterRevoke shouldBe 1L }
        stillHolds { h.journaled shouldBe listOf("probe", "evt-a") }
        coVerify(exactly = 1) { h.ingest.discardPendingJournal(ENABLED_PKG) }
        // And nothing new for that source is even queued any more.
        coordinator.offerCaptured(captured("evt-c"))
        stillHolds { h.journaled shouldBe listOf("probe", "evt-a") }
        coordinator.status.value.droppedAfterRevoke shouldBe 1L
    }

    test("a pause between acceptance and commit leaves the event pending instead of committing it") {
        val h = Harness()
        val enteredCheckpoint = CompletableDeferred<Unit>()
        val releaseCheckpoint = CompletableDeferred<Unit>()
        coEvery { h.ingest.checkpoint(any()) } coAnswers {
            enteredCheckpoint.complete(Unit)
            releaseCheckpoint.await()
            null
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        // A snapshot with content: the parser yields a candidate, so the pipeline reaches the commit.
        coordinator.offerCaptured(CapturedNotification(Fixtures.snapshot(Fixtures.base(title = "Alice", text = "hello"), packageName = ENABLED_PKG, eventId = "evt-commit"), null))
        withTimeout(5_000) { enteredCheckpoint.await() }
        coordinator.setPaused(true)
        releaseCheckpoint.complete(Unit)

        awaitUntil { h.journaled shouldBe listOf("evt-commit") }
        stillHolds { coVerify(exactly = 0) { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) } }
        // Not discarded either: it waits in the journal for the resume.
        coVerify(exactly = 0) { h.ingest.markJournal("evt-commit", "DISCARDED", any()) }
    }

    test("replay is held while paused and runs on resume") {
        val h = Harness()
        coEvery { h.ingest.pendingJournal(any(), any()) } returns emptyList()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()
        coordinator.setPaused(true)

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        stillHolds { coVerify(exactly = 0) { h.ingest.pendingJournal(any(), any()) } }

        coordinator.setPaused(false)
        coVerify(timeout = 5_000, atLeast = 1) { h.ingest.pendingJournal(any(), any()) }
    }

    test("replay discards a pending row whose source was disabled since and commits the others") {
        val h = Harness()
        val disabled = Fixtures.snapshot(Fixtures.base(title = null, text = null), packageName = UNLISTED_PKG, eventId = "evt-disabled")
        val enabled = Fixtures.snapshot(Fixtures.base(title = null, text = null), packageName = ENABLED_PKG, eventId = "evt-enabled")
        var served = false
        coEvery { h.ingest.pendingJournal(any(), any()) } coAnswers {
            if (served) emptyList() else { served = true; listOf("gen-old" to disabled, "gen-old" to enabled) }
        }
        coEvery { h.ingest.isJournalPending(any()) } returns true
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        coVerify(timeout = 5_000, exactly = 1) { h.ingest.markJournal("evt-disabled", "DISCARDED", "SOURCE_DISABLED") }
        // The enabled one went through the parser (no content → SKIPPED), i.e. it was processed, not discarded.
        coVerify(timeout = 5_000, exactly = 1) { h.ingest.markJournal("evt-enabled", "SKIPPED", any()) }
        coVerify(exactly = 0) { h.ingest.markJournal("evt-enabled", "DISCARDED", any()) }
        // Replay never re-journals.
        h.journaled shouldBe emptyList()
    }

    // ---- QI-SEC-003: a maintenance run is a complete barrier ---------------------------------

    test("a maintenance run drops what was queued, records an exact gap and starts a fresh generation") {
        val h = Harness()
        val enteredJournal = CompletableDeferred<Unit>()
        val releaseJournal = CompletableDeferred<Unit>()
        h.journalAnswers { snapshot ->
            if (snapshot.eventId == "evt-a") {
                enteredJournal.complete(Unit)
                releaseJournal.await()
            }
            true
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()
        val before = coordinator.status.value.activeGeneration.shouldNotBeNull()

        coordinator.offerCaptured(captured("evt-a"))
        withTimeout(5_000) { enteredJournal.await() }
        coordinator.offerCaptured(captured("evt-b"))
        val ran = CompletableDeferred<Unit>()
        val exclusive = launch { h.maintenance.exclusive { ran.complete(Unit) } }
        // Maintenance is announced before it gets the lock: nothing new is queued from here on.
        awaitUntil { coordinator.status.value.activeGeneration shouldBe null }
        coordinator.offerCaptured(captured("evt-during"))
        releaseJournal.complete(Unit)
        withTimeout(5_000) { ran.await() }
        exclusive.join()

        awaitUntil { coordinator.status.value.activeGeneration.shouldNotBeNull() shouldNotBe before }
        coordinator.status.value.listenerState shouldBe ListenerState.CONNECTED
        awaitUntil { coordinator.status.value.droppedAfterRevoke shouldBe 1L }
        stillHolds { h.journaled shouldBe listOf("evt-a") }
        coVerify(timeout = 5_000) { h.health.endSession(SESSION_ID, any(), "MAINTENANCE") }
        coVerify(timeout = 5_000) { h.health.recordGap(any(), any(), GapReason.MAINTENANCE, GapPrecision.EXACT, any()) }
        // Capture works again under the new generation.
        coordinator.offerCaptured(captured("evt-after"))
        awaitUntil { h.journaled shouldBe listOf("evt-a", "evt-after") }
    }

    test("every maintenance run records its own gap, even two in a row and an instant one") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()

        h.maintenance.exclusive { }
        h.maintenance.exclusive { }

        coVerify(timeout = 5_000, exactly = 2) { h.health.recordGap(any(), any(), GapReason.MAINTENANCE, GapPrecision.EXACT, any()) }
        awaitUntil { coordinator.status.value.activeGeneration.shouldNotBeNull() }
        coordinator.offerCaptured(captured("evt-after-two"))
        awaitUntil { h.journaled shouldBe listOf("evt-after-two") }
    }

    // ---- QI-CAPTURE-013: nothing is read from a notification before the policy is known ---------

    /** A framework notification object: only `packageName` is ever touched before the policy is known. */
    var nextSbn = 0
    fun sbnOf(pkg: String, id: Int = ++nextSbn, key: String = "0|$pkg|$id|null|10000", postTime: Long = 0L): StatusBarNotification =
        mockk(relaxed = true) {
            every { packageName } returns pkg
            every { this@mockk.id } returns id
            every { this@mockk.key } returns key
            every { this@mockk.postTime } returns postTime
        }

    test("before the source list is known a notification is held unread; once known, only sources are snapshotted") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        val created = Collections.synchronizedList(ArrayList<String>())
        every { factory.create(any(), any(), any(), any()) } answers {
            val pkg = firstArg<StatusBarNotification>().packageName
            created += pkg
            captured("evt-$pkg", pkg = pkg)
        }
        // The vault takes a moment: sources.sources() suspends until the test releases it.
        val vaultOpen = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { vaultOpen.await(); listOf(sourceConfig(ENABLED_PKG)) }
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        val unlisted = sbnOf(UNLISTED_PKG)
        coordinator.onPosted(unlisted)
        coordinator.onPosted(sbnOf(ENABLED_PKG))
        // Held, not materialised: the factory has not seen either of them.
        stillHolds { created shouldBe emptyList() }
        coordinator.status.value.queueDepth shouldBe 0

        vaultOpen.complete(Unit)
        awaitUntil { h.journaled shouldBe listOf("evt-$ENABLED_PKG") }
        // The unlisted one was dropped without ever being read: only its package name was touched.
        stillHolds { created shouldBe listOf(ENABLED_PKG) }
        verify(exactly = 0) {
            unlisted.key
            unlisted.postTime
            unlisted.notification
        }
    }

    test("a held buffer that overflowed before the policy was known records the drop as a gap and keeps only sources") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        every { factory.create(any(), any(), any(), any()) } answers {
            val sbn = firstArg<StatusBarNotification>()
            captured("evt-${sbn.id}", pkg = sbn.packageName)
        }
        val vaultOpen = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { vaultOpen.await(); listOf(sourceConfig(ENABLED_PKG)) }
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        // 300 notifications while the vault is still opening: more than the buffer holds.
        for (i in 1..300) coordinator.onPosted(sbnOf(if (i % 2 == 0) ENABLED_PKG else UNLISTED_PKG, id = i))
        vaultOpen.complete(Unit)

        // The drop is not hidden: one bounded COLD_START gap for the overflowed batch...
        coVerify(timeout = 5_000, atLeast = 1) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        // ...and what survived is only the enabled source's notifications.
        awaitUntil { h.journaled.size shouldBe 128 }
        h.journaled.all { it.startsWith("evt-") && it.removePrefix("evt-").toInt() % 2 == 0 } shouldBe true
        stillHolds { h.journaled.size shouldBe 128 }
    }

    test("a journal insert that throws is recorded as a gap, not marked retryable on a row that does not exist") {
        val h = Harness()
        h.journalAnswers { snapshot -> if (snapshot.eventId == "evt-busy") throw IllegalStateException("database is locked") else true }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-busy"))
        coordinator.offerCaptured(captured("evt-ok"))

        awaitUntil { h.journaled shouldBe listOf("evt-busy", "evt-ok") }
        // The gap names the source it belongs to: this one is not process-wide, and "dropped
        // somewhere" was never a useful thing to tell a bug report.
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.UNKNOWN, GapPrecision.EXACT, any(), ENABLED_PKG) }
        coVerify(timeout = 5_000, exactly = 1) { h.ingest.diagnostic("JOURNAL_FAILED", "IllegalStateException", ENABLED_PKG, any()) }
        coVerify(exactly = 0) { h.ingest.markJournalRetryable("evt-busy", any(), any()) }
    }

    test("when the vault does not open, held notifications are dropped unread and a bounded gap is recorded") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        coEvery { h.sources.sources() } throws VaultUnavailableException(KeyFailure.Unavailable("test"))
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(ENABLED_PKG))

        coVerify(timeout = 5_000, exactly = 1) { h.health.openGap(any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        stillHolds { h.journaled shouldBe emptyList() }
        coVerify(exactly = 0) { factory.create(any(), any(), any(), any()) }

        // A second notification while still locked extends the same gap instead of adding another row.
        coordinator.onPosted(sbnOf(ENABLED_PKG))
        stillHolds { coVerify(exactly = 1) { h.health.openGap(any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) } }

        // Once the vault opens and the policy loads, the gap is closed.
        coEvery { h.sources.sources() } returns listOf(sourceConfig(ENABLED_PKG))
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        coVerify(timeout = 5_000, exactly = 1) { h.health.closeOpenGaps(any(), GapReason.COLD_START) }
    }

    test("a cold-start loss while the vault is locked is written as a bounded gap once the vault opens") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        val locked = VaultUnavailableException(KeyFailure.Unavailable("locked"))
        coEvery { h.sources.sources() } throws locked
        // The gap table is behind the same lock: opening a gap fails too.
        coEvery { h.health.openGap(any(), GapReason.COLD_START, any(), any()) } throws locked
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(ENABLED_PKG))
        coVerify(timeout = 5_000, atLeast = 1) { h.health.openGap(any(), GapReason.COLD_START, any(), any()) }
        stillHolds { coVerify(exactly = 0) { h.health.recordGap(any(), any(), GapReason.COLD_START, any(), any()) } }

        // The vault opens: the loss is written now, bounded, exactly once.
        coEvery { h.sources.sources() } returns listOf(sourceConfig(ENABLED_PKG))
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        coVerify(exactly = 0) { factory.create(any(), any(), any(), any()) }
    }

    test("a lock-out gap the pipeline could not open is written as a bounded gap once the vault opens") {
        val h = Harness()
        val locked = VaultUnavailableException(KeyFailure.Unavailable("locked"))
        h.journalAnswers { throw locked }
        coEvery { h.health.openGap(any(), GapReason.UNKNOWN, any(), any()) } throws locked
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-locked"))
        awaitUntil { h.journaled shouldBe listOf("evt-locked") }
        awaitUntil { coordinator.status.value.vaultLocked shouldBe true }

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.UNKNOWN, GapPrecision.BOUNDED, any()) }
        coVerify(timeout = 5_000, exactly = 1) { h.health.closeOpenGaps(any(), GapReason.UNKNOWN) }
    }

    test("a cold-start loss whose settle failed is kept and written on the next policy load") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        val locked = VaultUnavailableException(KeyFailure.Unavailable("locked"))
        coEvery { h.sources.sources() } throws locked
        coEvery { h.health.openGap(any(), GapReason.COLD_START, any(), any()) } throws locked
        // The first settle fails half-way (the vault locked again); the second one succeeds.
        coEvery { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) } throws locked andThen Unit
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(ENABLED_PKG))
        coVerify(timeout = 5_000, atLeast = 1) { h.health.openGap(any(), GapReason.COLD_START, any(), any()) }

        coEvery { h.sources.sources() } returns listOf(sourceConfig(ENABLED_PKG))
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        // Not forgotten: the next policy load writes it, and only then does it stop being retried.
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG), sourceConfig(UNLISTED_PKG)))
        coVerify(timeout = 5_000, exactly = 2) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        stillHolds { coVerify(exactly = 2) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) } }
        coVerify(exactly = 0) { factory.create(any(), any(), any(), any()) }
    }

    test("a cold-start gap row that lands after the policy loaded is closed at once, not left open") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        val policyReady = CompletableDeferred<Unit>()
        val gapGate = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { policyReady.await(); listOf(sourceConfig(ENABLED_PKG)) }
        // The open-gap insert waits for the vault like any other write.
        coEvery { h.health.openGap(any(), GapReason.COLD_START, any(), any()) } coAnswers { gapGate.await(); 7L }
        val coordinator = h.coordinator().also { it.snapshotFactory = factory; it.coldStartTimeoutMs = 200L }
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(ENABLED_PKG))
        // The vault did not open in time: the buffer is dropped and the gap row is being opened.
        coVerify(timeout = 5_000, exactly = 1) { h.health.openGap(any(), GapReason.COLD_START, any(), any()) }

        // The vault opens and the policy loads while that insert is still waiting: its settle finds no row.
        policyReady.complete(Unit)
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        coVerify(timeout = 5_000, exactly = 1) { h.health.closeOpenGaps(any(), GapReason.COLD_START) }

        // The insert lands afterwards: closed right away instead of staying open until the next policy load.
        gapGate.complete(Unit)
        coVerify(timeout = 5_000, atLeast = 2) { h.health.closeOpenGaps(any(), GapReason.COLD_START) }
        coVerify(exactly = 0) { factory.create(any(), any(), any(), any()) }
    }

    test("a cold-start gap row that lands between the policy's settle and the flag flip is closed by the policy load") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        val policyReady = CompletableDeferred<Unit>()
        val gapGate = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>()
        val closeGate = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { policyReady.await(); listOf(sourceConfig(ENABLED_PKG)) }
        coEvery { h.health.openGap(any(), GapReason.COLD_START, any(), any()) } coAnswers { gapGate.await(); 7L }
        // The settle's close is held open so the row can land after it and before the flag flips.
        coEvery { h.health.closeOpenGaps(any(), GapReason.COLD_START) } coAnswers {
            if (!closeStarted.isCompleted) { closeStarted.complete(Unit); closeGate.await() }
        }
        val coordinator = h.coordinator().also { it.snapshotFactory = factory; it.coldStartTimeoutMs = 200L }
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(ENABLED_PKG))
        coVerify(timeout = 5_000, exactly = 1) { h.health.openGap(any(), GapReason.COLD_START, any(), any()) }
        policyReady.complete(Unit)
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        withTimeout(5_000) { closeStarted.await() }
        // The row lands now, while the settle's close is still running and the flag is still false.
        gapGate.complete(Unit)
        stillHolds { coVerify(exactly = 1) { h.health.closeOpenGaps(any(), GapReason.COLD_START) } }

        closeGate.complete(Unit)
        // The policy load re-checks after flipping the flag and closes the late row.
        coVerify(timeout = 5_000, atLeast = 2) { h.health.closeOpenGaps(any(), GapReason.COLD_START) }
        coVerify(exactly = 0) { factory.create(any(), any(), any(), any()) }
    }

    test("an overflow gap whose write failed is kept and written on the next policy load") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        every { factory.create(any(), any(), any(), any()) } answers {
            val sbn = firstArg<StatusBarNotification>()
            captured("evt-${sbn.id}", pkg = sbn.packageName)
        }
        val vaultOpen = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { vaultOpen.await(); listOf(sourceConfig(ENABLED_PKG)) }
        // The overflow gap's write fails once (disk full), then succeeds.
        coEvery { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) } throws IllegalStateException("full") andThen Unit
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        for (i in 1..300) coordinator.onPosted(sbnOf(ENABLED_PKG, id = i))
        vaultOpen.complete(Unit)
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        awaitUntil { h.journaled.size shouldBe 256 }

        // The loss was kept: the next policy load writes it, and only then is it forgotten.
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG), sourceConfig(UNLISTED_PKG)))
        coVerify(timeout = 5_000, exactly = 2) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        stillHolds { coVerify(exactly = 2) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) } }
    }

    test("a notification held across a disconnect gets no gap when it is paused or captured again by the resync") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        every { factory.create(any(), any(), any(), any()) } answers { captured("evt-live", pkg = firstArg<StatusBarNotification>().packageName) }
        val vaultOpen = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { vaultOpen.await(); listOf(sourceConfig(ENABLED_PKG), sourceConfig(PAUSED_PKG, paused = true)) }
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        val stillInShade = sbnOf(ENABLED_PKG, key = "0|$ENABLED_PKG|42|null|10000")
        coordinator.onPosted(stillInShade)
        coordinator.onPosted(sbnOf(PAUSED_PKG))
        coordinator.onDisconnected()
        coordinator.onConnected(h.service)
        // The rebind's resync offers the notification that is still in the shade again (the resync
        // runs on its own coroutine, so the same framework object is offered here, synchronously).
        coordinator.onPosted(stillInShade)
        vaultOpen.complete(Unit)

        // The current copy is captured; the stale copy and the paused source's notification are no loss.
        awaitUntil { h.journaled shouldBe listOf("evt-live") }
        stillHolds { coVerify(exactly = 0) { h.health.recordGap(any(), any(), GapReason.COLD_START, any(), any()) } }
    }

    test("a stale copy with the same key but an older post time is a loss of its own") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        every { factory.create(any(), any(), any(), any()) } answers { captured("evt-live", pkg = firstArg<StatusBarNotification>().packageName) }
        val vaultOpen = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { vaultOpen.await(); listOf(sourceConfig(ENABLED_PKG)) }
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        // The app replaced the notification's content under the same key while the listener was away.
        coordinator.onPosted(sbnOf(ENABLED_PKG, id = 42, postTime = 1_000L))
        coordinator.onDisconnected()
        coordinator.onConnected(h.service)
        coordinator.onPosted(sbnOf(ENABLED_PKG, id = 42, postTime = 2_000L))
        vaultOpen.complete(Unit)

        awaitUntil { h.journaled shouldBe listOf("evt-live") }
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
    }

    test("a disconnect landing while held notifications are released gives the later ones a gap instead of letting them suppress themselves") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        val coordinatorRef = CompletableDeferred<CaptureCoordinator>()
        every { factory.create(any(), any(), any(), any()) } answers {
            val sbn = firstArg<StatusBarNotification>()
            // The listener is rebound in the middle of the release, right after the first snapshot.
            if (sbn.id == 1) coordinatorRef.getCompleted().onDisconnected()
            captured("evt-${sbn.id}", pkg = sbn.packageName)
        }
        val vaultOpen = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { vaultOpen.await(); listOf(sourceConfig(ENABLED_PKG)) }
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinatorRef.complete(coordinator)
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(ENABLED_PKG, id = 1))
        coordinator.onPosted(sbnOf(ENABLED_PKG, id = 2))
        vaultOpen.complete(Unit)

        // The first was queued before the disconnect and is fenced by the consumer like any queued
        // event; the second is stale by then and, since it was never queued, gets a gap of its own
        // rather than being judged "queued already" by its own key.
        awaitUntil { coordinator.status.value.droppedAfterRevoke shouldBe 1L }
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        stillHolds {
            h.journaled shouldBe emptyList()
            coVerify(exactly = 1) { factory.create(any(), any(), any(), any()) }
        }
    }

    test("a notification held across a disconnect is recorded as a gap, not dropped silently") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        val vaultOpen = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { vaultOpen.await(); listOf(sourceConfig(ENABLED_PKG)) }
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(ENABLED_PKG))
        // The listener is rebound while the notification is still held: a new generation.
        coordinator.onDisconnected()
        coordinator.onConnected(h.service)
        vaultOpen.complete(Unit)

        // Its arrival predates the disconnect gap, so the held window gets a gap of its own.
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        stillHolds { coVerify(exactly = 0) { factory.create(any(), any(), any(), any()) } }
    }

    // ---- QI-MEDIA-006: a bitmap stays counted until the copier is done with it -------------------

    test("bitmaps in flight at the copier still count against the queue bound") {
        val h = Harness()
        val bitmap: android.graphics.Bitmap = mockk(relaxed = true)
        coEvery { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) } returns CommitOutcome(1L, listOf(1L), emptyList(), listOf(1L), 0, false)
        val copying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bitmaps = Collections.synchronizedList(ArrayList<android.graphics.Bitmap?>())
        // Every copy stays in flight until released: an in-flight bitmap is what the bound counts.
        coEvery { h.mediaCopier.copyPending(any(), any()) } coAnswers {
            bitmaps += secondArg<android.graphics.Bitmap?>()
            if (bitmaps.size == 1) copying.complete(Unit)
            release.await()
        }
        fun withBitmap(id: String) = CapturedNotification(Fixtures.snapshot(Fixtures.base(title = "A", text = "picture"), packageName = ENABLED_PKG, eventId = id), bitmap)
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(withBitmap("evt-0"))
        withTimeout(5_000) { copying.await() }
        // The first copy is still running: its bitmap is still counted. Seven more fill the bound
        // (counted at the door, whether queued or already in flight)...
        for (i in 1..7) coordinator.offerCaptured(withBitmap("evt-$i"))
        // ...so the ninth bitmap is dropped at the door (a placeholder is kept), not queued.
        coordinator.offerCaptured(withBitmap("evt-8"))
        release.complete(Unit)

        awaitUntil { bitmaps.size shouldBe 9 }
        // Copies are launched in order but not started in order: assert the bound, not the index.
        bitmaps.count { it == null } shouldBe 1
    }

    test("switching a source off opens a gap that names it, and switching it back on closes it") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.setSourceEnabled(ENABLED_PKG, false)

        // Its own reason and its own source. Before this the only trace was `droppedAfterRevoke`,
        // one counter that also holds a revoked permission, a rotated generation and maintenance —
        // so the one cause the user chose was indistinguishable from three they did not.
        coVerify(timeout = 5_000, exactly = 1) {
            h.health.openGap(any(), GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, any(), ENABLED_PKG)
        }

        coordinator.setSourceEnabled(ENABLED_PKG, true)
        coVerify(timeout = 5_000, exactly = 1) {
            h.health.closeOpenGapsForSource(any(), ENABLED_PKG, GapReason.SOURCE_DISABLED_BY_USER)
        }
    }

    test("setting a source flag to the value it already has is not a second gap") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        // The health page shows one interval per event. Two "disable" taps, or a screen that
        // re-submits its state, are one event; the second must change nothing.
        coordinator.setSourceEnabled(ENABLED_PKG, false)
        coordinator.setSourceEnabled(ENABLED_PKG, false)
        coordinator.setSourcePaused(ENABLED_PKG, false)

        awaitUntil {
            coVerify(exactly = 1) {
                h.health.openGap(any(), GapReason.SOURCE_DISABLED_BY_USER, any(), any(), ENABLED_PKG)
            }
        }
        stillHolds {
            coVerify(exactly = 1) {
                h.health.openGap(any(), GapReason.SOURCE_DISABLED_BY_USER, any(), any(), ENABLED_PKG)
            }
            // It was already unpaused, so nothing was written for the pause either way.
            coVerify(exactly = 0) { h.health.openGap(any(), GapReason.SOURCE_PAUSED_BY_USER, any(), any(), any()) }
            coVerify(exactly = 0) { h.health.closeOpenGapsForSource(any(), any(), GapReason.SOURCE_PAUSED_BY_USER) }
        }
    }

    test("removing a source closes the gap it left open, and forgets its name when the data goes too") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.setSourceEnabled(ENABLED_PKG, false)
        coVerify(timeout = 5_000, exactly = 1) {
            h.health.openGap(any(), GapReason.SOURCE_DISABLED_BY_USER, any(), any(), ENABLED_PKG)
        }

        // Removing is the one change nothing can undo: re-adding goes through addSource, which
        // opens nothing and closes nothing, so an interval left open here would read as "capture
        // is still missing" for ever.
        coordinator.removeSource(ENABLED_PKG, deleteData = true)

        coVerify(timeout = 5_000, exactly = 1) {
            h.health.closeOpenGapsForSource(
                any(),
                ENABLED_PKG,
                GapReason.SOURCE_DISABLED_BY_USER,
                GapReason.SOURCE_PAUSED_BY_USER,
            )
        }
        // The interval stays — it is the honest record — but it stops naming the app the user
        // asked to have forgotten.
        coVerify(timeout = 5_000, exactly = 1) { h.health.forgetGapSource(ENABLED_PKG) }
    }

    test("removing a source without deleting its data keeps the source's name on the gap") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        // The negative control for the test above: stopping capture and deleting saved copies are
        // separate decisions, so only the second one takes the name.
        coordinator.setSourceEnabled(ENABLED_PKG, false)
        coordinator.removeSource(ENABLED_PKG, deleteData = false)

        coVerify(timeout = 5_000, exactly = 1) {
            h.health.closeOpenGapsForSource(any(), ENABLED_PKG, GapReason.SOURCE_DISABLED_BY_USER, GapReason.SOURCE_PAUSED_BY_USER)
        }
        stillHolds { coVerify(exactly = 0) { h.health.forgetGapSource(any()) } }
    }

    test("a source paused, then switched off and on again, still has a gap saying it is paused") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        // Round 34 C1, found independently by two reviewers, and mine. The reconciliation on
        // policy load asked `pausedPackages` whether the source was paused — but that allow-list
        // deliberately holds only *enabled* sources, so it says "no" for one that is paused and
        // disabled. The pause gap was closed by the very load that followed the disable, and
        // re-enabling never opened another: the source came back still paused, still not being
        // captured, with nothing on the health page saying so.
        coordinator.setSourcePaused(ENABLED_PKG, true)
        awaitUntil { h.openGapsFor(ENABLED_PKG, GapReason.SOURCE_PAUSED_BY_USER) shouldHaveSize 1 }
        coordinator.setSourceEnabled(ENABLED_PKG, false)
        coordinator.setSourceEnabled(ENABLED_PKG, true)

        awaitUntil { h.openGapsFor(ENABLED_PKG, GapReason.SOURCE_DISABLED_BY_USER) shouldHaveSize 0 }
        stillHolds {
            // Still paused, so the pause gap is still the truth.
            h.openGapsFor(ENABLED_PKG, GapReason.SOURCE_PAUSED_BY_USER) shouldHaveSize 1
        }
    }

    test("pausing a source that is switched off keeps the gap that says so") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        // The other half of the same defect: the policy load that follows the pause closed the
        // gap it had just opened, because a disabled source is not in `pausedPackages` either.
        coordinator.setSourceEnabled(ENABLED_PKG, false)
        coordinator.setSourcePaused(ENABLED_PKG, true)

        awaitUntil { h.openGapsFor(ENABLED_PKG, GapReason.SOURCE_PAUSED_BY_USER) shouldHaveSize 1 }
        stillHolds { h.openGapsFor(ENABLED_PKG, GapReason.SOURCE_PAUSED_BY_USER) shouldHaveSize 1 }
    }

    test("a gap an earlier version left open against its own policy is closed when the policy loads") {
        val h = Harness()
        // What a process death between the two writes used to leave behind: the source is enabled,
        // and a "disabled by the user" interval is still open against it. They are one transaction
        // now, so this can only be an older row — and the policy, not the row, is what capture does.
        val stale = GapIntervalEntity(
            id = 7,
            startEpochMs = 1_000,
            endEpochMs = null,
            reason = GapReason.SOURCE_DISABLED_BY_USER.name,
            precision = GapPrecision.EXACT.name,
            createdAtEpochMs = 1_000,
            packageName = ENABLED_PKG,
        )
        coEvery { h.health.openSourceGaps() } returnsMany listOf(listOf(stale), emptyList())
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(capturedWithTruncation("evt-any", emptySet()))

        coVerify(timeout = 5_000, exactly = 1) { h.health.closeGap(7, any()) }
    }

    test("pausing one source never closes another source's gap") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.setSourcePaused(ENABLED_PKG, true)
        coVerify(timeout = 5_000, exactly = 1) {
            h.health.openGap(any(), GapReason.SOURCE_PAUSED_BY_USER, GapPrecision.EXACT, any(), ENABLED_PKG)
        }

        coordinator.setSourcePaused(ENABLED_PKG, false)
        // Scoped to the package: a global close would have ended an unrelated source's gap too.
        coVerify(timeout = 5_000, exactly = 1) {
            h.health.closeOpenGapsForSource(any(), ENABLED_PKG, GapReason.SOURCE_PAUSED_BY_USER)
        }
        coVerify(exactly = 0) { h.health.closeOpenGaps(any(), GapReason.SOURCE_PAUSED_BY_USER) }
    }

    test("messages dropped before parsing are a gap, even though the ingest that followed succeeded") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        // The notification carried more messages than the snapshot may hold. The commit afterwards
        // is a perfectly good one, so nothing else would ever say that content existed and was lost.
        // The batch really does have a body, so this is the committed case rather than the empty
        // one filed as skipped — which is what the test is named for (round 35 Codex M1).
        val survived = CapturedNotification(
            Fixtures.snapshot(
                shape = Fixtures.messaging(conversationTitle = "Group") { message("Ana", "the newest one") }
                    .copy(truncated = setOf(TruncationFlag.MESSAGES_DROPPED)),
                packageName = ENABLED_PKG,
                eventId = "evt-drop",
            ),
            null,
        )
        coordinator.offerCaptured(survived)

        coVerify(timeout = 5_000, exactly = 1) {
            h.health.recordGap(any(), any(), GapReason.MESSAGES_DROPPED, GapPrecision.BOUNDED, any(), ENABLED_PKG)
        }
        // It was committed, not skipped: the loss belongs to an event whose survivors were stored.
        // With a timeout, because the gap is written in the acceptance transaction and the commit
        // comes after it: verifying without one asserts on a point the pipeline has not reached
        // yet, and passes or fails on scheduling (round 36 Codex M1).
        coVerify(timeout = 5_000, exactly = 1) { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.ingest.markJournal("evt-drop", "SKIPPED", any()) }
    }

    test("an event the journal already holds does not record its loss a second time") {
        val h = Harness()
        // What the primary key does in the real repository: the second insert of an eventId
        // changes nothing and returns -1, so acceptance — and the loss written with it — happens
        // exactly once however many times the event is delivered.
        val seen = mutableSetOf<String>()
        h.journalAnswers { seen.add(it.eventId) }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(capturedWithTruncation("evt-twice", setOf(TruncationFlag.MESSAGES_DROPPED)))
        awaitUntil { coordinator.status.value.acceptedCount shouldBe 1L }
        coordinator.offerCaptured(capturedWithTruncation("evt-twice", setOf(TruncationFlag.MESSAGES_DROPPED)))
        awaitUntil { h.journaled.count { it == "evt-twice" } shouldBe 2 }

        stillHolds {
            coVerify(exactly = 1) {
                h.health.recordGap(any(), any(), GapReason.MESSAGES_DROPPED, any(), any(), any())
            }
        }
    }

    test("replaying a pending row does not record its loss again") {
        val h = Harness()
        // The regression this guards: while the gap was written inside processJournaled, every
        // replay of the same row wrote another one, so one loss was listed on the health page as
        // many. It is now written once, in the transaction that accepted the event — which this
        // row went through in an earlier run, before it was left pending.
        val pending = Fixtures.snapshot(
            shape = Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.MESSAGES_DROPPED)),
            packageName = ENABLED_PKG,
            eventId = "evt-pending-drop",
        )
        var served = false
        coEvery { h.ingest.pendingJournal(any(), any()) } coAnswers {
            if (served) emptyList() else { served = true; listOf("gen-old" to pending) }
        }
        coEvery { h.ingest.isJournalPending(any()) } returns true
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        // It really was replayed and processed, so a gap written on this path would have been seen.
        coVerify(timeout = 5_000, exactly = 1) { h.ingest.markJournal("evt-pending-drop", "SKIPPED", any()) }
        h.journaled shouldBe emptyList()
        coVerify(exactly = 0) {
            h.health.recordGap(any(), any(), GapReason.MESSAGES_DROPPED, any(), any(), any())
        }
    }

    test("lines an InboxStyle notification could not hold are a gap, not a shortened body") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        // Codex I3. More lines arrived than the snapshot may hold, so the oldest were discarded
        // outright. That is content that existed and is gone — the same loss as a dropped message,
        // and nothing else would ever have said so. Each surviving line still carries its own
        // truncation separately, which is a different thing entirely.
        coordinator.offerCaptured(capturedWithTruncation("evt-lines", setOf(TruncationFlag.LINES_DROPPED)))

        coVerify(timeout = 5_000, exactly = 1) {
            h.health.recordGap(any(), any(), GapReason.MESSAGES_DROPPED, GapPrecision.BOUNDED, any(), ENABLED_PKG)
        }
    }

    test("a message whose text was merely shortened is not a gap") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        // The negative control for the test above: the two losses shared one flag until this
        // release, so a shortened body would have manufactured a gap that never happened.
        coordinator.offerCaptured(capturedWithTruncation("evt-short", setOf(TruncationFlag.MESSAGES, TruncationFlag.BIG_TEXT)))

        awaitUntil { coordinator.status.value.acceptedCount shouldBe 1L }
        stillHolds { coVerify(exactly = 0) { h.health.recordGap(any(), any(), GapReason.MESSAGES_DROPPED, any(), any(), any()) } }
    }

    test("a row left pending by 0.1.3 has its dropped lines settled once, however often it is replayed") {
        val h = Harness()
        // Codex round 34 C2. Releases up to 0.1.3 wrote no gap for content the framework had
        // already dropped, and such a row can still be PENDING when this release starts. It never
        // goes through `journal()` — replay hands it straight to processJournaled — so the
        // acceptance transaction that now writes the loss never runs for it, and the terminal
        // transition afterwards clears the payload that was the only evidence it happened.
        //
        // `LINES` settles the case by itself: those releases raised it only when the line array
        // was longer than the snapshot may hold. A single over-long line was shortened silently.
        val legacy = Fixtures.snapshot(
            shape = Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.LINES)),
            packageName = ENABLED_PKG,
            eventId = "evt-v013-lines",
        )
        var served = 0
        coEvery { h.ingest.pendingJournal(any(), any()) } coAnswers {
            if (served++ < 2) listOf("gen-old" to legacy) else emptyList()
        }
        // Pending when it is picked up, no longer pending the moment after — then picked up again,
        // which is what a row that keeps failing to commit does.
        var checks = 0
        coEvery { h.ingest.isJournalPending(any()) } answers { checks++ % 2 == 0 }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        // It really was replayed twice, so anything recording the loss per pass rather than per
        // event would have written the second gap by now.
        coVerify(timeout = 5_000, exactly = 2) { h.ingest.markJournal("evt-v013-lines", "SKIPPED", any()) }
        h.gaps.count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
    }

    test("a 0.1.3 flag that can only mean whole messages were dropped is settled") {
        val h = Harness()
        // The second decidable shape. `MESSAGES` had two causes in those releases — a kept message
        // whose text was shortened, and whole messages over the limit — but the payload rules the
        // first out: every message it still carries is complete.
        val legacy = Fixtures.snapshot(
            shape = Fixtures.messaging(conversationTitle = "Group") { message("Ana", "on my way") }
                .copy(truncated = setOf(TruncationFlag.MESSAGES)),
            packageName = ENABLED_PKG,
            eventId = "evt-v013-messages",
        )
        var served = false
        coEvery { h.ingest.pendingJournal(any(), any()) } coAnswers {
            if (served) emptyList() else { served = true; listOf("gen-old" to legacy) }
        }
        coEvery { h.ingest.isJournalPending(any()) } returns true
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        awaitUntil { h.gaps.count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1 }
    }

    test("a 0.1.3 flag that could equally be one shortened message is left alone") {
        val h = Harness()
        // The negative control, and the boundary of the claim above: with a surviving message that
        // was itself shortened, the flag is explained without any message having been dropped, and
        // the payload cannot say whether one also was. A gap here would be a loss invented from
        // evidence that does not support it — the mirror of the defect this all exists to fix.
        val ambiguous = Fixtures.snapshot(
            shape = Fixtures.messaging(conversationTitle = "Group") {
                message("Ana", "on my way", truncated = true)
            }.copy(truncated = setOf(TruncationFlag.MESSAGES)),
            packageName = ENABLED_PKG,
            eventId = "evt-v013-ambiguous",
        )
        var served = false
        coEvery { h.ingest.pendingJournal(any(), any()) } coAnswers {
            if (served) emptyList() else { served = true; listOf("gen-old" to ambiguous) }
        }
        coEvery { h.ingest.isJournalPending(any()) } returns true
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        coVerify(timeout = 5_000, atLeast = 1) { h.ingest.pendingJournal(any(), any()) }
        stillHolds { h.gaps.none { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe true }
    }

    test("pending rows discarded with their source are settled while their payload can still be read") {
        val h = Harness()
        // The other way out of PENDING. These rows are never replayed: disabling the source
        // discards them and empties the payload in one statement, so if the loss is not taken here
        // there is no later moment at which it could be.
        val legacy = Fixtures.snapshot(
            shape = Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.LINES)),
            packageName = ENABLED_PKG,
            eventId = "evt-v013-discarded",
        )
        h.pendingByPackage[ENABLED_PKG] = mutableListOf(legacy)
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()

        coordinator.setSourceEnabled(ENABLED_PKG, false)
        // The settle ran before the discard: reversing the two inside the policy transaction
        // empties the pending rows first and this gap is never written.
        h.gaps.count { it.reason == GapReason.MESSAGES_DROPPED.name && it.packageName == ENABLED_PKG } shouldBe 1
        coVerify(exactly = 1) { h.ingest.discardPendingJournal(ENABLED_PKG) }

        // Re-enabled and switched off again: the rows really are gone, so there is nothing to
        // settle a second time, and the claim would refuse it even if there were.
        coordinator.setSourceEnabled(ENABLED_PKG, true)
        coordinator.setSourceEnabled(ENABLED_PKG, false)
        h.gaps.count { it.reason == GapReason.MESSAGES_DROPPED.name && it.packageName == ENABLED_PKG } shouldBe 1
    }

    test("a loss neither the journal nor its fallback could record is written on the next policy load") {
        val h = Harness()
        // Codex round 34 I3. Whatever stopped the acceptance write — a full disk is the case that
        // matters — does not stop at one statement, so the fallback gap fails too. "Less precise,
        // never absent" then said more than the code did: nothing anywhere recorded the event.
        val full = IllegalStateException("no space left on device")
        h.journalAnswers { throw full }
        coEvery {
            h.health.recordGap(any(), any(), GapReason.UNKNOWN, GapPrecision.EXACT, any(), any())
        } throws full
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()

        coordinator.offerCaptured(capturedWithTruncation("evt-nospace", emptySet()))
        awaitUntil { coordinator.lastError shouldBe "IllegalStateException" }
        stillHolds { h.gaps.isEmpty() shouldBe true }

        // Writable again: the next policy load records it, bounded by when it happened and when
        // it could finally be written.
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        awaitUntil { h.gaps.count { it.reason == GapReason.UNKNOWN.name } shouldBe 1 }
        h.gaps.single { it.reason == GapReason.UNKNOWN.name }.endEpochMs shouldNotBe null
        // And forgotten only then: a later load does not write the same loss again.
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG), sourceConfig(UNLISTED_PKG)))
        stillHolds { h.gaps.count { it.reason == GapReason.UNKNOWN.name } shouldBe 1 }
    }

    test("a remembered loss is written by the next event the vault accepts, with no policy change") {
        val h = Harness()
        // Round 35, agy I1. Hanging the settle on the policy load alone left the loss in memory for
        // as long as the user changed no source — which on a device that simply got its disk space
        // back is indefinitely, with capture working normally the whole time. A process death in
        // that window took the only record that the event had ever existed.
        val full = IllegalStateException("no space left on device")
        h.journalAnswers { throw full }
        coEvery {
            h.health.recordGap(any(), any(), GapReason.UNKNOWN, GapPrecision.EXACT, any(), any())
        } throws full
        var policyLoads = 0
        coEvery { h.sources.sources() } answers { policyLoads++; listOf(sourceConfig(ENABLED_PKG)) }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()

        coordinator.offerCaptured(capturedWithTruncation("evt-nospace-2", emptySet()))
        awaitUntil { coordinator.lastError shouldBe "IllegalStateException" }
        stillHolds { h.gaps.isEmpty() shouldBe true }
        // The policy loaded once, on the way in to this first event; the point of the test is that
        // it never loads again, so nothing after this line can be the policy load's doing.
        val loadsSoFar = policyLoads

        // Space is back. The next accepted event is itself the proof that the vault takes writes,
        // and no source was touched between the two.
        h.journalAnswers { true }
        coordinator.offerCaptured(capturedWithTruncation("evt-after", emptySet()))

        awaitUntil { h.gaps.count { it.reason == GapReason.UNKNOWN.name } shouldBe 1 }
        h.gaps.single { it.reason == GapReason.UNKNOWN.name }.endEpochMs shouldNotBe null
        // And it really was the acceptance that did it: the policy never loaded again.
        policyLoads shouldBe loadsSoFar
    }

    test("a carried-over row whose loss cannot be written is not committed and spends no attempt") {
        val h = Harness()
        // Round 35, subagent C2. The claim sat inside the try whose catch is markJournalRetryable,
        // so a failing *bookkeeping* write spent one of the event's three commit attempts. Three of
        // them filed the row FAILED, which clears the payload — losing the survivors and the record
        // together, with nothing on the health page. It is also not allowed to fall through and
        // commit: storing the batch while the record of what it lost goes missing is the round-33
        // finding. The row simply stays as it was, for a pass that can write.
        // A real messaging body, so "not committed" is a claim about the guard rather than about
        // the fixture: a body-less snapshot is filed SKIPPED and would never have been committed
        // whatever the settlement did (round 36 subagent I1, the same flaw as round 35 Codex M1).
        val legacy = Fixtures.snapshot(
            shape = Fixtures.messaging(conversationTitle = "Group") { message("Ana", "the newest one") }
                .copy(truncated = setOf(TruncationFlag.LINES)),
            packageName = ENABLED_PKG,
            eventId = "evt-claim-fails",
        )
        var served = 0
        coEvery { h.ingest.pendingJournal(any(), any()) } coAnswers {
            if (served++ < 1) listOf("gen-old" to legacy) else emptyList()
        }
        coEvery { h.ingest.isJournalPending(any()) } returns true
        coEvery { h.ingest.claimEventLoss(any(), any()) } throws IllegalStateException("no space left on device")
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        // The row was reached — the claim was asked for — and then nothing else happened to it.
        coVerify(timeout = 5_000, atLeast = 1) { h.ingest.claimEventLoss("evt-claim-fails", any()) }
        stillHolds {
            // Not committed. The commit is what would have stored the survivors while the record of
            // what they were missing went missing with them, and it writes COMMITTED itself, so
            // `markJournal` never sees it: asserting only on `markJournal` asserts nothing here.
            coVerify(exactly = 0) { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { h.ingest.markJournalRetryable(any(), any(), any()) }
            coVerify(exactly = 0) { h.ingest.markJournal("evt-claim-fails", any(), any()) }
            h.gaps.isEmpty() shouldBe true
        }
    }

    test("rows whose loss cannot be written stop holding the page against the rows behind them") {
        val h = Harness()
        // Round 36, Codex I1, at the boundary Codex named. The page is 200 rows wide; the first 200
        // carry a loss the gap table refuses, and the 201st is an ordinary event with nothing to
        // settle. Keeping a refused row PENDING is right — its payload is the only evidence of what
        // it lost — but keeping it *on the page* meant no replay ever reached row 201, however many
        // times the user triggered one.
        val blocked = (1..200).map { i ->
            "gen-old" to Fixtures.snapshot(
                shape = Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.LINES)),
                packageName = ENABLED_PKG,
                eventId = "evt-blocked-%03d".format(i),
                observedAt = 1_000L + i,
            )
        }
        val behind = "gen-old" to Fixtures.snapshot(
            shape = Fixtures.messaging(conversationTitle = "Group") { message("Ana", "still here") },
            packageName = ENABLED_PKG,
            eventId = "evt-behind",
            observedAt = 9_000L,
        )
        h.pendingReplay += blocked
        h.pendingReplay += behind
        coEvery { h.ingest.isJournalPending(any()) } returns true
        h.gapWritesFail = true

        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        // The 201st row reached the pipeline and was stored. Nothing else in this test commits:
        // the 200 ahead of it return before the fence.
        coVerify(timeout = 10_000, atLeast = 1) { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) }
        // And the blocked rows paid for it with their place on the page, not with their evidence:
        // no attempt was charged to any of them, and none was given up on.
        stillHolds {
            coVerify(exactly = 0) { h.ingest.markJournalRetryable(any(), any(), any()) }
            h.lossDeferred.size shouldBe 200
            h.gaps.isEmpty() shouldBe true
        }
    }

    test("a deferred settlement is retried when a gap write is next seen to succeed") {
        val h = Harness()
        // Round 36, Codex I1's second half: nothing rescheduled the blocked rows. The four things
        // that trigger a replay are all a user or a lifecycle event — vault ready, unpause,
        // maintenance ending, a manual recovery — so a device that got its disk space back could
        // wait days. The trigger here is proof rather than a timer: an event accepted *with* a
        // loss wrote a gap in its acceptance transaction, so the table that refused is taking
        // writes again.
        val stuck = "gen-old" to Fixtures.snapshot(
            shape = Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.LINES)),
            packageName = ENABLED_PKG,
            eventId = "evt-stuck",
            observedAt = 1_000L,
        )
        h.pendingReplay += stuck
        coEvery { h.ingest.isJournalPending(any()) } returns true
        h.gapWritesFail = true

        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        awaitUntil { h.lossDeferred shouldBe setOf("evt-stuck") }
        stillHolds { h.gaps.isEmpty() shouldBe true }

        // Space is back, and the next event to arrive carries a loss of its own, so accepting it
        // writes a gap. No source was touched, no maintenance ran, nothing was unpaused.
        h.gapWritesFail = false
        coordinator.offerCaptured(capturedWithTruncation("evt-live", setOf(TruncationFlag.MESSAGES_DROPPED)))

        // Two gaps now: the live event's own, and the one the stuck row had been carrying since a
        // release that recorded it nowhere.
        awaitUntil { h.gaps.count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 2 }
        h.lossClaimed shouldBe setOf("evt-stuck")
    }

    test("an event accepted without a loss does not retry a deferred settlement") {
        val h = Harness()
        // The negative control for the trigger above, and the reason it is not simply "any accepted
        // event": an acceptance that wrote no gap proves the journal takes writes, and the table
        // that refused was the gap table. Retrying on it would turn a stream of ordinary events
        // into a stream of replays for as long as the vault stayed broken.
        h.pendingReplay += "gen-old" to Fixtures.snapshot(
            shape = Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.LINES)),
            packageName = ENABLED_PKG,
            eventId = "evt-stuck-2",
            observedAt = 1_000L,
        )
        coEvery { h.ingest.isJournalPending(any()) } returns true
        h.gapWritesFail = true

        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        // The pass defers the row, drains, resumes it once and defers it again before it ends: wait
        // for the second claim, or the flip below lands while the pass is still trying and the
        // resumed claim simply succeeds — a race in the test, not a retry by the code.
        awaitUntil { coVerify(exactly = 2) { h.ingest.claimEventLoss("evt-stuck-2", any()) } }
        awaitUntil { h.lossDeferred shouldBe setOf("evt-stuck-2") }

        h.gapWritesFail = false
        coordinator.offerCaptured(capturedWithTruncation("evt-plain", emptySet()))

        // The row is still deferred: the vault may be writable again, but nothing has shown it.
        stillHolds {
            h.lossDeferred shouldBe setOf("evt-stuck-2")
            h.gaps.isEmpty() shouldBe true
        }
    }

    test("a deferred row is still settled before its source is disabled") {
        val h = Harness()
        // Round 36. The deferral takes a row out of both readers of pending rows, and the discard
        // that follows a disable clears its payload for good: if the walk did not resume first,
        // the one path that still held the evidence would be the one path that skipped it.
        val legacy = Fixtures.snapshot(
            shape = Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.LINES)),
            packageName = ENABLED_PKG,
            eventId = "evt-deferred-then-disabled",
        )
        h.pendingByPackage[ENABLED_PKG] = mutableListOf(legacy)
        h.lossDeferred += legacy.eventId

        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        coordinator.setSourceEnabled(ENABLED_PKG, false)

        awaitUntil { h.gaps.count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1 }
        h.lossClaimed shouldBe setOf("evt-deferred-then-disabled")
    }

    test("pending rows removed with their source are settled while their payload can still be read") {
        val h = Harness()
        // Round 36 agy I1. `removeSource` carries the same invariant as `setSourceEnabled` — settle
        // before the discard clears the payload — and had no test at all: agy deleted the settle
        // call outright and the whole suite still passed. Remove is the harder case of the two,
        // because nothing about it can be undone: the source is gone, so there is no later disable
        // to reach the rows, and a loss missed here is missed for good.
        val legacy = Fixtures.snapshot(
            shape = Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.LINES)),
            packageName = ENABLED_PKG,
            eventId = "evt-v013-removed",
        )
        h.pendingByPackage[ENABLED_PKG] = mutableListOf(legacy)
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()

        coordinator.removeSource(ENABLED_PKG, deleteData = false)

        // Reversing the two inside the remove transaction empties the pending rows first and this
        // gap is never written; deleting the settle call altogether does the same.
        h.gaps.count { it.reason == GapReason.MESSAGES_DROPPED.name && it.packageName == ENABLED_PKG } shouldBe 1
        h.lossClaimed shouldBe setOf("evt-v013-removed")
        // And the rows really are gone, which is what makes the moment above the last one.
        h.pendingByPackage[ENABLED_PKG] shouldBe null
    }

    test("rows that cannot settle do not starve a second source's rows either") {
        val h = Harness()
        // Round 36, Codex I1's fourth required case: fairness between sources. The page is not
        // per-source, so a source whose rows all refuse to settle fills it for everyone — the
        // shape `pendingExcluding` already exists to prevent for a paused source. A second source
        // capturing normally must not stop being replayed because the first one cannot settle.
        val other = "com.example.other"
        coEvery { h.sources.sources() } returns listOf(sourceConfig(ENABLED_PKG), sourceConfig(other))
        h.pendingReplay += (1..200).map { i ->
            "gen-old" to Fixtures.snapshot(
                shape = Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.LINES)),
                packageName = ENABLED_PKG,
                eventId = "evt-blocking-%03d".format(i),
                observedAt = 1_000L + i,
            )
        }
        h.pendingReplay += "gen-old" to Fixtures.snapshot(
            shape = Fixtures.messaging(conversationTitle = "Group") { message("Bo", "from the other app") },
            packageName = other,
            eventId = "evt-other-source",
            observedAt = 9_000L,
        )
        coEvery { h.ingest.isJournalPending(any()) } returns true
        h.gapWritesFail = true

        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        // The other source's event was stored in the same pass that deferred all two hundred.
        coVerify(timeout = 10_000, atLeast = 1) { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) }
        stillHolds {
            h.lossDeferred.size shouldBe 200
            coVerify(exactly = 0) { h.ingest.markJournalRetryable(any(), any(), any()) }
        }
    }

    test("a row another pass deferred is not committed by a batch that predates the deferral") {
        val h = Harness()
        // Round 37 Codex C1, and it is mine: the tri-state gave a `false` claim a second meaning.
        // It used to mean only "someone else already wrote this gap", which is why the caller could
        // ignore it and commit. A deferred row answers `false` too, and there the gap does *not*
        // exist — so committing on it stores the survivors and clears the payload that was the only
        // record of what they were missing. Reachable because a replay's page read is outside the
        // pipeline lock: one pass can defer a row while another still holds it in an older batch.
        val legacy = Fixtures.snapshot(
            shape = Fixtures.messaging(conversationTitle = "Group") { message("Ana", "the newest one") }
                .copy(truncated = setOf(TruncationFlag.LINES)),
            packageName = ENABLED_PKG,
            eventId = "evt-deferred-elsewhere",
        )
        var served = 0
        coEvery { h.ingest.pendingJournal(any(), any()) } coAnswers {
            if (served++ < 1) listOf("gen-old" to legacy) else emptyList()
        }
        coEvery { h.ingest.isJournalPending(any()) } returns true
        // Exactly what the repository answers for a row a concurrent pass has just deferred: no
        // exception, and no gap written.
        coEvery { h.ingest.claimEventLoss(any(), any()) } returns LossClaim.DEFERRED

        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        coVerify(timeout = 5_000, atLeast = 1) { h.ingest.claimEventLoss("evt-deferred-elsewhere", any()) }
        stillHolds {
            coVerify(exactly = 0) { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { h.ingest.markJournal("evt-deferred-elsewhere", any(), any()) }
            h.gaps.isEmpty() shouldBe true
        }
    }

    test("a replay that cannot even defer a row does not claim it made progress") {
        val h = Harness()
        // Round 37 subagent C2-b. The progress guard exists because a deferral that also fails
        // leaves the row exactly where it was: saying "the page moved on" then re-reads the same
        // page until the round limit — a hundred passes over the same rows for nothing.
        h.pendingReplay += "gen-old" to Fixtures.snapshot(
            shape = Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.LINES)),
            packageName = ENABLED_PKG,
            eventId = "evt-cannot-defer",
            observedAt = 1_000L,
        )
        coEvery { h.ingest.isJournalPending(any()) } returns true
        h.gapWritesFail = true
        h.deferralsFail = true

        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        coVerify(timeout = 5_000, atLeast = 1) { h.ingest.claimEventLoss("evt-cannot-defer", any()) }
        stillHolds {
            // One page read, and the pass stops. Claiming progress here would have read it a
            // hundred times; the row is still a candidate, so nothing moved.
            coVerify(atMost = 2) { h.ingest.pendingJournal(any(), any()) }
            h.lossDeferred.isEmpty() shouldBe true
            h.gaps.isEmpty() shouldBe true
        }
    }

    test("a failing prefix longer than a whole pass does not keep the rows behind it from ever being read") {
        val h = Harness()
        // Round 37 Codex I1, at the boundary that matters: `pageSize × roundLimit`. Putting the
        // deferred rows back at the *head* of every pass re-inserted the failing prefix in front of
        // everything each time, so the budget was spent on the same rows on every trigger and the
        // row behind them was never even selected. Six blockers at a page of two and a limit of
        // three rounds is the same shape as twenty thousand at 200 × 100.
        val coordinator = h.coordinator()
        coordinator.replayPageSize = 2
        coordinator.replayRounds = 3
        h.pendingReplay += (1..6).map { i ->
            "gen-old" to Fixtures.snapshot(
                shape = Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.LINES)),
                packageName = ENABLED_PKG,
                eventId = "evt-prefix-$i",
                observedAt = 1_000L + i,
            )
        }
        h.pendingReplay += "gen-old" to Fixtures.snapshot(
            shape = Fixtures.messaging(conversationTitle = "Group") { message("Ana", "behind the prefix") },
            packageName = ENABLED_PKG,
            eventId = "evt-tail",
            observedAt = 9_000L,
        )
        coEvery { h.ingest.isJournalPending(any()) } returns true
        h.gapWritesFail = true

        coordinator.onConnected(h.service)
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        // Pass one spends its three rounds on the prefix and never reaches the tail.
        awaitUntil { h.lossDeferred.size shouldBe 6 }
        stillHolds { coVerify(exactly = 0) { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) } }

        // A second trigger. The deferred prefix is no longer at the head, so the tail is the first
        // thing the page returns and it is committed — before the prefix is retried at the drain.
        coordinator.setPaused(true)
        coordinator.setPaused(false)

        coVerify(timeout = 10_000, atLeast = 1) { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    test("the pending rows of a source with more than one page are all settled before it is disabled") {
        val h = Harness()
        // Round 37 Codex I2 and subagent C2-a: the walk's `while` had no test that crossed a page
        // boundary through the real entry point, so mutating it to read one page only left the
        // whole suite green — while the discard that follows would clear every payload past the
        // first page, losing exactly the evidence this walk exists to keep.
        h.pendingByPackage[ENABLED_PKG] = (1..201).map { i ->
            Fixtures.snapshot(
                shape = Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.LINES)),
                packageName = ENABLED_PKG,
                eventId = "evt-page-%03d".format(i),
                observedAt = 1_000L + i,
            )
        }.toMutableList()

        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()

        coordinator.setSourceEnabled(ENABLED_PKG, false)

        // Every one of them, including the two hundred and first — and all before the discard,
        // which is what empties the list.
        h.lossClaimed.size shouldBe 201
        h.gaps.count { it.reason == GapReason.MESSAGES_DROPPED.name && it.packageName == ENABLED_PKG } shouldBe 201
        h.pendingByPackage[ENABLED_PKG] shouldBe null
    }

    test("two triggers arriving together run one replay pass, not two") {
        val h = Harness()
        // Round 37 Codex C1's structural half. A pass reads its page outside the pipeline lock, so
        // two passes overlapping is what lets one hold a batch the other has already changed. The
        // passes are coalesced instead: a caller that finds one running leaves it to that pass, and
        // the request is re-checked as the gate is released so nothing is dropped.
        val concurrent = java.util.concurrent.atomic.AtomicInteger()
        val maxConcurrent = java.util.concurrent.atomic.AtomicInteger()
        coEvery { h.ingest.pendingJournal(any(), any()) } coAnswers {
            val now = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { m -> maxOf(m, now) }
            try {
                kotlinx.coroutines.delay(50)
                emptyList()
            } finally {
                concurrent.decrementAndGet()
            }
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        coordinator.setPaused(true)
        coordinator.setPaused(false)

        awaitUntil { (maxConcurrent.get() >= 1) shouldBe true }
        stillHolds { maxConcurrent.get() shouldBe 1 }
    }
    // ---- Round 35 Codex I1: a whole row the parser proved was cut away is a loss the commit records ----

    /** A WhatsApp group body of exactly 4,096 characters ending on a line separator, plus a row that was cut away. */
    fun cutOnSeparator(eventId: String): CapturedNotification {
        val prefix = "Alice: " + "a".repeat(2037) + "\n" + "Bob: " + "b".repeat(2045) + "\n"
        val raw = prefix + "Carol: gone"
        return CapturedNotification(
            Fixtures.snapshot(Fixtures.bigText("Family", raw, bigText = raw), packageName = KnownSources.WHATSAPP, eventId = eventId),
            null,
        )
    }

    test("a whole row the parser proved was cut away is recorded as a gap handed to the commit") {
        val h = Harness()
        h.sourceList += sourceConfig(KnownSources.WHATSAPP)
        val recordedInsideCommit = java.util.concurrent.atomic.AtomicInteger()
        // The fake does what the repository does: runs the loss callback inside the commit. That
        // the real one does so in the same transaction is decided on a vault, in
        // JournalLossTransactionTest; this test decides that the coordinator hands it in at all,
        // and with the reason and scope the health page will show.
        coEvery { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<(suspend () -> Unit)?>(7)?.let { it(); recordedInsideCommit.incrementAndGet() }
            CommitOutcome(1L, listOf(1L, 2L), emptyList(), emptyList(), 0, false)
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(cutOnSeparator("evt-cut"))

        coVerify(timeout = 5_000, exactly = 1) {
            h.health.recordGap(any(), any(), GapReason.MESSAGES_DROPPED, GapPrecision.BOUNDED, any(), KnownSources.WHATSAPP)
        }
        awaitUntil { recordedInsideCommit.get() shouldBe 1 }
        // Not at acceptance: the snapshot carries no *_DROPPED flag, because the framework dropped
        // nothing — the parser is the only one who can see this loss.
        stillHolds { coVerify(exactly = 1) { h.health.recordGap(any(), any(), GapReason.MESSAGES_DROPPED, any(), any(), any()) } }
    }

    test("a cut inside the last row is that row's own flag, not a gap") {
        // The negative control for the test above: Bob lost his end and says so on his row;
        // whether a row followed him is not knowable, and the commit records nothing.
        val h = Harness()
        h.sourceList += sourceConfig(KnownSources.WHATSAPP)
        val committed = CompletableDeferred<Unit>()
        coEvery { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<(suspend () -> Unit)?>(7)?.invoke()
            committed.complete(Unit)
            CommitOutcome(1L, listOf(1L, 2L), emptyList(), emptyList(), 0, false)
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        val raw = "Alice: " + "a".repeat(2037) + "\n" + "Bob: " + "b".repeat(3000)
        coordinator.offerCaptured(CapturedNotification(Fixtures.snapshot(Fixtures.bigText("Family", raw, bigText = raw), packageName = KnownSources.WHATSAPP, eventId = "evt-inside"), null))

        withTimeout(5_000) { committed.await() }
        stillHolds { coVerify(exactly = 0) { h.health.recordGap(any(), any(), GapReason.MESSAGES_DROPPED, any(), any(), any()) } }
    }
    // ---- Issue #28: a row whose commit attempts run out is given up on with its loss recorded ----

    /** A row with content, so the parser yields a candidate and the replay reaches the commit. */
    fun pendingWithContent(eventId: String, observedAt: Long, pkg: String = ENABLED_PKG) =
        "gen-old" to Fixtures.snapshot(Fixtures.base(title = "A", text = "hi $eventId"), packageName = pkg, eventId = eventId, observedAt = observedAt)

    test("a row whose commit attempts run out is given up on with the loss of the whole event recorded, once") {
        val h = Harness()
        h.pendingReplay += pendingWithContent("evt-doomed", 1_000L)
        coEvery { h.ingest.isJournalPending(any()) } answers { firstArg<String>() !in h.exhausted }
        coEvery { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("disk")
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        // One attempt per pass: the row is charged and left, the pass finds no progress and ends.
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        awaitUntil { h.attempts["evt-doomed"] shouldBe 1 }
        coordinator.setPaused(true); coordinator.setPaused(false)
        awaitUntil { h.attempts["evt-doomed"] shouldBe 2 }
        stillHolds { h.gaps.none { it.reason == GapReason.COMMIT_FAILED.name } shouldBe true }

        // The third failure is the threshold: the record goes in with the filing.
        coordinator.setPaused(true); coordinator.setPaused(false)
        awaitUntil { h.exhausted shouldBe setOf("evt-doomed") }
        val record = h.gaps.single { it.reason == GapReason.COMMIT_FAILED.name }
        record.reason shouldBe GapReason.COMMIT_FAILED.name
        record.precision shouldBe GapPrecision.BOUNDED.name
        record.packageName shouldBe ENABLED_PKG
        record.startEpochMs shouldBe 950L
        record.endEpochMs shouldBe 1_000L

        // Given up on means given up on: no fourth attempt, no second record.
        coordinator.setPaused(true); coordinator.setPaused(false)
        stillHolds {
            coVerify(exactly = 3) { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) }
            h.gaps.count { it.reason == GapReason.COMMIT_FAILED.name } shouldBe 1
        }
    }

    test("a record that cannot be written parks the row, and the rows behind it are still reached") {
        val h = Harness()
        // Round 36 Codex I1's shape, one path further along: the row at the head of the page cannot
        // be committed *and* the record of giving up on it cannot be written. Left where it is, it
        // would hold the page and the clean row behind it would never be read.
        h.pendingReplay += pendingWithContent("evt-doomed", 1_000L)
        h.pendingReplay += pendingWithContent("evt-fine", 2_000L)
        h.attempts["evt-doomed"] = 2
        val committed = Collections.synchronizedSet(HashSet<String>())
        coEvery { h.ingest.isJournalPending(any()) } answers { val id = firstArg<String>(); id !in h.exhausted && id !in committed }
        coEvery { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val id = firstArg<NotificationSnapshot>().eventId
            if (id == "evt-doomed") throw IllegalStateException("disk")
            committed += id
            // A committed row has left PENDING, so the page no longer returns it — which is what
            // lets the pass drain and put the parked row back.
            synchronized(h.pendingReplay) { h.pendingReplay.removeAll { it.second.eventId == id } }
            CommitOutcome(1L, listOf(1L), emptyList(), emptyList(), 0, false)
        }
        h.gapWritesFail = true
        val coordinator = h.coordinator()
        coordinator.replayPageSize = 1
        coordinator.onConnected(h.service)

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        awaitUntil { h.lossDeferred shouldBe setOf("evt-doomed") }
        awaitUntil { committed shouldBe setOf("evt-fine") }
        stillHolds { h.gaps.isEmpty() shouldBe true }
        h.attempts["evt-doomed"] shouldBe 2

        // A gap write is next seen to succeed: the parked row is resumed and the commit tried once
        // more — still failing here, so this time it is given up on with its record.
        h.gapWritesFail = false
        coordinator.offerCaptured(capturedWithTruncation("evt-live", setOf(TruncationFlag.MESSAGES_DROPPED)))
        awaitUntil { h.exhausted shouldBe setOf("evt-doomed") }
        h.gaps.count { it.reason == GapReason.COMMIT_FAILED.name && it.packageName == ENABLED_PKG } shouldBe 1
    }

    test("a row that can neither be recorded nor parked is not counted as progress") {
        val h = Harness()
        // The negative control for the test above: the vault refuses the deferral too. The row is
        // still a candidate, and a pass that claimed progress anyway would re-read the same page
        // until its round limit (the shape of round 37 subagent C2-b).
        h.pendingReplay += pendingWithContent("evt-doomed", 1_000L)
        h.pendingReplay += pendingWithContent("evt-fine", 2_000L)
        h.attempts["evt-doomed"] = 2
        coEvery { h.ingest.isJournalPending(any()) } answers { firstArg<String>() !in h.exhausted }
        coEvery { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("disk")
        h.gapWritesFail = true
        h.deferralsFail = true
        val coordinator = h.coordinator()
        coordinator.replayPageSize = 1
        coordinator.onConnected(h.service)

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        // Charged once, not parked, and the pass ends there rather than spinning on the same page.
        awaitUntil { coVerify(exactly = 1) { h.ingest.markJournalRetryable("evt-doomed", any(), any()) } }
        stillHolds {
            h.lossDeferred.isEmpty() shouldBe true
            coVerify(exactly = 1) { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    test("the live entrance hands in the same record as the replay") {
        val h = Harness()
        // A live event's first failure is never the threshold, so the record is not written here —
        // but the callback the coordinator hands in is the one the repository would run, and it
        // must say COMMIT_FAILED for this source.
        val handedIn = CompletableDeferred<suspend () -> Unit>()
        coEvery { h.ingest.markJournalRetryable("evt-live-fail", any(), any()) } coAnswers {
            handedIn.complete(thirdArg())
            JournalRetry.RETRYABLE
        }
        coEvery { h.ingest.commit(any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("disk")
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(CapturedNotification(Fixtures.snapshot(Fixtures.base(title = "A", text = "hi"), packageName = ENABLED_PKG, eventId = "evt-live-fail"), null))

        val loss = withTimeout(5_000) { handedIn.await() }
        h.gaps.isEmpty() shouldBe true
        loss()
        val record = h.gaps.single()
        record.reason shouldBe GapReason.COMMIT_FAILED.name
        record.precision shouldBe GapPrecision.BOUNDED.name
        record.packageName shouldBe ENABLED_PKG
    }
})
