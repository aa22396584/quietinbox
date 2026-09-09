package dev.quietinbox.platform.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.identity.IdentityResolver
import dev.quietinbox.core.model.BuildInfo
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.parser.StandardParser
import dev.quietinbox.core.reconcile.Reconciler
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.feature.settings.ReminderScheduling
import dev.quietinbox.feature.settings.SettingsViewModel
import dev.quietinbox.platform.crypto.BlobCipher
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.crypto.KeyResult
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.DemoDataRepository
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.settings.SettingsRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stop after a durable restore must surface Ok through the shipped Settings abort path
 * (abort then cancel the waiting job), not a probe that only throws.
 */
@RunWith(AndroidJUnit4::class)
class SettingsBackupAbortTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keys: KeyMaterial
    private lateinit var holder: DatabaseHolder
    private lateinit var ingest: IngestRepository
    private lateinit var mediaDir: MediaDirectory
    private lateinit var cipher: BlobCipher
    private lateinit var service: BackupService
    private lateinit var maintenance: VaultMaintenance
    private lateinit var settings: SettingsRepository
    private lateinit var vm: SettingsViewModel
    private val parser = StandardParser()
    private val identity = IdentityResolver()
    private val reconciler = Reconciler()
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
        settings = SettingsRepository(context)
        service = BackupService(context, holder, keys, cipher, mediaDir, settings, maintenance)
        vm = SettingsViewModel(
            context,
            settings,
            service,
            VaultRepository(holder, keys, mediaDir, settings, maintenance),
            object : ReminderScheduling { override fun reschedule() {} },
            DemoDataRepository(holder, context),
            BuildInfo(debug = true, flavor = "test"),
        )
        ready()
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

    private suspend fun exportOne(): File {
        val snapshot = Fixtures.snapshot(Fixtures.bigText("Alice", "keep", tag = "t1"), packageName = KnownSources.TELEGRAM, eventId = "s-abort", observedAt = 1_700_000_000_000L)
        ingest.journal(snapshot, "gen", 60_000) shouldBe true
        val batch = parser.parse(snapshot)
        val id = identity.resolve(snapshot, batch)
        val r = reconciler.reconcile(snapshot.notificationKey, batch.messages, ingest.checkpoint(id.streamKey), lookupById = { null })
        ingest.commit(snapshot, batch, id, r, "gen", null, mediaAllowed = false)
        val backup = File(context.cacheDir, "settings-abort.qibk")
        service.export(Uri.fromFile(backup), "test").shouldBeInstanceOf<BackupResult.Ok>()
        return backup
    }

    @Test
    fun settingsStopAfterCommitShowsTheRestoreNotNoChange() = runBlocking {
        val backup = exportOne()
        holder.closeAndDeleteFiles() shouldBe true
        holder.retry()
        ready()
        service.restoreProbe = { point ->
            if (point == BackupService.RestorePoint.AFTER_COMMIT) vm.abortBackup()
        }
        val collector = launch { vm.state.collect { } }
        delay(50)
        vm.import(Uri.fromFile(backup), recoveryKey)
        withTimeout(20_000) {
            while (vm.state.value.backupInProgress || vm.state.value.lastBackup == null) delay(10)
        }
        val last = vm.state.value.lastBackup
        last.shouldBeInstanceOf<BackupResult.Ok>()
        holder.db().messageDao().exportPage(0L, 10, System.currentTimeMillis()).size shouldBe 1
        collector.cancel()
        backup.delete()
        Unit
    }

    @Test
    fun settingsStopBeforeApplyLeavesTheVaultUnchanged() = runBlocking {
        val backup = exportOne()
        holder.closeAndDeleteFiles() shouldBe true
        holder.retry()
        ready()
        service.afterStaged = { vm.abortBackup() }
        val collector = launch { vm.state.collect { } }
        delay(50)
        vm.import(Uri.fromFile(backup), recoveryKey)
        withTimeout(20_000) {
            while (vm.state.value.backupInProgress || vm.state.value.lastBackup == null) delay(10)
        }
        val last = vm.state.value.lastBackup
        last.shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.ABORTED
        holder.db().messageDao().exportPage(0L, 10, System.currentTimeMillis()) shouldBe emptyList()
        collector.cancel()
        backup.delete()
        Unit
    }

    @Test
    fun settingsStopBetweenSqlCommitAndTransactionReturnShowsTheRestore() = runBlocking {
        val backup = exportOne()
        holder.closeAndDeleteFiles() shouldBe true
        holder.retry()
        ready()
        val entered = CountDownLatch(1)
        val pause = CountDownLatch(1)
        val once = AtomicBoolean(false)
        holder.db().afterEndTransaction = {
            if (once.compareAndSet(false, true)) {
                entered.countDown()
                pause.await()
            }
        }
        val collector = launch { vm.state.collect { } }
        delay(50)
        vm.import(Uri.fromFile(backup), recoveryKey)
        try {
            withTimeout(20_000) { while (entered.count > 0) delay(10) }
            vm.abortBackup()
            val mid = vm.state.value.lastBackup
            (mid == null || mid is BackupResult.Ok) shouldBe true
            pause.countDown()
            withTimeout(20_000) {
                while (vm.state.value.backupInProgress || vm.state.value.lastBackup == null) delay(10)
            }
            vm.state.value.lastBackup.shouldBeInstanceOf<BackupResult.Ok>()
            holder.db().messageDao().exportPage(0L, 10, System.currentTimeMillis()).size shouldBe 1
        } finally {
            pause.countDown()
            collector.cancel()
            holder.db().afterEndTransaction = null
            backup.delete()
        }
        Unit
    }
}
