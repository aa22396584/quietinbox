package dev.quietinbox.platform.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.model.GapPrecision
import dev.quietinbox.core.model.GapReason
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.core.reconcile.Reconciler
import dev.quietinbox.core.parser.StandardParser
import dev.quietinbox.core.identity.IdentityResolver
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.db.PENDING_FOR_PACKAGE_AFTER
import dev.quietinbox.platform.storage.repo.JournalCursor
import dev.quietinbox.platform.storage.repo.LossClaim
import dev.quietinbox.platform.storage.db.LOSS_UNSETTLED
import dev.quietinbox.platform.storage.db.LOSS_SETTLED
import dev.quietinbox.platform.storage.db.LOSS_DEFERRED_SETTLED
import dev.quietinbox.platform.storage.db.LOSS_DEFERRED
import dev.quietinbox.platform.storage.repo.JournalRetry
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    private suspend fun messageCount(): Int =
        holder.db().openHelper.writableDatabase.query("SELECT COUNT(*) FROM message").use { it.moveToFirst(); it.getInt(0) }

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

        ingest.claimEventLoss("evt-legacy") { recordLoss() } shouldBe LossClaim.RECORDED
        // Every later pass — a replay that comes round again, or the discard that follows a
        // disabled source — finds the claim spent. This is the whole idempotency boundary: without
        // it a row replayed n times lists one loss n times on the health page.
        ingest.claimEventLoss("evt-legacy") { recordLoss() } shouldBe LossClaim.ALREADY_RECORDED
        ingest.claimEventLoss("evt-legacy") { recordLoss() } shouldBe LossClaim.ALREADY_RECORDED

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
        ingest.claimEventLoss("evt-legacy-2") { recordLoss() } shouldBe LossClaim.DEFERRED
        ingest.resumeDeferredSettlements() shouldBe 1
        ingest.claimEventLoss("evt-legacy-2") { recordLoss() } shouldBe LossClaim.RECORDED
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
        ingest.claimEventLoss("evt-modern") { recordLoss() } shouldBe LossClaim.ALREADY_RECORDED

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
        ingest.claimEventLoss("evt-settled") { recordLoss() } shouldBe LossClaim.NOT_PENDING

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
                ingest.claimEventLoss("evt-retry") { recordLoss() } shouldBe LossClaim.DEFERRED
                ingest.isJournalPending("evt-retry") shouldBe true
                allGaps().isEmpty() shouldBe true
            }
        }

        // The evidence is still readable after all of them, which is the point: three failures cost
        // it its place on the page and nothing else.
        ingest.resumeDeferredSettlements() shouldBe 1
        ingest.pendingJournalForPackage(pkg).snapshots.map { it.eventId } shouldBe listOf("evt-retry")

        // And when it can finally be written, it is written once.
        ingest.claimEventLoss("evt-retry") { recordLoss() } shouldBe LossClaim.RECORDED
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
        // The whole of the second page *as the correct paging produces it*: raw pages at limit 2
        // are [p1,p2], [p3,p4], [p5]. Corrupting p2 and p3 instead leaves every correct page with
        // something decodable in it, so a walk that stops on an empty page would still pass
        // (round 37 Codex I4).
        for (id in listOf("evt-p3", "evt-p4")) {
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
        visited shouldBe listOf("evt-p1", "evt-p2", "evt-p5")
        Unit
    }

    /**
     * The same page, stated directly: it yields nothing and still says there is more.
     *
     * This is the property the walk depends on, and asserting only the visited list leaves it to
     * inference — the list would also be explained by the page never having been read.
     */
    @Test
    fun aPageOfUndecodableRowsIsEmptyAndStillPointsOn() = runBlocking {
        ready()
        val ids = listOf("evt-q1", "evt-q2", "evt-q3", "evt-q4", "evt-q5")
        for ((i, id) in ids.withIndex()) ingest.journal(snapshotAt(id, 100L * (i + 1)), "gen", 60_000) shouldBe true
        for (id in listOf("evt-q3", "evt-q4")) {
            holder.db().openHelper.writableDatabase
                .execSQL("UPDATE event_journal SET payload = 'not json at all' WHERE eventId = ?", arrayOf<Any>(id))
        }

        val first = ingest.pendingJournalForPackage(pkg, JournalCursor.START, limit = 2)
        first.snapshots.map { it.eventId } shouldBe listOf("evt-q1", "evt-q2")
        val second = ingest.pendingJournalForPackage(pkg, first.next!!, limit = 2)
        second.snapshots.isEmpty() shouldBe true
        // Non-null although nothing decoded: the cursor comes from the rows, so the page after it
        // is still reachable.
        second.next shouldNotBe null
        ingest.pendingJournalForPackage(pkg, second.next!!, limit = 2).snapshots.map { it.eventId } shouldBe listOf("evt-q5")
        Unit
    }

    /**
     * Round 37 Codex C1, at the layer that decides it: a claim that takes nothing says *why*.
     *
     * Before this, both "the gap is already on disk" and "the row is deferred and no gap exists"
     * came back as `false`, and the caller could not tell a settled row from one whose evidence
     * still had to be written. Committing on the second is how the payload was lost.
     */
    @Test
    fun aClaimSaysWhetherTheGapIsOnDiskOrOnlyThatItTookNothing() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-claim-kind"), "gen", 60_000) shouldBe true

        // Deferred: the gap is not written, and the claim must not be read as success.
        runCatching { ingest.claimEventLoss("evt-claim-kind") { error("the gap write failed") } }.isFailure shouldBe true
        ingest.claimEventLoss("evt-claim-kind") { recordLoss() } shouldBe LossClaim.DEFERRED
        allGaps().isEmpty() shouldBe true

        // Recorded, then already recorded: both mean the gap is on disk.
        ingest.resumeDeferredSettlements() shouldBe 1
        ingest.claimEventLoss("evt-claim-kind") { recordLoss() } shouldBe LossClaim.RECORDED
        ingest.claimEventLoss("evt-claim-kind") { recordLoss() } shouldBe LossClaim.ALREADY_RECORDED
        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1

        // And a row that has left PENDING owes nothing: its payload is gone.
        ingest.journal(snapshot("evt-claim-gone"), "gen", 60_000) shouldBe true
        ingest.markJournal("evt-claim-gone", "COMMITTED")
        ingest.claimEventLoss("evt-claim-gone") { recordLoss() } shouldBe LossClaim.NOT_PENDING
        Unit
    }

    /**
     * Round 37, agy and the subagent independently: the walk's resume is one source's business.
     *
     * It runs inside that source's policy transaction. A global reset there puts another app's
     * deferred rows back — and takes them away again if the transaction rolls back — so a source
     * being switched off silently changes the replay schedule of every other source on the device.
     */
    @Test
    fun resumingOneSourcesDeferredRowsLeavesAnothersAlone() = runBlocking {
        ready()
        val other = "com.example.other"
        ingest.journal(snapshot("evt-mine"), "gen", 60_000) shouldBe true
        ingest.journal(
            Fixtures.snapshot(Fixtures.base(title = "t", text = "b"), packageName = other, eventId = "evt-theirs"),
            "gen",
            60_000,
        ) shouldBe true
        for (id in listOf("evt-mine", "evt-theirs")) {
            runCatching { ingest.claimEventLoss(id) { error("the gap write failed") } }.isFailure shouldBe true
        }
        ingest.isReplayCandidate("evt-mine") shouldBe false
        ingest.isReplayCandidate("evt-theirs") shouldBe false

        ingest.resumeDeferredSettlements(pkg) shouldBe 1

        ingest.isReplayCandidate("evt-mine") shouldBe true
        ingest.isReplayCandidate("evt-theirs") shouldBe false
        Unit
    }

    /**
     * Round 37 subagent C2-a: the deferral may not walk back a settled row.
     *
     * `deferLoss` carries `lossRecorded = 0` for the same reason the claim does. Through
     * `claimEventLoss` the predicate is unreachable — the catch runs only when the claim had won,
     * so the row is at 0 there — which is exactly why it is pinned at this layer instead: the
     * guard is one line, and without a test nothing would notice it going.
     */
    @Test
    fun aSettledRowCannotBeWalkedBackToDeferred() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-settled-defer"), "gen", 60_000) { recordLoss() } shouldBe true

        holder.db().journalDao().deferLoss("evt-settled-defer") shouldBe 0
        // Still settled, and still out of reach of a second claim — a downgrade to 2 would let a
        // later pass resume it and record the same loss a second time.
        ingest.isReplayCandidate("evt-settled-defer") shouldBe true
        ingest.claimEventLoss("evt-settled-defer") { recordLoss() } shouldBe LossClaim.ALREADY_RECORDED
        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
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
        // The DAO's own statement, not a copy of it: a copy keeps its pretty plan while the real
        // one degrades. Room's `:name` bindings become positional `?` and nothing else changes.
        val sql = "EXPLAIN QUERY PLAN " + PENDING_FOR_PACKAGE_AFTER.replace(Regex(":\\w+"), "?")
        val plan = holder.db().openHelper.writableDatabase
            .query(sql, arrayOf<Any>(pkg, 100L, "evt-a", 200))
            .use { c ->
                buildString { while (c.moveToNext()) appendLine(c.getString(c.columnCount - 1)) }
            }

        withClue(plan) {
            plan shouldContain "index_event_journal_packageName_state_lossRecorded_receivedAtEpochMs_eventId"
            // The cursor itself has to be part of the index range, not a filter applied after it.
            // Asserting only the index name and the absence of a scan lets a statement through that
            // seeks on the three equality columns and then re-reads every row the cursor has already
            // passed — correct, and quadratic (round 37 Codex I3).
            plan shouldContain "(receivedAtEpochMs,eventId)>(?,?)"
            // A scan reads rows the cursor has passed; a temp B-tree means the index did not supply
            // the order and every candidate was sorted.
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
        ingest.claimEventLoss("evt-defer") { recordLoss() } shouldBe LossClaim.RECORDED
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
    // ---- Round 35 Codex I1: a loss the parser proved is committed with the batch or not at all ----

    private fun groupBody(eventId: String) =
        Fixtures.snapshot(Fixtures.bigText("Family", "Alice: hi\nBob: hello", bigText = "Alice: hi\nBob: hello"), packageName = pkg, eventId = eventId)

    private suspend fun commitWithLoss(snapshot: dev.quietinbox.core.model.NotificationSnapshot, withIdentity: Boolean, loss: suspend () -> Unit) {
        val parser = StandardParser()
        val batch = parser.parse(snapshot)
        val id = if (withIdentity) IdentityResolver().resolve(snapshot, batch) else null
        val r = id?.let { Reconciler().reconcile(snapshot.notificationKey, batch.messages, ingest.checkpoint(it.streamKey), lookupById = { null }) }
        ingest.commit(snapshot, batch, id, r, "gen", null, mediaAllowed = false, lossOnCommit = loss)
    }

    @Test
    fun aLossTheParserProvedIsWrittenInTheCommitThatStoresTheBatch() = runBlocking {
        ready()
        val s = groupBody("evt-cut")
        ingest.journal(s, "gen", 60_000) shouldBe true

        commitWithLoss(s, withIdentity = true) { recordLoss() }

        ingest.isJournalPending("evt-cut") shouldBe false
        messageCount() shouldBe 1
        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
        Unit
    }

    /** The exit that stores nothing — no identity, so no messages — still leaves PENDING, and must still record. */
    @Test
    fun aLossTheParserProvedIsWrittenOnTheCommitExitThatStoresNothingToo() = runBlocking {
        ready()
        val s = groupBody("evt-cut-empty")
        ingest.journal(s, "gen", 60_000) shouldBe true

        commitWithLoss(s, withIdentity = false) { recordLoss() }

        ingest.isJournalPending("evt-cut-empty") shouldBe false
        messageCount() shouldBe 0
        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
        Unit
    }

    /** The write the record depends on fails: nothing is stored, the row stays PENDING, the replay gets another go. */
    @Test
    fun aCommitWhoseLossCannotBeWrittenStoresNothingAndStaysPending() = runBlocking {
        ready()
        val s = groupBody("evt-cut-refused")
        ingest.journal(s, "gen", 60_000) shouldBe true

        runCatching { commitWithLoss(s, withIdentity = true) { error("the gap write failed") } }.isFailure shouldBe true

        withClue("the batch must not be stored without its record") { messageCount() shouldBe 0 }
        withClue("the row is the replay's to retry") { ingest.isJournalPending("evt-cut-refused") shouldBe true }
        allGaps().isEmpty() shouldBe true
        Unit
    }
    // ---- Issue #28: a row whose commit attempts run out is given up on with its loss recorded ----

    private suspend fun commitFailure() {
        health.recordGap(1_000, 2_000, GapReason.COMMIT_FAILED, GapPrecision.BOUNDED, 2_000, pkg)
    }

    private suspend fun failCommit(eventId: String, loss: suspend () -> Unit = { commitFailure() }) =
        ingest.markJournalRetryable(eventId, "REPLAY_IllegalStateException", loss)

    private suspend fun lossState(eventId: String) = holder.db().journalDao().pendingLossState(eventId)
    private suspend fun attempts(eventId: String) = holder.db().journalDao().attempts(eventId)
    private suspend fun pendingIds() = ingest.pendingJournal().map { it.second.eventId }
    private suspend fun sql(statement: String) = holder.db().openHelper.writableDatabase.execSQL(statement)

    @Test
    fun anExhaustedRowRecordsTheWholeEventWithItsFilingAndOnlyOnce() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-exhausted"), "gen", 60_000) shouldBe true

        failCommit("evt-exhausted") shouldBe JournalRetry.RETRYABLE
        failCommit("evt-exhausted") shouldBe JournalRetry.RETRYABLE
        allGaps().isEmpty() shouldBe true
        failCommit("evt-exhausted") shouldBe JournalRetry.FAILED_RECORDED

        ingest.isJournalPending("evt-exhausted") shouldBe false
        pendingIds() shouldBe emptyList()
        val record = allGaps().single()
        record.reason shouldBe GapReason.COMMIT_FAILED.name
        record.packageName shouldBe pkg
        // Given up on is terminal: a later charge finds nothing to charge and writes nothing.
        failCommit("evt-exhausted") shouldBe JournalRetry.NOT_PENDING
        allGaps().size shouldBe 1
        Unit
    }

    @Test
    fun aRecordThatCannotBeWrittenLeavesTheRowPendingAndParksItUntilAPassResumesIt() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-parked"), "gen", 60_000) shouldBe true
        failCommit("evt-parked"); failCommit("evt-parked")

        failCommit("evt-parked") { error("the gap write failed") } shouldBe JournalRetry.FAILED_DEFERRED

        withClue("the rollback kept the row, its attempts and its payload") {
            ingest.isJournalPending("evt-parked") shouldBe true
            attempts("evt-parked") shouldBe 2
            lossState("evt-parked") shouldBe LOSS_DEFERRED
        }
        withClue("parked means out of the page, not out of the vault") {
            pendingIds() shouldBe emptyList()
            ingest.isReplayCandidate("evt-parked") shouldBe false
        }
        allGaps().isEmpty() shouldBe true

        ingest.resumeDeferredSettlements() shouldBe 1
        lossState("evt-parked") shouldBe LOSS_UNSETTLED
        pendingIds() shouldBe listOf("evt-parked")
        // The commit is tried once more; still failing, the record goes in this time.
        failCommit("evt-parked") shouldBe JournalRetry.FAILED_RECORDED
        allGaps().single().reason shouldBe GapReason.COMMIT_FAILED.name
        Unit
    }

    @Test
    fun aRowWhoseArrivalLossIsSettledIsParkedAndResumedWithItStillSettled() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-settled"), "gen", 60_000) { recordLoss() } shouldBe true
        lossState("evt-settled") shouldBe LOSS_SETTLED
        failCommit("evt-settled"); failCommit("evt-settled")

        failCommit("evt-settled") { error("the gap write failed") } shouldBe JournalRetry.FAILED_DEFERRED

        lossState("evt-settled") shouldBe LOSS_DEFERRED_SETTLED
        // Parked is parked, whatever the settled bit says: a caller that read 3 as "already
        // recorded, safe to commit" would be round 37's Critical again.
        ingest.claimEventLoss("evt-settled") { recordLoss() } shouldBe LossClaim.DEFERRED
        ingest.isReplayCandidate("evt-settled") shouldBe false

        ingest.resumeDeferredSettlements() shouldBe 1
        lossState("evt-settled") shouldBe LOSS_SETTLED
        ingest.claimEventLoss("evt-settled") { recordLoss() } shouldBe LossClaim.ALREADY_RECORDED
        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
        Unit
    }

    @Test
    fun aCarriedOverClaimSpentBeforeAParkIsStillSpentAfterIt() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-claimed"), "gen", 60_000) shouldBe true
        ingest.claimEventLoss("evt-claimed") { recordLoss() } shouldBe LossClaim.RECORDED
        failCommit("evt-claimed"); failCommit("evt-claimed")

        failCommit("evt-claimed") { error("the gap write failed") } shouldBe JournalRetry.FAILED_DEFERRED
        ingest.resumeDeferredSettlements(pkg) shouldBe 1

        // The resume gave the row back exactly the state it had; writing 0 here would let the
        // next pass record the same loss a second time.
        lossState("evt-claimed") shouldBe LOSS_SETTLED
        ingest.claimEventLoss("evt-claimed") { recordLoss() } shouldBe LossClaim.ALREADY_RECORDED
        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
        Unit
    }

    @Test
    fun theCandidateReadsAgreeOnAllFourValues() = runBlocking {
        ready()
        ingest.journal(snapshotAt("evt-0", 100L), "gen", 60_000) shouldBe true
        ingest.journal(snapshotAt("evt-1", 200L), "gen", 60_000) { recordLoss() } shouldBe true
        ingest.journal(snapshotAt("evt-2", 300L), "gen", 60_000) shouldBe true
        ingest.journal(snapshotAt("evt-3", 400L), "gen", 60_000) { recordLoss() } shouldBe true
        for (id in listOf("evt-2", "evt-3")) {
            failCommit(id); failCommit(id)
            failCommit(id) { error("the gap write failed") } shouldBe JournalRetry.FAILED_DEFERRED
        }
        listOf("evt-0", "evt-1", "evt-2", "evt-3").map { lossState(it) } shouldBe listOf(LOSS_UNSETTLED, LOSS_SETTLED, LOSS_DEFERRED, LOSS_DEFERRED_SETTLED)

        withClue("the replay reads 0 and 1, never a parked row") { pendingIds() shouldBe listOf("evt-0", "evt-1") }
        listOf("evt-0", "evt-1", "evt-2", "evt-3").map { ingest.isReplayCandidate(it) } shouldBe listOf(true, true, false, false)
        withClue("the settle walk reads only rows with something to settle") {
            ingest.pendingJournalForPackage(pkg).snapshots.map { it.eventId } shouldBe listOf("evt-0")
        }
        ingest.claimEventLoss("evt-3") { recordLoss() } shouldBe LossClaim.DEFERRED
        ingest.claimEventLoss("evt-2") { recordLoss() } shouldBe LossClaim.DEFERRED
        Unit
    }

    /** The record went in and the filing was refused: the two must go together, so the record goes too. */
    @Test
    fun aRecordWhoseFilingFailsIsRolledBackWithIt() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-half"), "gen", 60_000) shouldBe true
        failCommit("evt-half"); failCommit("evt-half")
        sql("CREATE TRIGGER refuse_filing BEFORE UPDATE OF state ON event_journal WHEN NEW.state = 'FAILED' BEGIN SELECT RAISE(ABORT, 'refused'); END")
        try {
            failCommit("evt-half") shouldBe JournalRetry.FAILED_DEFERRED
            withClue("a record standing for a filing that never happened") { allGaps().isEmpty() shouldBe true }
            ingest.isJournalPending("evt-half") shouldBe true
            lossState("evt-half") shouldBe LOSS_DEFERRED
        } finally {
            sql("DROP TRIGGER refuse_filing")
        }
        Unit
    }

    @Test
    fun aParkedRowResumedAndCommittedLosesNothing() = runBlocking {
        ready()
        val s = groupBody("evt-lucky")
        ingest.journal(s, "gen", 60_000) shouldBe true
        failCommit("evt-lucky"); failCommit("evt-lucky")
        failCommit("evt-lucky") { error("the gap write failed") } shouldBe JournalRetry.FAILED_DEFERRED
        ingest.resumeDeferredSettlements() shouldBe 1

        // The fourth try is a real commit and it works: the threshold was for recording the loss,
        // not a ceiling on trying.
        commitWithLoss(s, withIdentity = true) { }

        ingest.isJournalPending("evt-lucky") shouldBe false
        messageCount() shouldBe 1
        allGaps().none { it.reason == GapReason.COMMIT_FAILED.name } shouldBe true
        Unit
    }

    @Test
    fun aRowParkedBeforeItsClaimAnswersDeferredAndTakesNoGap() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-park-first"), "gen", 60_000) shouldBe true
        failCommit("evt-park-first"); failCommit("evt-park-first")
        failCommit("evt-park-first") { error("the gap write failed") } shouldBe JournalRetry.FAILED_DEFERRED

        ingest.claimEventLoss("evt-park-first") { recordLoss() } shouldBe LossClaim.DEFERRED
        allGaps().isEmpty() shouldBe true
        lossState("evt-park-first") shouldBe LOSS_DEFERRED
        Unit
    }

    /** The vault refuses the park as well: the row is left exactly where it was, and nothing claims otherwise. */
    @Test
    fun aParkThatFailsTooLeavesTheRowWhereItWas() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-stuck"), "gen", 60_000) shouldBe true
        failCommit("evt-stuck"); failCommit("evt-stuck")
        sql("CREATE TRIGGER refuse_park BEFORE UPDATE OF lossRecorded ON event_journal WHEN NEW.lossRecorded >= 2 BEGIN SELECT RAISE(ABORT, 'refused'); END")
        try {
            failCommit("evt-stuck") { error("the gap write failed") } shouldBe JournalRetry.RETRYABLE
            attempts("evt-stuck") shouldBe 2
            lossState("evt-stuck") shouldBe LOSS_UNSETTLED
            ingest.isReplayCandidate("evt-stuck") shouldBe true
            pendingIds() shouldBe listOf("evt-stuck")
            allGaps().isEmpty() shouldBe true
        } finally {
            sql("DROP TRIGGER refuse_park")
        }
        Unit
    }

    @Test
    fun aRowThatAlreadyLeftPendingIsNeitherChargedNorRecorded() = runBlocking {
        ready()
        val s = groupBody("evt-gone")
        ingest.journal(s, "gen", 60_000) shouldBe true
        commitWithLoss(s, withIdentity = true) { }
        ingest.isJournalPending("evt-gone") shouldBe false

        failCommit("evt-gone") shouldBe JournalRetry.NOT_PENDING
        allGaps().isEmpty() shouldBe true
        Unit
    }
}
