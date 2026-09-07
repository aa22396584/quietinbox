package dev.quietinbox.platform.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.model.GapPrecision
import dev.quietinbox.core.model.GapReason
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.storage.db.CheckpointEntity
import dev.quietinbox.platform.storage.db.ConversationEntity
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.DeletionSuppressionEntity
import dev.quietinbox.platform.storage.db.DiagnosticEventEntity
import dev.quietinbox.platform.storage.db.MediaBlobEntity
import dev.quietinbox.platform.storage.db.MessageEntity
import dev.quietinbox.platform.storage.db.SummaryObservationEntity
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.IngestRepository
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
    private lateinit var ingest: IngestRepository

    private val pkg = "com.example.chat"

    @Before
    fun setUp() {
        wipe()
        keys = KeyMaterial(context)
        holder = DatabaseHolder(context, keys)
        sources = SourceRepository(holder, MediaDirectory(context))
        health = HealthRepository(holder)
        ingest = IngestRepository(holder)
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

    private suspend fun allGaps() = holder.db().healthDao().observeGaps(100).first()

    /** A row as a release up to 0.1.3 left it: pending, and with its loss recorded nowhere. */
    private suspend fun journalCarriedOver(eventId: String) {
        val snapshot = Fixtures.snapshot(Fixtures.base(title = "t", text = "b"), packageName = pkg, eventId = eventId)
        ingest.journal(snapshot, "gen", 60_000) shouldBe true
    }

    private suspend fun recordLoss() {
        health.recordGap(1_000, 2_000, GapReason.MESSAGES_DROPPED, GapPrecision.BOUNDED, 2_000, pkg)
    }

    /**
     * Everything `remove(deleteData = true)` deletes, so a rollback has something to be a rollback
     * *of*. The two failure controls that existed built a source, a pending row and a gap only, and
     * threw inside the callback — before the graph deletions had started — so "the whole graph
     * stays in place" was a claim about rows the test never created (round 36 Codex I4).
     */
    private suspend fun seedGraph(): String {
        val db = holder.db()
        val conversationId = db.conversationDao().insert(
            ConversationEntity(
                packageName = pkg, profileKey = "$pkg|", accountKey = null, identityKey = "id-1",
                identityConfidence = "EXACT", title = "Group", isGroup = true, pinned = false, archived = false,
                createdAtEpochMs = 1_000, lastActivityEpochMs = 1_000, lastViewedEpochMs = null,
                messageCount = 1, ambiguousCount = 0, summaryOnlyCount = 0,
                lastMessagePreview = "hi", lastSenderName = "Ana",
            ),
        )
        val messageId = db.messageDao().insert(
            MessageEntity(
                conversationId = conversationId, sourceMessageId = null, senderName = "Ana", senderKey = "ana",
                isSelf = false, body = "hi", kind = "TEXT", sourceTimestampEpochMs = 1_000,
                timestampQuality = "SOURCE", observedAtEpochMs = 1_000, postedAtEpochMs = 1_000,
                origin = "LIVE", contentStatus = "COMPLETE", dedupState = "UNIQUE", revisionCount = 0,
                observationCount = 1, mediaState = "NONE", mediaBlobId = null, mediaUri = null,
                mediaMimeType = null, fingerprint = "fp-1", eventId = "evt-graph", sortKey = 1_000,
                expiresAtEpochMs = null,
            ),
        )
        val fileName = "graph-blob.bin"
        MediaDirectory(context).file(fileName).also { it.parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        db.mediaDao().insert(
            MediaBlobEntity(
                messageId = messageId, fileName = fileName, thumbFileName = null, mimeType = "image/png",
                byteCount = 3, width = 1, height = 1, state = "READY", failureReason = null, createdAtEpochMs = 1_000,
            ),
        )
        db.checkpointDao().upsert(
            CheckpointEntity(
                streamKey = "$pkg|stream", packageName = pkg, notificationKey = "key", windowJson = "[]",
                closed = false, parserId = "standard", parserVersion = "1", generation = "gen",
                updatedAtEpochMs = 1_000,
            ),
        )
        db.suppressionDao().upsert(DeletionSuppressionEntity("$pkg|scope", "fp-1", 9_000_000))
        db.healthDao().insertSummary(
            SummaryObservationEntity(packageName = pkg, observedAtEpochMs = 1_000, messageCount = 5, conversationCount = 1, eventId = "evt-sum"),
        )
        db.diagnosticsDao().insert(DiagnosticEventEntity(code = "TEST", detail = null, packageName = pkg, atEpochMs = 1_000))
        return fileName
    }

    private suspend fun count(sql: String, vararg args: Any): Int =
        holder.db().openHelper.writableDatabase.query(sql, args).use { it.moveToFirst(); it.getInt(0) }

    /**
     * One count per table `remove(deleteData = true)` touches, read straight from the vault rather
     * than through DAOs written for the purpose: the assertion is about rows surviving, and a
     * production query added only to let a test look is one more thing to keep true.
     */
    private suspend fun graphCounts(): List<Int> = listOf(
        count("SELECT COUNT(*) FROM conversation WHERE packageName = ?", pkg),
        count("SELECT COUNT(*) FROM message WHERE conversationId IN (SELECT id FROM conversation WHERE packageName = ?)", pkg),
        count("SELECT COUNT(*) FROM media_blob WHERE messageId IN (SELECT m.id FROM message m JOIN conversation c ON m.conversationId = c.id WHERE c.packageName = ?)", pkg),
        count("SELECT COUNT(*) FROM notification_checkpoint WHERE packageName = ?", pkg),
        count("SELECT COUNT(*) FROM deletion_suppression WHERE scopeKey LIKE ?", "$pkg|%"),
        count("SELECT COUNT(*) FROM summary_observation WHERE packageName = ?", pkg),
        count("SELECT COUNT(*) FROM local_diagnostic_event WHERE packageName = ?", pkg),
        count("SELECT COUNT(*) FROM source_configuration WHERE packageName = ?", pkg),
    )

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
    fun disablingASourceSettlesItsPendingRowsAndDiscardsThemTogether() = runBlocking {
        ready()
        addSource()
        journalCarriedOver("evt-carried")

        // The shape the coordinator builds for this path (round 34 C2). The discard empties the
        // payloads in the same statement that settles the rows, so the loss they arrived with has
        // to be read and written first — and inside this transaction, or a crash between the two
        // leaves capture stopped with nothing saying what was already gone.
        val changed = sources.setEnabled(pkg, false) {
            health.openGap(2_000, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, 2_000, pkg)
            for (s in ingest.pendingJournalForPackage(pkg).snapshots) ingest.claimEventLoss(s.eventId) { recordLoss() }
            ingest.discardPendingJournal(pkg)
        }

        changed shouldBe true
        ingest.isJournalPending("evt-carried") shouldBe false
        ingest.pendingJournalForPackage(pkg).snapshots.isEmpty() shouldBe true
        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 1
        Unit
    }

    @Test
    fun aDiscardWhoseSettlementFailsLeavesTheRowAndTheSourceWhereTheyWere() = runBlocking {
        ready()
        addSource()
        journalCarriedOver("evt-carried-2")

        runCatching {
            sources.setEnabled(pkg, false) {
                for (s in ingest.pendingJournalForPackage(pkg).snapshots) ingest.claimEventLoss(s.eventId) { error("the gap write failed") }
                ingest.discardPendingJournal(pkg)
            }
        }.isFailure shouldBe true

        // Nothing half-happened: the source is still on, the row still pending with its payload,
        // and the claim unspent, so the next attempt still finds the evidence it needs.
        sources.get(pkg)!!.enabled shouldBe true
        ingest.isJournalPending("evt-carried-2") shouldBe true
        allGaps().isEmpty() shouldBe true
        ingest.claimEventLoss("evt-carried-2") { recordLoss() } shouldBe true
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

    /**
     * Both remove branches only ever had happy-path tests, so a callback quietly moved outside the
     * transaction would still have passed all of them (round 35 Codex I6). These are the controls:
     * a failure anywhere inside the removal leaves every part of it where it was.
     */
    @Test
    fun aRemoveWithoutDataWhoseCallbackFailsChangesNothing() = runBlocking {
        ready()
        addSource()
        journalCarriedOver("evt-remove-1")
        sources.setEnabled(pkg, false) {
            health.openGap(2_000, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, 2_000, pkg)
        } shouldBe true

        runCatching { sources.remove(pkg, deleteData = false) { error("the gap close failed") } }.isFailure shouldBe true

        // The source is still there, its pending row still pending with its payload, and its gap
        // still open — a half-removed source with a closed gap would say capture had resumed.
        sources.get(pkg)!!.enabled shouldBe false
        ingest.isJournalPending("evt-remove-1") shouldBe true
        ingest.pendingJournalForPackage(pkg).snapshots.size shouldBe 1
        openGaps().count { it.packageName == pkg } shouldBe 1
        Unit
    }

    @Test
    fun aRemoveWithDataWhoseCallbackFailsLeavesTheWholeGraphInPlace() = runBlocking {
        ready()
        addSource()
        journalCarriedOver("evt-remove-2")
        sources.setEnabled(pkg, false) {
            health.openGap(2_000, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, 2_000, pkg)
        } shouldBe true

        runCatching {
            sources.remove(pkg, deleteData = true) {
                health.closeOpenGapsForSource(4_000, pkg, GapReason.SOURCE_DISABLED_BY_USER)
                error("forgetting the source failed")
            }
        }.isFailure shouldBe true

        sources.get(pkg)!!.packageName shouldBe pkg
        ingest.isJournalPending("evt-remove-2") shouldBe true
        // The close that ran before the failure is rolled back with it: the gap is open and named.
        val open = openGaps().filter { it.packageName == pkg }
        open.size shouldBe 1
        open.single().endEpochMs shouldBe null
        Unit
    }

    /**
     * Round 36, Codex I4: the rollback of the *graph*, with the failure landing in the middle of it.
     *
     * The two controls above throw inside the callback, which runs before a single graph deletion
     * has started — so they show that the callback's own writes roll back, and nothing more. Here
     * the callback succeeds and the removal gets as far as the diagnostics delete, the last of the
     * seven, before a trigger aborts it. Everything the six earlier statements had already deleted
     * inside the transaction has to come back, and the media file — deleted after the transaction,
     * from the list the transaction returned — must never have been touched at all.
     */
    @Test
    fun aRemoveThatFailsPartWayThroughTheGraphRollsAllOfItBack() = runBlocking {
        ready()
        addSource()
        journalCarriedOver("evt-graph-rollback")
        val fileName = seedGraph()
        val before = graphCounts()
        before shouldBe listOf(1, 1, 1, 1, 1, 1, 1, 1)

        val db = holder.db().openHelper.writableDatabase
        db.execSQL(
            "CREATE TRIGGER fail_diagnostics_delete BEFORE DELETE ON local_diagnostic_event " +
                "BEGIN SELECT RAISE(ABORT, 'the diagnostics delete failed'); END",
        )
        try {
            runCatching { sources.remove(pkg, deleteData = true) { recordLoss() } }.isFailure shouldBe true
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS fail_diagnostics_delete")
        }

        // Every row is back, including the ones six statements had already deleted.
        graphCounts() shouldBe before
        // And the parts the earlier controls covered, still: the journal row with its payload, and
        // the callback's own gap.
        ingest.isJournalPending("evt-graph-rollback") shouldBe true
        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 0
        // The file is the one thing outside the transaction. It is deleted only from the list a
        // committed transaction returns, so a failure must leave it where it is — a deleted file
        // whose row came back is the shape that shows an empty bubble for ever.
        MediaDirectory(context).file(fileName).exists() shouldBe true
        Unit
    }

    /**
     * The `deleteData = false` control, made discriminating.
     *
     * Its callback only threw, so moving the callback *before* the transaction changed nothing it
     * asserted: the throw came first either way (round 36 Codex I4). Writing something observable
     * before the throw is what tells the two apart — outside the transaction that write survives.
     */
    @Test
    fun aRemoveWithoutDataRollsBackWhatItsCallbackHadAlreadyWritten() = runBlocking {
        ready()
        addSource()
        journalCarriedOver("evt-remove-observable")

        runCatching {
            sources.remove(pkg, deleteData = false) {
                recordLoss()
                error("the gap close failed")
            }
        }.isFailure shouldBe true

        // The gap the callback wrote before it threw is gone with everything else.
        allGaps().count { it.reason == GapReason.MESSAGES_DROPPED.name } shouldBe 0
        sources.get(pkg)!!.packageName shouldBe pkg
        ingest.isJournalPending("evt-remove-observable") shouldBe true
        Unit
    }
}
