package dev.quietinbox.platform.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.model.GapPrecision
import dev.quietinbox.core.model.GapReason
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.retention.MediaDirectory
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
 * A source policy change and the gap that records it are one write (round 33).
 *
 * The coordinator's own tests run against a mocked repository, so they can only show that it hands
 * the gap to the repository rather than writing it itself. What that hand-off is worth is decided
 * here, on a real SQLCipher vault: whether the two rows really commit together, and whether setting
 * a flag to the value it already holds really writes nothing.
 *
 * The last test is the other half of the same promise — what may later delete those rows.
 */
@RunWith(AndroidJUnit4::class)
class SourcePolicyTransactionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keys: KeyMaterial
    private lateinit var holder: DatabaseHolder
    private lateinit var sources: SourceRepository
    private lateinit var health: HealthRepository

    private val pkg = "com.example.chat"

    @Before
    fun setUp() {
        wipe()
        keys = KeyMaterial(context)
        holder = DatabaseHolder(context, keys)
        sources = SourceRepository(holder, MediaDirectory(context))
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

    private suspend fun addSource() {
        sources.enable(pkg, pkg, adapterId = null, now = 1_000)
    }

    private suspend fun openGaps() = health.openSourceGaps()

    @Test
    fun disablingWritesTheFlagAndItsGapTogether() = runBlocking {
        ready()
        addSource()

        val changed = sources.setEnabled(pkg, false) {
            health.openGap(2_000, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, 2_000, pkg)
        }

        changed shouldBe true
        sources.get(pkg)!!.enabled shouldBe false
        openGaps().count { it.packageName == pkg && it.reason == GapReason.SOURCE_DISABLED_BY_USER.name } shouldBe 1
        Unit
    }

    @Test
    fun settingTheFlagToTheValueItAlreadyHasWritesNothing() = runBlocking {
        ready()
        addSource()
        sources.setEnabled(pkg, false) {
            health.openGap(2_000, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, 2_000, pkg)
        } shouldBe true

        // The second disable is not a second event: one interval, not two stacked on each other.
        val changedAgain = sources.setEnabled(pkg, false) {
            health.openGap(3_000, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, 3_000, pkg)
        }

        changedAgain shouldBe false
        openGaps().count { it.packageName == pkg } shouldBe 1
        Unit
    }

    @Test
    fun aFlagChangeWhoseGapFailsIsNotCommittedEither() = runBlocking {
        ready()
        addSource()

        // The whole point of the transaction: capture must never be left stopped with nothing on
        // the health page saying so. If the record cannot be written, the policy does not move.
        runCatching {
            sources.setEnabled(pkg, false) { error("the gap write failed") }
        }.isFailure shouldBe true

        sources.get(pkg)!!.enabled shouldBe true
        openGaps().none { it.packageName == pkg } shouldBe true
        Unit
    }

    @Test
    fun removingASourceClosesItsGapInTheSameTransaction() = runBlocking {
        ready()
        addSource()
        sources.setEnabled(pkg, false) {
            health.openGap(2_000, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, 2_000, pkg)
        }
        openGaps().count { it.packageName == pkg } shouldBe 1

        sources.remove(pkg, deleteData = false) {
            health.closeOpenGapsForSource(4_000, pkg, GapReason.SOURCE_DISABLED_BY_USER, GapReason.SOURCE_PAUSED_BY_USER)
        }

        // Nothing could ever have closed it afterwards: the source it names no longer exists.
        sources.get(pkg) shouldBe null
        openGaps().none { it.packageName == pkg } shouldBe true
        Unit
    }

    @Test
    fun forgettingASourceKeepsTheIntervalAndDropsTheName() = runBlocking {
        ready()
        addSource()
        sources.setEnabled(pkg, false) {
            health.openGap(2_000, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, 2_000, pkg)
        }

        health.forgetGapSource(pkg)

        // "Remove and delete this source's data" takes the name. Deleting the interval instead
        // would hide a loss the user had already been shown.
        val open = openGaps()
        open.count { it.reason == GapReason.SOURCE_DISABLED_BY_USER.name } shouldBe 1
        open.none { it.packageName == pkg } shouldBe true
        Unit
    }

    @Test
    fun retentionExpiresClosedGapsAndKeepsTheOnesStillHappening() = runBlocking {
        ready()
        addSource()
        val db = holder.db()
        // A source the user disabled long ago: old enough for the sweep, and still not captured.
        health.openGap(1_000, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, 1_000, pkg)
        // A window that opened and closed just as long ago: over, and safe to forget.
        health.recordGap(1_000, 2_000, GapReason.UNKNOWN, GapPrecision.EXACT, 1_000, pkg)

        db.healthDao().deleteGapsBefore(before = 10_000)

        // Deleting the open one would take the only thing on the health page saying capture is
        // still missing for that source — a gap hidden by housekeeping.
        val left = db.healthDao().openGaps(listOf(GapReason.SOURCE_DISABLED_BY_USER.name))
        left.count { it.packageName == pkg } shouldBe 1
        // ...and the closed one really was swept, so this is not simply a sweep that does nothing.
        val all = db.healthDao().observeGaps(100).first()
        all.size shouldBe 1
        all.single().endEpochMs shouldBe null
        Unit
    }

    @Test
    fun removingASourceAndItsDataTakesTheNameOffItsGapsInOneGo() = runBlocking {
        ready()
        addSource()
        sources.setEnabled(pkg, false) {
            health.openGap(2_000, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, 2_000, pkg)
        }
        // Another source's interval, to prove the removal is scoped and does not take it too.
        health.openGap(2_500, GapReason.SOURCE_PAUSED_BY_USER, GapPrecision.EXACT, 2_500, "com.example.other")

        // The path the coordinator actually takes, end to end (round 34 I3): close and forget both
        // run inside remove's own transaction, not as two calls that could half-happen.
        sources.remove(pkg, deleteData = true) {
            health.closeOpenGapsForSource(4_000, pkg, GapReason.SOURCE_DISABLED_BY_USER, GapReason.SOURCE_PAUSED_BY_USER)
            health.forgetGapSource(pkg)
        }

        val all = holder.db().healthDao().observeGaps(100).first()
        // The removed source's interval is closed and anonymous; it is still there, because it is
        // the record that capture stopped.
        all.count { it.packageName == pkg } shouldBe 0
        all.count { it.packageName == null && it.endEpochMs != null } shouldBe 1
        // The other source is untouched: still named, still open.
        all.count { it.packageName == "com.example.other" && it.endEpochMs == null } shouldBe 1
        Unit
    }
}
