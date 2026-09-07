package dev.quietinbox.platform.media

import android.graphics.Bitmap
import android.graphics.Color
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
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.settings.SettingsRepository
import io.kotest.matchers.collections.shouldContain
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
 * A media copy that reaches its linking transaction after the message was deleted must leave no
 * blob row and no file (audit-2 MED-7): `media_blob` has no foreign key to `message`, so the row
 * used to outlive the message as an orphan until a sweep happened to find it. The seam fires after
 * the files are written and before the transaction, which is where a delete can land.
 *
 * Negative control: drop the `!= 1` check in `MediaCopier.store` and the race test fails on the
 * blob count. The positive control is the second test, the same copy with the message still there.
 */
@RunWith(AndroidJUnit4::class)
class MediaDeletionRaceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keys: KeyMaterial
    private lateinit var holder: DatabaseHolder
    private lateinit var ingest: IngestRepository
    private lateinit var mediaDir: MediaDirectory
    private lateinit var cipher: BlobCipher
    private lateinit var settings: SettingsRepository
    private lateinit var maintenance: VaultMaintenance
    private lateinit var copier: MediaCopier
    private val parser = StandardParser()
    private val identity = IdentityResolver()
    private val reconciler = Reconciler()

    @Before
    fun setUp() = runBlocking {
        wipe()
        keys = KeyMaterial(context)
        holder = DatabaseHolder(context, keys)
        ingest = IngestRepository(holder)
        mediaDir = MediaDirectory(context)
        cipher = BlobCipher(keys)
        settings = SettingsRepository(context)
        settings.setMediaCopy(true)
        settings.setMediaDisclosureAccepted(true)
        maintenance = VaultMaintenance()
        copier = MediaCopier(MediaStreams(context), holder, cipher, mediaDir, settings, maintenance)
        withTimeout(20_000) { holder.state.filterIsInstance<VaultState.Ready>().first() }
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

    /** One committed message whose picture is still to be copied from the notification bitmap. */
    private suspend fun pendingMessage(): Long {
        val shape = Fixtures.bigText("Alice", "photo", tag = "p1").copy(hasPicture = true)
        val snapshot = Fixtures.snapshot(shape, packageName = KnownSources.TELEGRAM, eventId = "m1", observedAt = 1_700_000_000_000L)
        ingest.journal(snapshot, "gen", 60_000) shouldBe true
        val batch = parser.parse(snapshot)
        val id = identity.resolve(snapshot, batch)
        val r = reconciler.reconcile(snapshot.notificationKey, batch.messages, ingest.checkpoint(id.streamKey), lookupById = { null })
        val outcome = ingest.commit(snapshot, batch, id, r, "gen", null, mediaAllowed = true)
        return outcome.pendingMediaMessageIds.single()
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    private fun mediaFiles(): List<String> = (File(context.filesDir, "media").list() ?: emptyArray()).sorted()

    private suspend fun blobCount(): Int =
        holder.db().openHelper.writableDatabase.query("SELECT COUNT(*) FROM media_blob").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun aMessageDeletedWhileItsCopyIsInFlightLeavesNoBlobRowAndNoFile() = runBlocking {
        val id = pendingMessage()
        copier.beforeLink = { mid -> holder.db().messageDao().delete(listOf(mid)) }
        copier.copyPending(listOf(id), bitmap())
        holder.db().messageDao().get(id) shouldBe null
        blobCount() shouldBe 0
        holder.db().mediaDao().orphans() shouldBe emptyList()
        mediaFiles() shouldBe emptyList()
        Unit
    }

    @Test
    fun aCopyWhoseMessageIsStillThereLinksTheBlobAndKeepsItsFile() = runBlocking {
        val id = pendingMessage()
        copier.copyPending(listOf(id), bitmap())
        val row = holder.db().messageDao().get(id)!!
        row.mediaState shouldBe MediaState.LOCAL_COPY.name
        val blob = holder.db().mediaDao().get(row.mediaBlobId!!)!!
        mediaFiles() shouldContain blob.fileName
        holder.db().mediaDao().orphans() shouldBe emptyList()
        Unit
    }
}
