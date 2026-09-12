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
import dev.quietinbox.platform.storage.db.EventJournalEntity
import dev.quietinbox.platform.storage.db.LOSS_DEFERRED_SETTLED
import dev.quietinbox.platform.storage.db.LOSS_DEFERRED
import dev.quietinbox.platform.storage.repo.JournalRetry
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import dev.quietinbox.platform.capture.CaptureCoordinator
import dev.quietinbox.platform.capture.ListenerAccess
import dev.quietinbox.platform.storage.repo.PendingJournalBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.settings.SettingsRepository
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import dev.quietinbox.platform.crypto.BlobCipher
import dev.quietinbox.platform.media.MediaCopier
import dev.quietinbox.platform.media.MediaStreams

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

    private fun snapshotAt(eventId: String, observedAt: Long, packageName: String = pkg) =
        Fixtures.snapshot(Fixtures.base(title = "t", text = "b"), packageName = packageName, eventId = eventId, observedAt = observedAt)

    private fun messagingSnapshot(eventId: String) =
        Fixtures.snapshot(
            shape = Fixtures.messaging(conversationTitle = "Friends") {
                message("Alice", "Hello from $eventId")
            },
            packageName = pkg,
            eventId = eventId,
        )

    private fun createCoordinator(customIngest: IngestRepository = ingest): CaptureCoordinator {
        val mediaDir = MediaDirectory(context)
        val sources = SourceRepository(holder, mediaDir)
        val settings = SettingsRepository(context)
        val maintenance = VaultMaintenance()
        val vault = VaultRepository(holder, keys, mediaDir, settings, maintenance)
        val mediaCopier = MediaCopier(
            streams = MediaStreams(context),
            holder = holder,
            cipher = BlobCipher(keys),
            dir = mediaDir,
            settings = settings,
            maintenance = maintenance,
        )
        val listenerAccess = ListenerAccess(context)
        return CaptureCoordinator(
            context = context,
            ingest = customIngest,
            sources = sources,
            health = health,
            settings = settings,
            vault = vault,
            mediaCopier = mediaCopier,
            listenerAccess = listenerAccess,
            maintenance = maintenance,
        )
    }

    private suspend fun journalEntity(eventId: String): EventJournalEntity =
        holder.db().openHelper.writableDatabase.query(
            "SELECT eventId, generation, receivedAtEpochMs, expiresAtEpochMs, state, attempts, failureCode, payload, packageName, lossRecorded FROM event_journal WHERE eventId = ?",
            arrayOf<Any>(eventId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) error("No journal row for $eventId")
            EventJournalEntity(
                eventId = cursor.getString(0),
                generation = cursor.getString(1),
                receivedAtEpochMs = cursor.getLong(2),
                expiresAtEpochMs = cursor.getLong(3),
                state = cursor.getString(4),
                attempts = cursor.getInt(5),
                failureCode = if (cursor.isNull(6)) null else cursor.getString(6),
                payload = cursor.getString(7),
                packageName = if (cursor.isNull(8)) null else cursor.getString(8),
                lossRecorded = cursor.getInt(9),
            )
        }

    private suspend fun messageCount(): Int =
        holder.db().openHelper.writableDatabase.query("SELECT COUNT(*) FROM message").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun allGaps(limit: Int = 1000) = holder.db().healthDao().observeGaps(limit).first()

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
     * Round 37 asked that a settled row never be walked back to deferred, because a resume that
     * wrote 0 would let the next pass claim the same loss again. Under the two-bit column the row
     * *can* be parked — a row given up on needs that whatever its arrival loss did (issue #28) —
     * but the settled bit rides along and comes back with it: the claim is never walked back, and
     * a second park finds nothing to park.
     */
    @Test
    fun aSettledRowParkedIsNeverWalkedBackToUnsettled() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-settled-park"), "gen", 60_000) { recordLoss() } shouldBe true
        lossState("evt-settled-park") shouldBe LOSS_SETTLED

        holder.db().journalDao().deferLoss("evt-settled-park") shouldBe 1
        lossState("evt-settled-park") shouldBe LOSS_DEFERRED_SETTLED
        withClue("parked twice is parked once") { holder.db().journalDao().deferLoss("evt-settled-park") shouldBe 0 }
        ingest.claimEventLoss("evt-settled-park") { recordLoss() } shouldBe LossClaim.DEFERRED

        ingest.resumeDeferredSettlements() shouldBe 1
        lossState("evt-settled-park") shouldBe LOSS_SETTLED
        ingest.claimEventLoss("evt-settled-park") { recordLoss() } shouldBe LossClaim.ALREADY_RECORDED
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

        // Round 39 Codex M2: the four values above never called pendingExcluding. A paused source
        // with 0 and 1 must vanish from that read, and parked 2/3 of the included source stay out.
        val paused = "com.example.paused"
        ingest.journal(snapshotAt("evt-p0", 50L, paused), "gen", 60_000) shouldBe true
        ingest.journal(snapshotAt("evt-p1", 60L, paused), "gen", 60_000) { recordLoss() } shouldBe true
        listOf("evt-p0", "evt-p1").map { lossState(it) } shouldBe listOf(LOSS_UNSETTLED, LOSS_SETTLED)
        withClue("without exclusions, every source's 0 and 1 are candidates") {
            pendingIds() shouldBe listOf("evt-p0", "evt-p1", "evt-0", "evt-1")
        }
        withClue("a paused source's 0/1 are absent; parked 2/3 of the included source stay out") {
            ingest.pendingJournal(excludingPackages = listOf(paused)).map { it.second.eventId } shouldBe
                listOf("evt-0", "evt-1")
        }
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

    // ---- Issue #33: atomic terminal gap on DECODE and PARSE failure ----

    @Test
    fun unreadablePayloadFailsTerminalWithBoundedPayloadUnreadableGap() = runBlocking {
        ready()
        // Insert a row directly into event_journal with malformed JSON payload
        holder.db().journalDao().insert(
            EventJournalEntity(
                eventId = "evt-bad-json",
                generation = "gen",
                receivedAtEpochMs = 5_000L,
                expiresAtEpochMs = 65_000L,
                state = "PENDING",
                attempts = 0,
                failureCode = null,
                payload = "{ malformed json",
                packageName = pkg,
                lossRecorded = LOSS_UNSETTLED,
            ),
        )

        // pendingJournal attempts to decode, catches error, and calls markJournalTerminal
        val pending = ingest.pendingJournal()
        pending.none { it.second.eventId == "evt-bad-json" } shouldBe true

        ingest.isJournalPending("evt-bad-json") shouldBe false
        val row = holder.db().journalDao().state("evt-bad-json")
        row shouldBe "FAILED"
        holder.db().journalDao().payload("evt-bad-json") shouldBe ""

        val record = allGaps().single { it.reason == GapReason.PAYLOAD_UNREADABLE.name }
        record.precision shouldBe GapPrecision.BOUNDED.name
        record.startEpochMs shouldBe 5_000L
        record.endEpochMs shouldBe 5_000L
        record.packageName shouldBe pkg
        Unit
    }

    @Test
    fun parseFailureFailsTerminalWithBoundedParseFailedGap() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-parse-fail"), "gen", 60_000) shouldBe true

        val retry = ingest.markJournalTerminal("evt-parse-fail", "PARSE_IllegalArgumentException") {
            health.recordGap(1_000, 2_000, GapReason.PARSE_FAILED, GapPrecision.BOUNDED, 2_000, pkg)
        }
        retry shouldBe JournalRetry.FAILED_RECORDED

        ingest.isJournalPending("evt-parse-fail") shouldBe false
        holder.db().journalDao().state("evt-parse-fail") shouldBe "FAILED"
        holder.db().journalDao().payload("evt-parse-fail") shouldBe ""

        val record = allGaps().single { it.reason == GapReason.PARSE_FAILED.name }
        record.precision shouldBe GapPrecision.BOUNDED.name
        record.packageName shouldBe pkg

        // Terminal row is not pending: subsequent call returns NOT_PENDING
        val second = ingest.markJournalTerminal("evt-parse-fail", "PARSE_Other") {
            error("should not be called")
        }
        second shouldBe JournalRetry.NOT_PENDING
        Unit
    }

    @Test
    fun terminalFailureRollsBackOnGapFailureAndParksTheRow() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-terminal-park"), "gen", 60_000) shouldBe true

        val retry = ingest.markJournalTerminal("evt-terminal-park", "PARSE_Crash") {
            error("gap write fails")
        }
        retry shouldBe JournalRetry.FAILED_DEFERRED

        // Row is still pending, payload intact, but lossRecorded is now LOSS_DEFERRED
        ingest.isJournalPending("evt-terminal-park") shouldBe true
        holder.db().journalDao().state("evt-terminal-park") shouldBe "PENDING"
        holder.db().journalDao().payload("evt-terminal-park") shouldNotBe ""
        lossState("evt-terminal-park") shouldBe LOSS_DEFERRED

        // Candidate query filters it out
        ingest.isReplayCandidate("evt-terminal-park") shouldBe false
        pendingIds() shouldBe emptyList()
        Unit
    }

    @Test
    fun concurrentPolicyDiscardDoesNotGetOverwrittenByTerminalFailure() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-policy-discard"), "gen", 60_000) shouldBe true

        // 1. Raw page is read while row is PENDING
        val preRead = holder.db().journalDao().pending(200)
        preRead.map { it.eventId } shouldBe listOf("evt-policy-discard")

        // 2. User disables source: row becomes DISCARDED with SOURCE_DISABLED
        holder.db().journalDao().discardPending(pkg) shouldBe 1
        holder.db().journalDao().state("evt-policy-discard") shouldBe "DISCARDED"

        // 3. Stale terminal call attempts to file FAILED with gap
        var gapRecorded = false
        val retry = ingest.markJournalTerminal("evt-policy-discard", "DECODE") {
            gapRecorded = true
            health.recordGap(1_000, 2_000, GapReason.PAYLOAD_UNREADABLE, GapPrecision.BOUNDED, 2_000, pkg)
        }

        // Must NOT overwrite DISCARDED state or failureCode, and must NOT record a gap
        retry shouldBe JournalRetry.NOT_PENDING
        gapRecorded shouldBe false
        holder.db().journalDao().state("evt-policy-discard") shouldBe "DISCARDED"
        holder.db().journalDao().payload("evt-policy-discard") shouldBe ""
        val (fc, st) = holder.db().openHelper.writableDatabase.query("SELECT failureCode, state FROM event_journal WHERE eventId = 'evt-policy-discard'").use {
            it.moveToFirst()
            Pair(it.getString(0), it.getString(1))
        }
        st shouldBe "DISCARDED"
        fc shouldBe "SOURCE_DISABLED"
        allGaps().none { it.reason == GapReason.PAYLOAD_UNREADABLE.name } shouldBe true
        Unit
    }

    @Test
    fun repeatedOrConcurrentTerminalCallDoesNotDuplicateGaps() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-no-dup"), "gen", 60_000) shouldBe true

        var gapWrites = 0
        val first = ingest.markJournalTerminal("evt-no-dup", "PARSE_Bad") {
            gapWrites++
            health.recordGap(1_000, 2_000, GapReason.PARSE_FAILED, GapPrecision.BOUNDED, 2_000, pkg)
        }
        first shouldBe JournalRetry.FAILED_RECORDED
        gapWrites shouldBe 1

        val second = ingest.markJournalTerminal("evt-no-dup", "PARSE_Bad") {
            gapWrites++
            health.recordGap(1_000, 2_000, GapReason.PARSE_FAILED, GapPrecision.BOUNDED, 2_000, pkg)
        }
        second shouldBe JournalRetry.NOT_PENDING
        gapWrites shouldBe 1
        allGaps().count { it.reason == GapReason.PARSE_FAILED.name } shouldBe 1
        Unit
    }

    @Test
    fun concurrentCoroutinesCallingTerminalDoNotDuplicateGapsOrDeadlock() = runBlocking {
        ready()
        ingest.journal(snapshot("evt-concurrent-term"), "gen", 60_000) shouldBe true

        val ready1 = CompletableDeferred<Unit>()
        val ready2 = CompletableDeferred<Unit>()
        val startGate = CompletableDeferred<Unit>()
        val gapWrites = AtomicInteger(0)

        withTimeout(10_000) {
            coroutineScope {
                val d1 = async(Dispatchers.IO) {
                    ready1.complete(Unit)
                    startGate.await()
                    ingest.markJournalTerminal("evt-concurrent-term", "PARSE_Crash") {
                        gapWrites.incrementAndGet()
                        health.recordGap(1_000, 2_000, GapReason.PARSE_FAILED, GapPrecision.BOUNDED, 2_000, pkg)
                    }
                }
                val d2 = async(Dispatchers.IO) {
                    ready2.complete(Unit)
                    startGate.await()
                    ingest.markJournalTerminal("evt-concurrent-term", "PARSE_Crash") {
                        gapWrites.incrementAndGet()
                        health.recordGap(1_000, 2_000, GapReason.PARSE_FAILED, GapPrecision.BOUNDED, 2_000, pkg)
                    }
                }

                try {
                    ready1.await()
                    ready2.await()
                    startGate.complete(Unit)
                    val r1 = d1.await()
                    val r2 = d2.await()

                    val results = setOf(r1, r2)
                    results shouldBe setOf(JournalRetry.FAILED_RECORDED, JournalRetry.NOT_PENDING)
                    gapWrites.get() shouldBe 1
                    allGaps().count { it.reason == GapReason.PARSE_FAILED.name } shouldBe 1
                } finally {
                    if (!startGate.isCompleted) {
                        startGate.cancel()
                    }
                    d1.cancel()
                    d2.cancel()
                }
            }
        }
        Unit
    }

    @Test
    fun missingReadySignalTimesOutAndCleansUpWorkersPromptly() = runBlocking {
        ready()
        val ready1 = CompletableDeferred<Unit>()
        val startGate = CompletableDeferred<Unit>()
        val workerRan = AtomicBoolean(false)

        val failed = try {
            withTimeout(1_000) {
                coroutineScope {
                    val d1 = async(Dispatchers.IO) {
                        // intentionally does NOT complete ready1!
                        startGate.await()
                        workerRan.set(true)
                    }
                    try {
                        ready1.await()
                        startGate.complete(Unit)
                        d1.await()
                    } finally {
                        if (!startGate.isCompleted) startGate.cancel()
                        d1.cancel()
                    }
                }
            }
            false
        } catch (e: TimeoutCancellationException) {
            true
        }

        failed shouldBe true
        workerRan.get() shouldBe false
        Unit
    }

    @Test
    fun triggerFailureDuringTerminalUpdateRollsBackBothGapAndJournalRow() = runBlocking {
        ready()
        val originalSnapshot = snapshot("evt-trigger-fail")
        ingest.journal(originalSnapshot, "gen", 60_000) shouldBe true
        val originalEntity = journalEntity("evt-trigger-fail")

        val db = holder.db().openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER fail_journal_update BEFORE UPDATE ON event_journal BEGIN SELECT RAISE(ABORT, 'forced trigger failure'); END;")
        try {
            val retry = ingest.markJournalTerminal("evt-trigger-fail", "DECODE") {
                health.recordGap(1_000, 2_000, GapReason.PAYLOAD_UNREADABLE, GapPrecision.BOUNDED, 2_000, pkg)
            }
            retry shouldBe JournalRetry.RETRYABLE

            // Both gap and journal row must be rolled back
            allGaps().none { it.reason == GapReason.PAYLOAD_UNREADABLE.name } shouldBe true
            val postEntity = journalEntity("evt-trigger-fail")
            postEntity.state shouldBe "PENDING"
            postEntity.attempts shouldBe originalEntity.attempts
            postEntity.payload shouldBe originalEntity.payload
            postEntity.failureCode shouldBe originalEntity.failureCode
            lossState("evt-trigger-fail") shouldBe LOSS_UNSETTLED
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS fail_journal_update")
        }
        Unit
    }

    @Test
    fun lossBitsDeferAndResumePreservesSettledState() = runBlocking {
        ready()
        // Case A: unsettled (bit 0) row -> defer (+2) -> 2 -> resume (-2) -> 0
        ingest.journal(snapshot("evt-loss-bit-0"), "gen", 60_000) shouldBe true
        lossState("evt-loss-bit-0") shouldBe LOSS_UNSETTLED

        val retry0 = ingest.markJournalTerminal("evt-loss-bit-0", "PARSE_FAIL") { error("gap fail") }
        retry0 shouldBe JournalRetry.FAILED_DEFERRED
        lossState("evt-loss-bit-0") shouldBe LOSS_DEFERRED
        ingest.isReplayCandidate("evt-loss-bit-0") shouldBe false

        ingest.resumeDeferredSettlements() shouldBe 1
        lossState("evt-loss-bit-0") shouldBe LOSS_UNSETTLED
        ingest.isReplayCandidate("evt-loss-bit-0") shouldBe true

        // Case B: settled (bit 1) row -> defer (+2) -> 3 -> resume (-2) -> 1
        ingest.journal(snapshot("evt-loss-bit-1"), "gen", 60_000) shouldBe true
        ingest.claimEventLoss("evt-loss-bit-1") { recordLoss() } shouldBe LossClaim.RECORDED
        lossState("evt-loss-bit-1") shouldBe LOSS_SETTLED

        // Terminal attempt with failing gap write on already-settled row defers it to 3
        val retry1 = ingest.markJournalTerminal("evt-loss-bit-1", "COMMIT_FAIL") { error("gap fail") }
        retry1 shouldBe JournalRetry.FAILED_DEFERRED
        lossState("evt-loss-bit-1") shouldBe LOSS_DEFERRED_SETTLED
        ingest.isReplayCandidate("evt-loss-bit-1") shouldBe false

        ingest.resumeDeferredSettlements() shouldBe 1
        lossState("evt-loss-bit-1") shouldBe LOSS_SETTLED
        ingest.isReplayCandidate("evt-loss-bit-1") shouldBe true
        Unit
    }

    @Test
    fun deferLossPreservesSourceIsolation() = runBlocking {
        ready()
        val pkgOther = "dev.quietinbox.other"
        ingest.journal(snapshot("evt-source-a"), "gen", 60_000) shouldBe true
        ingest.journal(snapshotAt("evt-source-b", 1_000L, pkgOther), "gen", 60_000) shouldBe true

        // Row A fails with failing gap write and gets parked
        val retryA = ingest.markJournalTerminal("evt-source-a", "PARSE_Crash") {
            error("gap write failure")
        }
        retryA shouldBe JournalRetry.FAILED_DEFERRED
        lossState("evt-source-a") shouldBe LOSS_DEFERRED
        ingest.isReplayCandidate("evt-source-a") shouldBe false

        // Row B must remain untouched and still be a replay candidate
        lossState("evt-source-b") shouldBe LOSS_UNSETTLED
        ingest.isReplayCandidate("evt-source-b") shouldBe true
        pendingIds() shouldBe listOf("evt-source-b")
        Unit
    }

    @Test
    fun twoHundredDeferredBadRowsDoNotStarveReplayOfTailRows() = runBlocking {
        ready()
        // Insert 200 bad rows
        for (i in 1..200) {
            val id = "evt-bad-$i"
            holder.db().journalDao().insert(
                EventJournalEntity(
                    eventId = id,
                    generation = "gen",
                    receivedAtEpochMs = 1_000L + i,
                    expiresAtEpochMs = 60_000L + i,
                    state = "PENDING",
                    attempts = 0,
                    failureCode = null,
                    payload = "{ bad json",
                    packageName = pkg,
                    lossRecorded = LOSS_UNSETTLED,
                ),
            )
        }
        // Insert the 201st row with valid payload
        val goodSnapshot = snapshot("evt-good-201")
        ingest.journal(goodSnapshot, "gen", 60_000) shouldBe true

        // Replay drain loop: continues through rawAdvanced pages without manual two-step calls
        val recovered = mutableListOf<String>()
        var rounds = 0
        var progressed = true
        while (progressed && rounds++ < 10) {
            val batch = ingest.pendingJournal(limit = 200)
            if (batch.isEof) break
            for ((_, snap) in batch.snapshots) {
                recovered += snap.eventId
                holder.db().journalDao().setState(snap.eventId, "COMMITTED", null)
            }
            progressed = if (batch.snapshots.isEmpty()) batch.rawAdvanced else true
        }

        recovered shouldBe listOf("evt-good-201")
        Unit
    }

    @Test
    fun cancellationDuringTerminalTransitionRollsBackAndKeepsPendingNotDeferred() = runBlocking {
        ready()
        val originalSnapshot = snapshot("evt-cancel-term")
        ingest.journal(originalSnapshot, "gen", 60_000) shouldBe true
        val originalEntity = journalEntity("evt-cancel-term")

        var exceptionThrown = false
        try {
            ingest.markJournalTerminal("evt-cancel-term", "PARSE_FAIL") {
                health.recordGap(1_000, 2_000, GapReason.PARSE_FAILED, GapPrecision.BOUNDED, 2_000, pkg)
                throw CancellationException("simulated coroutine cancellation")
            }
        } catch (e: CancellationException) {
            exceptionThrown = true
        }

        exceptionThrown shouldBe true
        // Transaction must be completely rolled back
        allGaps().none { it.reason == GapReason.PARSE_FAILED.name } shouldBe true
        val postEntity = journalEntity("evt-cancel-term")
        postEntity.state shouldBe "PENDING"
        postEntity.attempts shouldBe originalEntity.attempts
        postEntity.payload shouldBe originalEntity.payload
        postEntity.failureCode shouldBe originalEntity.failureCode
        lossState("evt-cancel-term") shouldBe LOSS_UNSETTLED
        // Must NOT be parked or deferred
        ingest.isReplayCandidate("evt-cancel-term") shouldBe true
        Unit
    }

    @Test
    fun oneHundredNinetyNineBadRowsPlusNormalTailRowCommitsTailRowInOneBatch() = runBlocking {
        ready()
        for (i in 1..199) {
            val id = "evt-bad-199-$i"
            holder.db().journalDao().insert(
                EventJournalEntity(
                    eventId = id,
                    generation = "gen",
                    receivedAtEpochMs = 1_000L + i,
                    expiresAtEpochMs = 60_000L + i,
                    state = "PENDING",
                    attempts = 0,
                    failureCode = null,
                    payload = "{ bad json",
                    packageName = pkg,
                    lossRecorded = LOSS_UNSETTLED,
                ),
            )
        }
        val goodSnapshot = snapshot("evt-good-200")
        ingest.journal(goodSnapshot, "gen", 60_000) shouldBe true

        val batch = ingest.pendingJournal(limit = 200)
        // 199 bad rows were terminalized, and the single good row was returned in the batch snapshots
        batch.snapshots.map { it.second.eventId } shouldBe listOf("evt-good-200")
        batch.rawCount shouldBe 200
        batch.rawAdvanced shouldBe true
        Unit
    }

    @Test
    fun badRowsWithFailingGapAndSuccessfulDeferralAdvancesToTailRow() = runBlocking {
        ready()
        // Install trigger that fails gap insertion only
        val db = holder.db().openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER fail_gap_insert BEFORE INSERT ON gap_interval BEGIN SELECT RAISE(ABORT, 'forced gap failure'); END;")
        try {
            // Insert 200 bad rows
            for (i in 1..200) {
                val id = "evt-defer-bad-$i"
                holder.db().journalDao().insert(
                    EventJournalEntity(
                        eventId = id,
                        generation = "gen",
                        receivedAtEpochMs = 1_000L + i,
                        expiresAtEpochMs = 60_000L + i,
                        state = "PENDING",
                        attempts = 0,
                        failureCode = null,
                        payload = "{ bad json",
                        packageName = pkg,
                        lossRecorded = LOSS_UNSETTLED,
                    ),
                )
            }
            val goodSnapshot = snapshot("evt-good-defer-201")
            ingest.journal(goodSnapshot, "gen", 60_000) shouldBe true

            // In first page read: 200 bad rows fail gap write and are deferred, returning 0 snapshots but rawAdvanced = true
            val firstBatch = ingest.pendingJournal(limit = 200)
            firstBatch.snapshots.isEmpty() shouldBe true
            firstBatch.rawAdvanced shouldBe true
            firstBatch.deferredCount shouldBe 200

            // In second page read: the 200 deferred rows are out of the candidate set, so tail row is read!
            val secondBatch = ingest.pendingJournal(limit = 200)
            secondBatch.snapshots.map { it.second.eventId } shouldBe listOf("evt-good-defer-201")
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS fail_gap_insert")
        }
        Unit
    }

    @Test
    fun badRowsWithFailingDeferralPreservesPayloadAndAttemptsWithBoundedExit() = runBlocking {
        ready()
        val db = holder.db().openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER fail_gap_insert BEFORE INSERT ON gap_interval BEGIN SELECT RAISE(ABORT, 'forced gap failure'); END;")
        db.execSQL("CREATE TRIGGER fail_journal_update BEFORE UPDATE ON event_journal BEGIN SELECT RAISE(ABORT, 'forced update failure'); END;")
        try {
            holder.db().journalDao().insert(
                EventJournalEntity(
                    eventId = "evt-bad-nodefer",
                    generation = "gen",
                    receivedAtEpochMs = 1_000L,
                    expiresAtEpochMs = 60_000L,
                    state = "PENDING",
                    attempts = 0,
                    failureCode = null,
                    payload = "{ bad json preserved",
                    packageName = pkg,
                    lossRecorded = LOSS_UNSETTLED,
                ),
            )

            val batch = ingest.pendingJournal(limit = 200)
            batch.snapshots.isEmpty() shouldBe true
            batch.rawAdvanced shouldBe false
            batch.deferredCount shouldBe 0

            // Row preserved intact
            val entity = journalEntity("evt-bad-nodefer")
            entity.state shouldBe "PENDING"
            entity.attempts shouldBe 0
            entity.payload shouldBe "{ bad json preserved"
            entity.failureCode shouldBe null
            lossState("evt-bad-nodefer") shouldBe LOSS_UNSETTLED
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS fail_gap_insert")
            db.execSQL("DROP TRIGGER IF EXISTS fail_journal_update")
        }
        Unit
    }

    // ---- Real CaptureCoordinator + Real IngestRepository / Room cross-layer verification (S3) ----

    @Test
    fun realCoordinatorAndRoomReplay200And400BadRowsWithSuccessfulGapsCommitsTailRow() = runBlocking {
        ready()
        val sources = SourceRepository(holder, MediaDirectory(context))
        sources.enable(pkg, "Chat", "standard", 1_000L)
        val coordinator = createCoordinator()

        // 1. First scenario: 200 bad rows + 201st valid messaging row
        for (i in 1..200) {
            holder.db().journalDao().insert(
                EventJournalEntity(
                    eventId = "evt-bad-$i",
                    generation = "gen",
                    receivedAtEpochMs = 1_000L + i,
                    expiresAtEpochMs = 60_000L + i,
                    state = "PENDING",
                    attempts = 0,
                    failureCode = null,
                    payload = "{ bad json $i",
                    packageName = pkg,
                    lossRecorded = LOSS_UNSETTLED,
                ),
            )
        }
        val goodSnapshot201 = messagingSnapshot("evt-good-201")
        ingest.journal(goodSnapshot201, "gen", 60_000) shouldBe true

        // Call production replayPass directly on CaptureCoordinator — NO custom drain loop or fake setState!
        val ran1 = coordinator.replayPassForTesting()
        ran1 shouldBe true

        // Tail row 201 was truly parsed and committed by CaptureCoordinator into Room!
        holder.db().journalDao().state("evt-good-201") shouldBe "COMMITTED"
        messageCount() shouldBe 1

        // All 200 bad rows were marked FAILED and PAYLOAD_UNREADABLE gaps recorded in Room DB
        holder.db().journalDao().state("evt-bad-1") shouldBe "FAILED"
        holder.db().journalDao().state("evt-bad-200") shouldBe "FAILED"
        allGaps().count { it.reason == GapReason.PAYLOAD_UNREADABLE.name } shouldBe 200

        // 2. Second scenario: 400 additional bad rows + 401st valid messaging row
        for (i in 201..600) {
            holder.db().journalDao().insert(
                EventJournalEntity(
                    eventId = "evt-bad400-$i",
                    generation = "gen",
                    receivedAtEpochMs = 10_000L + i,
                    expiresAtEpochMs = 60_000L + i,
                    state = "PENDING",
                    attempts = 0,
                    failureCode = null,
                    payload = "{ bad json 400 $i",
                    packageName = pkg,
                    lossRecorded = LOSS_UNSETTLED,
                ),
            )
        }
        val goodSnapshot401 = messagingSnapshot("evt-good-401")
        ingest.journal(goodSnapshot401, "gen", 60_000) shouldBe true

        val ran2 = coordinator.replayPassForTesting()
        ran2 shouldBe true

        holder.db().journalDao().state("evt-good-401") shouldBe "COMMITTED"
        messageCount() shouldBe 2
        holder.db().journalDao().state("evt-bad400-201") shouldBe "FAILED"
        holder.db().journalDao().state("evt-bad400-600") shouldBe "FAILED"
        allGaps().count { it.reason == GapReason.PAYLOAD_UNREADABLE.name } shouldBe 600
        Unit
    }

    @Test
    fun realCoordinatorAndRoomReplay200And400BadRowsWithFailingGapAndSuccessfulDeferralCommitsTailRow() = runBlocking {
        ready()
        val sources = SourceRepository(holder, MediaDirectory(context))
        sources.enable(pkg, "Chat", "standard", 1_000L)
        val coordinator = createCoordinator()

        val db = holder.db().openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER fail_gap_insert BEFORE INSERT ON gap_interval BEGIN SELECT RAISE(ABORT, 'forced gap failure'); END;")
        try {
            // 200 bad rows where decode fails; gap fails so rows are deferred (+2 -> LOSS_DEFERRED)
            for (i in 1..200) {
                holder.db().journalDao().insert(
                    EventJournalEntity(
                        eventId = "evt-defer-bad-$i",
                        generation = "gen",
                        receivedAtEpochMs = 1_000L + i,
                        expiresAtEpochMs = 60_000L + i,
                        state = "PENDING",
                        attempts = 0,
                        failureCode = null,
                        payload = "{ bad json defer $i",
                        packageName = pkg,
                        lossRecorded = LOSS_UNSETTLED,
                    ),
                )
            }
            val goodSnapshot201 = messagingSnapshot("evt-good-defer-201")
            ingest.journal(goodSnapshot201, "gen", 60_000) shouldBe true

            // Coordinator replayPass advances through the deferred bad rows and commits the tail row
            val ran1 = coordinator.replayPassForTesting()
            ran1 shouldBe true

            holder.db().journalDao().state("evt-good-defer-201") shouldBe "COMMITTED"
            messageCount() shouldBe 1

            // Bad rows were deferred (not committed, lossState == LOSS_DEFERRED)
            lossState("evt-defer-bad-1") shouldBe LOSS_DEFERRED
            lossState("evt-defer-bad-200") shouldBe LOSS_DEFERRED

            // 400 additional bad rows
            for (i in 201..600) {
                holder.db().journalDao().insert(
                    EventJournalEntity(
                        eventId = "evt-defer400-bad-$i",
                        generation = "gen",
                        receivedAtEpochMs = 10_000L + i,
                        expiresAtEpochMs = 60_000L + i,
                        state = "PENDING",
                        attempts = 0,
                        failureCode = null,
                        payload = "{ bad json defer 400 $i",
                        packageName = pkg,
                        lossRecorded = LOSS_UNSETTLED,
                    ),
                )
            }
            val goodSnapshot401 = messagingSnapshot("evt-good-defer-401")
            ingest.journal(goodSnapshot401, "gen", 60_000) shouldBe true

            val ran2 = coordinator.replayPassForTesting()
            ran2 shouldBe true

            holder.db().journalDao().state("evt-good-defer-401") shouldBe "COMMITTED"
            messageCount() shouldBe 2
            lossState("evt-defer400-bad-201") shouldBe LOSS_DEFERRED
            lossState("evt-defer400-bad-600") shouldBe LOSS_DEFERRED
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS fail_gap_insert")
        }
        Unit
    }

    @Test
    fun realCoordinatorAndRoomReplayFailingDeferralExitsBoundedAndPreservesPayloadAndAttempts() = runBlocking {
        ready()
        val sources = SourceRepository(holder, MediaDirectory(context))
        sources.enable(pkg, "Chat", "standard", 1_000L)

        val pageCallCount = AtomicInteger(0)
        val countingIngest = object : IngestRepository(holder) {
            override suspend fun pendingJournal(
                limit: Int,
                excludingPackages: Collection<String>,
            ): PendingJournalBatch {
                pageCallCount.incrementAndGet()
                return super.pendingJournal(limit, excludingPackages)
            }
        }
        val coordinator = createCoordinator(countingIngest)

        val db = holder.db().openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER fail_gap_insert BEFORE INSERT ON gap_interval BEGIN SELECT RAISE(ABORT, 'forced gap failure'); END;")
        db.execSQL("CREATE TRIGGER fail_journal_update BEFORE UPDATE ON event_journal BEGIN SELECT RAISE(ABORT, 'forced update failure'); END;")
        try {
            holder.db().journalDao().insert(
                EventJournalEntity(
                    eventId = "evt-bad-nodefer-coord",
                    generation = "gen",
                    receivedAtEpochMs = 1_000L,
                    expiresAtEpochMs = 60_000L,
                    state = "PENDING",
                    attempts = 0,
                    failureCode = null,
                    payload = "{ bad json preserved intact",
                    packageName = pkg,
                    lossRecorded = LOSS_UNSETTLED,
                ),
            )

            // Reset call counter after initial coordinator setup, exactly as required by reviewer
            pageCallCount.set(0)

            // Must exit cleanly within bounded time (5s) without infinite loop or hanging
            withTimeout(5_000) {
                val ran = coordinator.replayPassForTesting()
                ran shouldBe true
            }

            // Exactly 1 page call / query upper bound: failed raw batch deferral must stop immediately
            pageCallCount.get() shouldBe 1

            // Exactly original payload, attempts, and state preserved intact
            val entity = journalEntity("evt-bad-nodefer-coord")
            entity.state shouldBe "PENDING"
            entity.payload shouldBe "{ bad json preserved intact"
            entity.attempts shouldBe 0
            entity.failureCode shouldBe null
            lossState("evt-bad-nodefer-coord") shouldBe LOSS_UNSETTLED
            messageCount() shouldBe 0
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS fail_gap_insert")
            db.execSQL("DROP TRIGGER IF EXISTS fail_journal_update")
        }
        Unit
    }
}
