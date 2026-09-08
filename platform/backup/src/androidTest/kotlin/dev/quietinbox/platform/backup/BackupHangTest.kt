package dev.quietinbox.platform.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.identity.IdentityResolver
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.parser.StandardParser
import dev.quietinbox.core.reconcile.Reconciler
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.platform.crypto.BlobCipher
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.crypto.KeyResult
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.settings.SettingsRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference



/**
 * Import must not hold exclusive maintenance across an uncooperative `InputStream.read`.
 * A timeout around the exclusive block would still leave the lock held; the read is outside it.
 */
@RunWith(AndroidJUnit4::class)
class BackupHangTest {
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
    private lateinit var recoveryKey: String
    private lateinit var releaseHung: CountDownLatch

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
        releaseHung = CountDownLatch(1)
        ready()
        recoveryKey = service.recoveryKeyText().shouldBeInstanceOf<KeyResult.Ok<String>>().value
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        releaseHung.countDown()
        holder.closeAndDeleteFiles()
        wipe()
    }

    private fun wipe() {
        File(context.filesDir, "keys").deleteRecursively()
        File(context.filesDir, "media").deleteRecursively()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(context.getDatabasePath("quietinbox.vault").path + suffix).delete()
    }

    private suspend fun ready() = withTimeout(20_000) { holder.state.filterIsInstance<VaultState.Ready>().first() }

    @Test
    fun cancellingANeverReturningReadReleasesTheCallerAndDoesNotHoldExclusive() = runBlocking {
        val entered = CountDownLatch(1)
        val lock = Any()
        service.openInput = {
            object : InputStream() {
                override fun read(): Int {
                    synchronized(lock) {
                        entered.countDown()
                        releaseHung.await()
                        return -1
                    }
                }
                override fun close() {
                    // Same monitor as read: a caller that close()s here would wait forever.
                    synchronized(lock) { }
                }
            }
        }
        val job = launch(Dispatchers.IO) { service.import(Uri.parse("content://quietinbox.test/hang"), recoveryKey) }
        withTimeout(5_000) { while (entered.count > 0) delay(10) }
        maintenance.isActive shouldBe false
        withTimeout(5_000) { maintenance.exclusive { 7 } shouldBe 7 }
        job.cancel()
        withTimeout(5_000) { job.join() }
        maintenance.isActive shouldBe false
        withTimeout(5_000) { maintenance.exclusive { 9 } shouldBe 9 }
        Unit
    }

    @Test
    fun aWriteAfterKeyEpochChangeDoesNotLand() = runBlocking {
        val snapshot = Fixtures.snapshot(Fixtures.bigText("Alice", "keep", tag = "t1"), packageName = KnownSources.TELEGRAM, eventId = "h1", observedAt = 1_700_000_000_000L)
        ingest.journal(snapshot, "gen", 60_000) shouldBe true
        val batch = parser.parse(snapshot)
        val id = identity.resolve(snapshot, batch)
        val r = reconciler.reconcile(snapshot.notificationKey, batch.messages, ingest.checkpoint(id.streamKey), lookupById = { null })
        ingest.commit(snapshot, batch, id, r, "gen", null, mediaAllowed = false)
        val backup = File(context.cacheDir, "epoch.qibk")
        service.export(Uri.fromFile(backup), "test").shouldBeInstanceOf<BackupResult.Ok>()
        holder.closeAndDeleteFiles() shouldBe true
        holder.retry()
        ready()
        val epoch = keys.epoch
        service.afterStaged = { keys.destroyAll() }
        val result = service.import(Uri.fromFile(backup), recoveryKey)
        result.shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.KEY_UNAVAILABLE
        keys.epoch shouldBe epoch + 1
        Unit
    }

    @Test
    fun openAndApplyDoNotRunOnMain() = runBlocking {
        val snapshot = Fixtures.snapshot(Fixtures.bigText("Alice", "keep", tag = "t1"), packageName = KnownSources.TELEGRAM, eventId = "h2", observedAt = 1_700_000_000_000L)
        ingest.journal(snapshot, "gen", 60_000) shouldBe true
        val batch = parser.parse(snapshot)
        val id = identity.resolve(snapshot, batch)
        val r = reconciler.reconcile(snapshot.notificationKey, batch.messages, ingest.checkpoint(id.streamKey), lookupById = { null })
        ingest.commit(snapshot, batch, id, r, "gen", null, mediaAllowed = false)
        val backup = File(context.cacheDir, "threads.qibk")
        service.export(Uri.fromFile(backup), "test").shouldBeInstanceOf<BackupResult.Ok>()
        val recovery = service.recoveryKeyText().shouldBeInstanceOf<KeyResult.Ok<String>>().value
        holder.closeAndDeleteFiles() shouldBe true
        holder.retry()
        ready()
        val openOn = AtomicReference<String>()
        val applyOn = AtomicReference<String>()
        val realOpen = service.openInput
        service.openInput = { uri ->
            openOn.set(Thread.currentThread().name)
            realOpen(uri)
        }
        service.applyThreadProbe = { applyOn.set(Thread.currentThread().name) }
        withTimeout(20_000) {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                service.import(Uri.fromFile(backup), recovery).shouldBeInstanceOf<BackupResult.Ok>()
            }
        }
        openOn.get()!!.startsWith("main") shouldBe false
        applyOn.get()!!.startsWith("main") shouldBe false
        Unit
    }
}
