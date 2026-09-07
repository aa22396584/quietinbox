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
import dev.quietinbox.platform.crypto.RecoveryKeyCodec
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * A restore that is refused must leave the vault exactly as it was (audit-2 ATOM-1, ATOM-2, ATOM-3).
 * Each case takes a real `.qibk`, damages it or the key or the free space, runs it through
 * [BackupService.import] on a populated vault, and compares every row count and every media file
 * byte for byte. The negative control is the happy path in [BackupRoundTripTest]: the same import
 * with the right key on the same file does change the counts.
 */
@RunWith(AndroidJUnit4::class)
class BackupRejectionTest {
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
        val snapshot = Fixtures.snapshot(Fixtures.bigText("Alice", "with picture", tag = "t1"), packageName = KnownSources.TELEGRAM, eventId = "r1", observedAt = 1_700_000_000_000L)
        ingest.journal(snapshot, "gen", 60_000) shouldBe true
        val batch = parser.parse(snapshot)
        val id = identity.resolve(snapshot, batch)
        val r = reconciler.reconcile(snapshot.notificationKey, batch.messages, ingest.checkpoint(id.streamKey), lookupById = { null })
        val messageId = ingest.commit(snapshot, batch, id, r, "gen", null, mediaAllowed = false).newMessageIds.single()
        val db = holder.db()
        cipher.encryptToFile("picture".toByteArray(), mediaDir.file("blob-src")).shouldBeInstanceOf<KeyResult.Ok<Unit>>()
        val blob = db.mediaDao().insert(MediaBlobEntity(messageId = messageId, fileName = "blob-src", thumbFileName = null, mimeType = "image/png", byteCount = 7, width = 1, height = 1, state = MediaState.LOCAL_COPY.name, failureReason = null, createdAtEpochMs = 1L))
        db.messageDao().setMedia(messageId, MediaState.LOCAL_COPY.name, blob)
        backup = File(context.cacheDir, "reject.qibk")
        service.export(Uri.fromFile(backup), "test").shouldBeInstanceOf<BackupResult.Ok>().counts.media shouldBe 1
        recoveryKey = service.recoveryKeyText().shouldBeInstanceOf<KeyResult.Ok<String>>().value
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

    /**
     * The restore's claim that a refusal left the vault unchanged: every column of every row it
     * can touch, plus every media file's bytes. Counts alone would miss `UPDATE message SET body=…`
     * (round 40, Codex I3).
     */
    private suspend fun vaultFingerprint(): Map<String, Any> {
        val sql = holder.db().openHelper.writableDatabase
        fun table(name: String): List<String> {
            val rows = ArrayList<String>()
            sql.query("SELECT * FROM $name ORDER BY rowid").use { c ->
                val cols = c.columnCount
                while (c.moveToNext()) {
                    rows += (0 until cols).joinToString("\u001f") { i ->
                        if (c.isNull(i)) "N" else "S${c.getString(i).length}:${c.getString(i)}"
                    }
                }
            }
            return rows
        }
        val files = (File(context.filesDir, "media").listFiles() ?: emptyArray()).sortedBy { it.name }.associate { it.name to it.readBytes().toList() }
        return mapOf(
            "source" to table("source_configuration"),
            "conversation" to table("conversation"),
            "message" to table("message"),
            "revision" to table("message_revision"),
            "media_blob" to table("media_blob"),
            "search_token" to table("search_token"),
            "diagnostic" to table("local_diagnostic_event"),
            "files" to files,
        )
    }

    private suspend fun refused(file: File, key: String, reason: BackupResult.Reason) {
        val before = vaultFingerprint()
        service.import(Uri.fromFile(file), key).shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe reason
        ready()
        vaultFingerprint() shouldBe before
    }

    private fun damaged(name: String, edit: (RandomAccessFile) -> Unit): File {
        val copy = File(context.cacheDir, name)
        FileInputStream(backup).use { src -> FileOutputStream(copy).use { src.copyTo(it) } }
        RandomAccessFile(copy, "rw").use(edit)
        return copy
    }

    @Test
    fun aWrongRecoveryKeyIsRefusedAndChangesNothing() = runBlocking {
        val wrong = RecoveryKeyCodec.encode(ByteArray(RecoveryKeyCodec.KEY_BYTES) { (it * 7 + 3).toByte() })
        wrong shouldNotBe recoveryKey
        refused(backup, wrong, BackupResult.Reason.WRONG_KEY_OR_TAMPERED)
    }

    @Test
    fun aFlippedCiphertextByteIsRefusedAsTamperedAndChangesNothing() = runBlocking {
        val tampered = damaged("tampered.qibk") { raf ->
            val at = BackupCrypto.HEADER_BYTES + (raf.length() - BackupCrypto.HEADER_BYTES) / 2
            raf.seek(at)
            val b = raf.read()
            raf.seek(at)
            raf.write(b xor 0x55)
        }
        refused(tampered, recoveryKey, BackupResult.Reason.WRONG_KEY_OR_TAMPERED)
    }

    @Test
    fun aHalfCopiedFileIsRefusedUnderTheLabelThatNamesAnIncompleteFileAndChangesNothing() = runBlocking {
        // Tink refuses the cut segment before the stager can miss the end record, so the reason is
        // the AEAD one; its label says "modified or incomplete", never only "wrong key".
        val cut = damaged("cut.qibk") { raf -> raf.setLength(BackupCrypto.HEADER_BYTES + (raf.length() - BackupCrypto.HEADER_BYTES) * 6 / 10) }
        refused(cut, recoveryKey, BackupResult.Reason.WRONG_KEY_OR_TAMPERED)
    }

    @Test
    fun aFileCutInsideTheHeaderIsRefusedAsIncompleteAndChangesNothing() = runBlocking {
        // Half a header still starts with the magic and the version; the read count is what tells
        // a half-copied file from a foreign one.
        val stub = damaged("stub.qibk") { raf -> raf.setLength((BackupCrypto.HEADER_BYTES / 2).toLong()) }
        refused(stub, recoveryKey, BackupResult.Reason.TRUNCATED)
    }

    @Test
    fun aFileThatIsNotABackupIsRefusedAsABadHeaderAndChangesNothing() = runBlocking {
        val foreign = damaged("foreign.qibk") { raf -> raf.seek(0); raf.write("PK\u0003\u0004".toByteArray()) }
        refused(foreign, recoveryKey, BackupResult.Reason.BAD_HEADER)
        Unit
    }

    @Test
    fun aBodyRewriteThatDoesNotChangeRowCountsIsVisibleToTheFingerprint() = runBlocking {
        service.import(Uri.fromFile(backup), recoveryKey).shouldBeInstanceOf<BackupResult.Ok>()
        ready()
        val before = vaultFingerprint()
        holder.db().openHelper.writableDatabase.execSQL("UPDATE message SET body='mutated'")
        val afterBody = vaultFingerprint()
        afterBody shouldNotBe before
        holder.db().openHelper.writableDatabase.execSQL(
            "INSERT INTO local_diagnostic_event (code, detail, packageName, atEpochMs) VALUES ('x','y',null,1)",
        )
        vaultFingerprint() shouldNotBe afterBody
        Unit
    }

    @Test
    fun aVaultWithoutRoomForTheMediaRefusesBeforeWritingAnything() = runBlocking {
        service.freeBytes = { 0L }
        refused(backup, recoveryKey, BackupResult.Reason.LOW_SPACE)
        (File(context.filesDir, "media").list() ?: emptyArray()).toList() shouldBe listOf("blob-src")
        Unit
    }
}
