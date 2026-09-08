package dev.quietinbox.platform.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.identity.IdentityResolver
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.core.parser.StandardParser
import dev.quietinbox.core.reconcile.Reconciler
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.platform.crypto.BlobCipher
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.crypto.KeyResult
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.MediaBlobEntity
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.settings.SettingsRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
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
 * A restore interrupted at each of its two exits (audit-2 ATOM-4). Inside the transaction the
 * rows roll back and every blob written for them goes; after the commit the rows are durable, the
 * files they point at stay, and only the blobs no inserted message references are removed.
 *
 * Negative controls: (1) move `writtenFiles.removeAll(usedFiles)` out of the transaction so the
 * `check` after it fails (the used names would still be on the cleanup list); (2) drop the
 * `catch` cleanup and the in-transaction test fails on the leaked blob.
 */
@RunWith(AndroidJUnit4::class)
class BackupCancellationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keys: KeyMaterial
    private lateinit var holder: DatabaseHolder
    private lateinit var ingest: IngestRepository
    private lateinit var mediaDir: MediaDirectory
    private lateinit var cipher: BlobCipher
    private lateinit var service: BackupService
    private lateinit var maintenance: VaultMaintenance
    private val parser = StandardParser()
    private val identity = IdentityResolver()
    private val reconciler = Reconciler()
    private lateinit var backup: File
    private lateinit var recoveryKey: String

    @Before
    fun setUp() = runBlocking {
        wipe()
        keys = KeyMaterial(context)
        holder = DatabaseHolder(context, keys)
        ingest = IngestRepository(holder)
        mediaDir = MediaDirectory(context)
        cipher = BlobCipher(keys)
        maintenance = VaultMaintenance()
        service = BackupService(context, holder, keys, cipher, mediaDir, SettingsRepository(context), maintenance)
        ready()
        // One message with a readable blob, exported, then the vault and its media wiped.
        val snapshot = Fixtures.snapshot(Fixtures.bigText("Alice", "with picture", tag = "t1"), packageName = KnownSources.TELEGRAM, eventId = "c1", observedAt = 1_700_000_000_000L)
        ingest.journal(snapshot, "gen", 60_000) shouldBe true
        val batch = parser.parse(snapshot)
        val id = identity.resolve(snapshot, batch)
        val r = reconciler.reconcile(snapshot.notificationKey, batch.messages, ingest.checkpoint(id.streamKey), lookupById = { null })
        val messageId = ingest.commit(snapshot, batch, id, r, "gen", null, mediaAllowed = false).newMessageIds.single()
        val db = holder.db()
        cipher.encryptToFile("picture".toByteArray(), mediaDir.file("blob-src")).shouldBeInstanceOf<KeyResult.Ok<Unit>>()
        val blob = db.mediaDao().insert(MediaBlobEntity(messageId = messageId, fileName = "blob-src", thumbFileName = null, mimeType = "image/png", byteCount = 7, width = 1, height = 1, state = MediaState.LOCAL_COPY.name, failureReason = null, createdAtEpochMs = 1L))
        db.messageDao().setMedia(messageId, MediaState.LOCAL_COPY.name, blob)
        backup = File(context.cacheDir, "cancel.qibk")
        service.export(Uri.fromFile(backup), "test").shouldBeInstanceOf<BackupResult.Ok>().counts.media shouldBe 1
        recoveryKey = service.recoveryKeyText().shouldBeInstanceOf<KeyResult.Ok<String>>().value
        holder.closeAndDeleteFiles() shouldBe true
        mediaDir.deleteAll() shouldBe true
        holder.retry()
        ready()
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        holder.closeAndDeleteFiles()
        wipe()
    }

    private fun wipe() {
        File(context.filesDir, "keys").deleteRecursively()
        File(context.filesDir, "media").deleteRecursively()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(context.getDatabasePath("quietinbox.vault").path + suffix).delete()
    }

    private suspend fun ready() = withTimeout(20_000) { holder.state.filterIsInstance<VaultState.Ready>().first() }

    private fun mediaFiles(): List<String> = (File(context.filesDir, "media").list() ?: emptyArray()).sorted()

    /** The restored rows: id, media state and blob id of every message a restore inserted. */
    private suspend fun restoredRows(): List<Triple<Long, String, Long?>> =
        holder.db().openHelper.writableDatabase.query("SELECT id, mediaState, mediaBlobId FROM message WHERE eventId LIKE 'restore:%' ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2))) }
        }

    private fun cancelAt(point: BackupService.RestorePoint) {
        service.restoreProbe = { if (it == point) throw CancellationException("landed at $point") }
    }

    @Test
    fun aCancellationLandingAfterTheCommitKeepsTheFilesTheRowsPointAtAndRemovesTheRest() = runBlocking {
        cancelAt(BackupService.RestorePoint.AFTER_COMMIT)
        val first = service.import(Uri.fromFile(backup), recoveryKey).shouldBeInstanceOf<BackupResult.Ok>()
        first.counts.messages shouldBe 1
        ready()
        val restored = restoredRows()
        restored.size shouldBe 1
        restored.single().second shouldBe MediaState.LOCAL_COPY.name
        val blob = holder.db().mediaDao().get(restored.single().third!!)!!
        mediaDir.file(blob.fileName).exists() shouldBe true
        mediaFiles() shouldBe listOf(blob.fileName)

        // Duplicate import: the extra prepared blob has no row and must be removed; the message is not duplicated.
        service.restoreProbe = {}
        service.import(Uri.fromFile(backup), recoveryKey).shouldBeInstanceOf<BackupResult.Ok>()
        ready()
        restoredRows().size shouldBe 1
        mediaFiles() shouldBe listOf(blob.fileName)
        Unit
    }

    @Test
    fun aBlobWhoseBytesDoNotDecodeLeavesItsMessageMarkedAndIsCountedInTheResult() = runBlocking {
        // Invalid Base64 becomes empty bytes on Android; that is not a picture, so the message
        // is restored, its media state is FAILED, and "Done" carries the count.
        val key = dev.quietinbox.platform.crypto.RecoveryKeyCodec.decode(recoveryKey)!!
        val header = ByteArray(BackupCrypto.HEADER_BYTES)
        val lines = java.io.FileInputStream(backup).use { raw ->
            raw.read(header) shouldBe header.size
            val salt = BackupCrypto.parseHeader(header)!!
            BackupCrypto.streamingAead(key, salt).newDecryptingStream(raw, header).bufferedReader(Charsets.UTF_8).readLines()
        }
        val broken = lines.map { if ("\"media\"" in it && "dataBase64" in it) it.replace(Regex("\"dataBase64\":\"[^\"]*\""), "\"dataBase64\":\"%%%%\"") else it }
        broken shouldNotBe lines
        val salt = ByteArray(BackupCrypto.SALT_BYTES) { it.toByte() }
        val newHeader = BackupCrypto.header(salt)
        val brokenFile = File(context.cacheDir, "broken-media.qibk")
        java.io.FileOutputStream(brokenFile).use { raw ->
            raw.write(newHeader)
            BackupCrypto.streamingAead(key, salt).newEncryptingStream(raw, newHeader).bufferedWriter(Charsets.UTF_8).use { w -> for (l in broken) { w.write(l); w.write("\n") } }
        }
        val result = service.import(Uri.fromFile(brokenFile), recoveryKey).shouldBeInstanceOf<BackupResult.Ok>()
        result.counts.messages shouldBe 1
        result.counts.media shouldBe 0
        result.mediaNotRestored shouldBe 1
        val rows = restoredRows()
        rows.size shouldBe 1
        rows.single().second shouldBe MediaState.FAILED.name
        rows.single().third shouldBe null
        mediaFiles() shouldBe emptyList()
        Unit
    }

    @Test
    fun aCancellationLandingInsideTheTransactionRollsTheRowsBackAndRemovesEveryFile() = runBlocking {
        cancelAt(BackupService.RestorePoint.IN_TRANSACTION)
        val result = service.import(Uri.fromFile(backup), recoveryKey)
        result.shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.ABORTED
        ready()
        restoredRows() shouldBe emptyList()
        mediaFiles() shouldBe emptyList()
        Unit
    }
}
