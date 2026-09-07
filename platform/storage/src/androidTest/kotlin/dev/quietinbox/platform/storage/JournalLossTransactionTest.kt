package dev.quietinbox.platform.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.model.GapPrecision
import dev.quietinbox.core.model.GapReason
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.repo.JournalCursor
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A loss the event arrived with is committed with the event or not at all (round 33 cluster a).
 *
 * The coordinator's tests can only show that it hands the loss to the repository: their journal
 * stub is a fake that invokes the callback itself, so the `if (accepted)` guard and the transaction
 * around it are the fake's behaviour there, not the code's (round 34 I1). Both are decided here, on
 * a real vault.
 *
 * The second half of the file is the same question for an event that arrived before any of this
 * existed (round 34 C2). A row a release up to 0.1.3 left pending never goes through `journal()` at
 * all, so nothing wrote its loss; the claim is what lets exactly one of the two ways out of PENDING
 * write it now. That claim is a conditional UPDATE, so it too is only really decided here.
 */
@RunWith(AndroidJUnit4::class)
class JournalLossTransactionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keys: KeyMaterial
    private lateinit var holder: DatabaseHolder
    private lateinit var ingest: IngestRepository
    private lateinit var health: HealthRepository

    private val pkg = "com.example.chat"

    @Before
    fun setUp() {
        wipe()
        keys = KeyMaterial(context)
        holder = DatabaseHolder(context, keys)
        ingest = IngestRepository(holder)
        health = HealthRepository(holder)
    }

    @After
    fun tearDown() = runBlocking {
        holder.closeAndDeleteFiles()
        wipe()
    }

    private fun wipe() {
        File(context.filesDir, "keys").deleteRecursively()
        File(context.filesDir, "media").deleteRecursively()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            File(context.getDatabasePath("quietinbox.vault").path + suffix).delete()
        }
    }

    private suspend fun ready() = withTimeout(20_000) { holder.state.filterIsInstance<VaultState.Ready>().first() }

    private fun snapshot(eventId: String) =
        Fixtures.snapshot(Fixtures.base(title = "t", text = "b"), packageName = pkg, eventId = eventId)

    private fun snapshotAt(eventId: String, observedAt: Long) =
        Fixtures.snapshot(Fixtures.base(title = "t", text = "b"), packageName = pkg, eventId = eventId, observedAt = observedAt)

    private suspend fun allGaps() = holder.db().healthDao().observeGaps(100).first()

    private suspend fun recordLoss() {
        health.recordGap(1_000, 2_000, GapReason.MESSAGES_DROPPED, GapPrecision.BOUNDED, 2_000, pkg)
    }

    @Test
    fun acceptingAnEventWritesItsLossWithIt() = runBlocking {
        ready()

        ingest.journal(snapshot("evt-1"), "gen", 60_000) { recordLoss() } shouldBe true

        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
        Unit
    }

    @Test
    fun redeliveringTheSameEventDoesNotRecordItsLossTwice() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-2"), "gen", 60_000) { recordLoss() } shouldBe true

        // eventId is the journal's primary key and the insert ignores conflicts, so the second
        // delivery changes nothing — which is what makes the loss exactly-once without a column
        // of its own. This is the guard the coordinator's fake was standing in for.
        ingest.journal(snapshot("evt-2"), "gen", 60_000) { recordLoss() } shouldBe false

        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
        Unit
    }

    @Test
    fun aLossCarriedInFromAnOlderReleaseIsRecordedByExactlyOneClaim() = runBlocking {
        ready()
        // A row as an older release left it: journalled with no loss recorded, because that
        // release recorded none.
        ingest.journal(snapshot("evt-legacy"), "gen", 60_000) shouldBe true

        ingest.claimEventLoss("evt-legacy") { recordLoss() } shouldBe true
        // Every later pass — a replay that comes round again, or the discard that follows a
        // disabled source — finds the claim spent. This is the whole idempotency boundary: without
        // it a row replayed n times lists one loss n times on the health page.
        ingest.claimEventLoss("evt-legacy") { recordLoss() } shouldBe false
        ingest.claimEventLoss("evt-legacy") { recordLoss() } shouldBe false

        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
        Unit
    }

    @Test
    fun aClaimWhoseGapCannotBeWrittenIsNotSpent() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-legacy-2"), "gen", 60_000) shouldBe true

        runCatching {
            ingest.claimEventLoss("evt-legacy-2") { error("the gap write failed") }
        }.isFailure shouldBe true

        // The claim and the gap are one transaction, so a failed write leaves the row exactly as
        // it was and the next pass can still record what it lost. Marking it settled here would
        // lose the loss for good — there is no second copy of the evidence.
        allGaps().isEmpty() shouldBe true
        // Deferred, though, which is what takes it off the page it cannot make progress on; the
        // two passes that read pending rows resume before they read, and only then can it be
        // claimed. A claim that reached a deferred row directly would be a pass that had not
        // decided whether the refusal still holds.
        ingest.claimEventLoss("evt-legacy-2") { recordLoss() } shouldBe false
        ingest.resumeDeferredSettlements() shouldBe 1
        ingest.claimEventLoss("evt-legacy-2") { recordLoss() } shouldBe true
        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
        Unit
    }

    @Test
    fun anEventThisReleaseAcceptedIsAlreadySettled() = runBlocking {
        ready()
        // Accepted with its loss, the way this release does it.
        ingest.journal(snapshot("evt-modern"), "gen", 60_000) { recordLoss() } shouldBe true

        // The upgrade path must not touch it. The insert set the column, so this cannot depend on
        // reading the payload's flags right — which is what makes a new row and an old one carrying
        // the same flags distinguishable at all.
        ingest.claimEventLoss("evt-modern") { recordLoss() } shouldBe false

        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
        Unit
    }

    @Test
    fun aRowThatHasAlreadyLeftPendingCannotBeClaimed() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-settled"), "gen", 60_000) shouldBe true
        ingest.markJournal("evt-settled", "COMMITTED")

        // Its payload was cleared when it left PENDING, so anything claiming it now is working
        // from a snapshot decoded before that — too late to be recording anything about it.
        ingest.claimEventLoss("evt-settled") { recordLoss() } shouldBe false

        allGaps().isEmpty() shouldBe true
        Unit
    }

    @Test
    fun anEventWhoseLossCannotBeWrittenIsNotAcceptedEither() = runBlocking {
        ready()

        // If the gap write fails the journal row must go with it: an accepted event whose loss was
        // silently dropped is the exact shape of the defect this release exists to remove. The
        // caller then has no row to retry and records the loss less precisely instead.
        runCatching {
            ingest.journal(snapshot("evt-3"), "gen", 60_000) { error("the gap write failed") }
        }.isFailure shouldBe true

        ingest.isJournalPending("evt-3") shouldBe false
        allGaps().isEmpty() shouldBe true
        Unit
    }

    /**
     * Round 35, Codex C1 / subagent C2, on a real vault: settling a carried-over loss must never
     * cost the event its commit attempts. Three failed settlements leave the row exactly as it was
     * — still pending, payload readable — so the next pass still has the evidence. Since round 36
     * they also leave it deferred, which costs it its place on the page and nothing else.
     */
    @Test
    fun aSettlementThatKeepsFailingNeverCostsTheEventItsAttempts() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-retry"), "gen", 60_000) shouldBe true

        // Three passes, as the coordinator makes them: each resumes what the last one deferred and
        // tries again. A row already deferred is not retried inside a pass — the claim finds
        // nothing to take and returns false without touching the vault — so one attempt per pass
        // is the whole cost, and it is spent on bookkeeping, never on the event's three commits.
        repeat(3) { pass ->
            withClue("pass $pass") {
                ingest.resumeDeferredSettlements() shouldBe if (pass == 0) 0 else 1
                runCatching { ingest.claimEventLoss("evt-retry") { error("the gap write failed") } }.isFailure shouldBe true
                ingest.claimEventLoss("evt-retry") { recordLoss() } shouldBe false
                ingest.isJournalPending("evt-retry") shouldBe true
                allGaps().isEmpty() shouldBe true
            }
        }

        // The evidence is still readable after all of them, which is the point: three failures cost
        // it its place on the page and nothing else.
        ingest.resumeDeferredSettlements() shouldBe 1
        ingest.pendingJournalForPackage(pkg).snapshots.map { it.eventId } shouldBe listOf("evt-retry")

        // And when it can finally be written, it is written once.
        ingest.claimEventLoss("evt-retry") { recordLoss() } shouldBe true
        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
        Unit
    }

    /**
     * The shape that would have happened had the settlement stayed inside the event's retry budget,
     * reproduced deliberately so the pre-existing hole it belongs to is on record: a row whose
     * commit attempts run out is filed FAILED with its payload cleared, and nothing anywhere says
     * what it lost. That hole is older than this release and is issue #28; what this release
     * guarantees is only that a *settlement* failure can no longer be what drives a row into it.
     */
    @Test
    fun aRowWhoseCommitAttemptsRunOutLosesItsEvidenceAndSaysNothing() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-exhausted"), "gen", 60_000) shouldBe true

        repeat(3) { ingest.markJournalRetryable("evt-exhausted", "REPLAY_IllegalStateException") }

        ingest.isJournalPending("evt-exhausted") shouldBe false
        // The payload is gone, so the claim can no longer be taken and no gap can ever be written.
        ingest.pendingJournalForPackage(pkg).snapshots.isEmpty() shouldBe true
        ingest.claimEventLoss("evt-exhausted") { recordLoss() } shouldBe false
        allGaps().isEmpty() shouldBe true
        Unit
    }

    /**
     * Round 36, subagent C1: the walk itself, not the loop that drives it.
     *
     * Two ways to get the paging wrong are silent — ordering by the timestamp alone, and building
     * the cursor from the decoded snapshots rather than from the rows — and both lose whole rows
     * rather than failing. Three of these seven rows share a millisecond, which is what a batched
     * re-post looks like, so a walk that leaves their order to the query plan cannot get the same
     * answer at every page size. Every page size that divides the seven differently is walked, and
     * the one that equals the row count is included so the page-exactly-full case runs too.
     */
    @Test
    fun theSettleWalkVisitsEveryPendingRowOfTheSourceOnceInOrder() = runBlocking {
        ready()
        val rows = listOf(
            "evt-a" to 100L, "evt-b" to 100L, "evt-c" to 100L,
            "evt-d" to 200L,
            "evt-e" to 300L, "evt-f" to 300L,
            "evt-g" to 400L,
        )
        for ((id, at) in rows) ingest.journal(snapshotAt(id, at), "gen", 60_000) shouldBe true
        val all = rows.map { it.first }

        for (limit in listOf(1, 2, 3, 6, 7, 8)) {
            val visited = mutableListOf<String>()
            var after = JournalCursor.START
            var pages = 0
            while (pages++ <= all.size + 1) {
                val page = ingest.pendingJournalForPackage(pkg, after, limit)
                visited += page.snapshots.map { it.eventId }
                after = page.next ?: break
            }
            withClue("limit = $limit") { visited shouldBe all }
        }
        Unit
    }

    /**
     * A whole page that will not decode still moves the walk forward.
     *
     * The cursor is built from the rows, not from what decoded out of them, and this is the case
     * that tells the two apart: a page whose payloads are all unreadable yields no snapshots, so a
     * cursor taken from the snapshots has nothing to advance to and the walk stops with the rest of
     * the source unvisited. Undecodable rows are skipped rather than failed here on purpose — the
     * discard about to run will take them anyway, and marking a payload unreadable belongs to the
     * replay — but skipping them may not cost the readable rows behind them (round 36 Codex I3).
     */
    @Test
    fun aPageThatWillNotDecodeStillMovesTheWalkOn() = runBlocking {
        ready()
        val ids = listOf("evt-p1", "evt-p2", "evt-p3", "evt-p4", "evt-p5")
        for ((i, id) in ids.withIndex()) ingest.journal(snapshotAt(id, 100L * (i + 1)), "gen", 60_000) shouldBe true
        // The whole of the second page, at this page size.
        for (id in listOf("evt-p2", "evt-p3")) {
            holder.db().openHelper.writableDatabase
                .execSQL("UPDATE event_journal SET payload = 'not json at all' WHERE eventId = ?", arrayOf<Any>(id))
        }

        val visited = mutableListOf<String>()
        var after = JournalCursor.START
        var pages = 0
        while (pages++ <= ids.size + 1) {
            val page = ingest.pendingJournalForPackage(pkg, after, limit = 2)
            visited += page.snapshots.map { it.eventId }
            after = page.next ?: break
        }
        visited shouldBe listOf("evt-p1", "evt-p4", "evt-p5")
        Unit
    }

    /**
     * Round 36, Codex I2: the query plan, measured rather than assumed.
     *
     * The walk runs inside the source policy transaction, holding the pipeline lock, so its cost
     * is paid by live capture. Codex measured the previous shape re-reading every pending row of
     * the source once per page — 3.8M VM instructions over 8,000 rows against 128k for a single
     * unpaged read — because no index covered the source, and the sort was a temp B-tree.
     */
    @Test
    fun theSettleWalkSeeksToItsCursorInsideTheIndex() = runBlocking {
        ready()
        val sql = """
            EXPLAIN QUERY PLAN
            SELECT * FROM event_journal
            WHERE state = 'PENDING' AND packageName = ? AND lossRecorded = 0
              AND (receivedAtEpochMs, eventId) > (?, ?)
            ORDER BY receivedAtEpochMs, eventId LIMIT ?
        """.trimIndent()
        val plan = holder.db().openHelper.writableDatabase
            .query(sql, arrayOf<Any>(pkg, 100L, "evt-a", 200))
            .use { c ->
                buildString { while (c.moveToNext()) appendLine(c.getString(c.columnCount - 1)) }
            }

        plan shouldContain "index_event_journal_packageName_state_lossRecorded_receivedAtEpochMs_eventId"
        // The two findings, each named: a scan reads rows the cursor has already passed, and a
        // temp B-tree means the index did not supply the order and every candidate was sorted.
        withClue(plan) {
            plan.contains("SCAN event_journal") shouldBe false
            plan.contains("TEMP B-TREE") shouldBe false
        }
        Unit
    }

    /**
     * Round 36, Codex I1: a settlement that cannot be written leaves the row deferred, not pending.
     *
     * Deferred is a state of its own precisely because the other two are both wrong here: charging
     * the failure to the event's three commit attempts files it FAILED and clears the payload
     * (round 35), and leaving it in the replay's candidate set puts it at the head of every page
     * for ever. The payload and the unspent claim survive either way — that part is round 35's
     * guarantee and is asserted again here, because the deferral must not weaken it.
     */
    @Test
    fun aSettlementThatCannotBeWrittenLeavesTheRowDeferredAndItsEvidenceIntact() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-defer"), "gen", 60_000) shouldBe true

        runCatching { ingest.claimEventLoss("evt-defer") { error("the gap write failed") } }.isFailure shouldBe true

        // Still pending, still holding its payload, still unclaimed: nothing was given up.
        ingest.isJournalPending("evt-defer") shouldBe true
        allGaps().isEmpty() shouldBe true
        // But out of the replay's candidate set, which is the whole point: it may not hold a place
        // on the page while it cannot make progress.
        ingest.isReplayCandidate("evt-defer") shouldBe false
        ingest.pendingJournal().map { it.second.eventId } shouldBe emptyList()

        // And back in it when a pass resumes, with the claim still there to win.
        ingest.resumeDeferredSettlements() shouldBe 1
        ingest.isReplayCandidate("evt-defer") shouldBe true
        ingest.pendingJournal().map { it.second.eventId } shouldBe listOf("evt-defer")
        ingest.claimEventLoss("evt-defer") { recordLoss() } shouldBe true
        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
        Unit
    }

    /**
     * The starvation itself, at the boundary Codex named: rows that cannot settle must not keep a
     * row behind them from ever being replayed.
     *
     * Three rows fail to settle and one, journalled with its loss already recorded, has nothing to
     * settle at all. Before the deferral the three sat at the head of every page; here the first
     * pass takes them out and the fourth row is what a second read returns.
     */
    @Test
    fun rowsThatCannotSettleStopBlockingTheRowsBehindThem() = runBlocking {
        ready()
        for (i in 1..3) ingest.journal(snapshotAt("evt-blocked-$i", 100L + i), "gen", 60_000) shouldBe true
        ingest.journal(snapshotAt("evt-behind", 500L), "gen", 60_000) { recordLoss() } shouldBe true

        ingest.pendingJournal(limit = 3).map { it.second.eventId } shouldBe
            listOf("evt-blocked-1", "evt-blocked-2", "evt-blocked-3")

        for (i in 1..3) {
            runCatching { ingest.claimEventLoss("evt-blocked-$i") { error("the gap write failed") } }.isFailure shouldBe true
        }

        // The same page-sized read now reaches the row that was behind them.
        ingest.pendingJournal(limit = 3).map { it.second.eventId } shouldBe listOf("evt-behind")
        // And the blocked rows are still there, whole, waiting for a pass that resumes them.
        for (i in 1..3) ingest.isJournalPending("evt-blocked-$i") shouldBe true
        ingest.resumeDeferredSettlements() shouldBe 3
        ingest.pendingJournal(limit = 3).map { it.second.eventId } shouldBe
            listOf("evt-blocked-1", "evt-blocked-2", "evt-blocked-3")
        Unit
    }

    /**
     * A deferred row is still one the settle walk must see, because the discard that follows a
     * disabled source clears its payload for good: the walk resumes deferred rows before reading.
     * Without that, the `lossRecorded = 0` filter would make the one path that still holds the
     * evidence the one path that skipped it.
     */
    @Test
    fun aDeferredRowIsStillSettledBeforeItsSourceIsDiscarded() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-defer-discard"), "gen", 60_000) shouldBe true
        runCatching { ingest.claimEventLoss("evt-defer-discard") { error("the gap write failed") } }.isFailure shouldBe true

        // As the walk sees it before resuming: nothing to read, and the discard would take it.
        ingest.pendingJournalForPackage(pkg).snapshots.map { it.eventId } shouldBe emptyList()

        ingest.resumeDeferredSettlements() shouldBe 1
        ingest.pendingJournalForPackage(pkg).snapshots.map { it.eventId } shouldBe listOf("evt-defer-discard")
        Unit
    }

    /**
     * A row this release accepted with its loss is not walked at all: it settled with the insert.
     * The filter is what keeps a long-lived source's settled rows from being decoded once per
     * disable, and it must not swallow a row that still has something to settle.
     */
    @Test
    fun theSettleWalkSkipsRowsWhoseLossIsAlreadyRecorded() = runBlocking {
        ready()
        ingest.journal(snapshotAt("evt-settled-1", 100L), "gen", 60_000) { recordLoss() } shouldBe true
        ingest.journal(snapshotAt("evt-carried", 200L), "gen", 60_000) shouldBe true
        ingest.journal(snapshotAt("evt-settled-2", 300L), "gen", 60_000) { recordLoss() } shouldBe true

        ingest.pendingJournalForPackage(pkg).snapshots.map { it.eventId } shouldBe listOf("evt-carried")
        Unit
    }
}
