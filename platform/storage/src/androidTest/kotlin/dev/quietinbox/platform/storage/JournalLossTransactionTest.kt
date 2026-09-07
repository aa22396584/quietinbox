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
import io.kotest.matchers.shouldBe
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
}
