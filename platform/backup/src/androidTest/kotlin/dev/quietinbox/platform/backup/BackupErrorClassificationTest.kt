package dev.quietinbox.platform.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.platform.crypto.BlobCipher
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.crypto.KeyResult
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.settings.SettingsRepository
import io.kotest.matchers.shouldBe
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
import java.io.IOException

/**
 * Expected provider/apply failures remain user-facing backup results, while JVM fatal errors cross
 * the detached worker boundary exceptionally. Each fatal case is time-bounded so the test also
 * proves that the worker completes its deferred instead of orphaning the caller.
 */
@RunWith(AndroidJUnit4::class)
class BackupErrorClassificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keys: KeyMaterial
    private lateinit var holder: DatabaseHolder
    private lateinit var mediaDir: MediaDirectory
    private lateinit var service: BackupService
    private lateinit var backup: File
    private lateinit var recoveryKey: String

    @Before
    fun setUp() = runBlocking {
        wipe()
        keys = KeyMaterial(context)
        holder = DatabaseHolder(context, keys)
        mediaDir = MediaDirectory(context)
        service = BackupService(
            context,
            holder,
            keys,
            BlobCipher(keys),
            mediaDir,
            SettingsRepository(context),
            VaultMaintenance(),
        )
        withTimeout(20_000) { holder.state.filterIsInstance<VaultState.Ready>().first() }
        backup = File(context.cacheDir, "error-classification.qibk")
        service.export(Uri.fromFile(backup), "test").shouldBeInstanceOf<BackupResult.Ok>()
        recoveryKey = service.recoveryKeyText().shouldBeInstanceOf<KeyResult.Ok<String>>().value
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        holder.closeAndDeleteFiles()
        backup.delete()
        wipe()
    }

    private fun wipe() {
        File(context.filesDir, "keys").deleteRecursively()
        File(context.filesDir, "media").deleteRecursively()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            File(context.getDatabasePath("quietinbox.vault").path + suffix).delete()
        }
    }

    @Test
    fun exportIOExceptionRemainsAnIoResult() = runBlocking {
        service.openOutput = { throw IOException("expected export failure") }

        val result = withTimeout(20_000) {
            service.export(Uri.parse("content://quietinbox.test/export-io"), "test")
        }

        result.shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.IO
        Unit
    }

    @Test
    fun exportAssertionErrorCompletesExceptionally() = runBlocking {
        service.openOutput = { throw AssertionError("fatal export failure") }

        val failure = runCatching {
            withTimeout(20_000) {
                service.export(Uri.parse("content://quietinbox.test/export-error"), "test")
            }
        }.exceptionOrNull()

        failure.shouldBeInstanceOf<AssertionError>().message shouldBe "fatal export failure"
        Unit
    }

    @Test
    fun applyIOExceptionRemainsAnIoResult() = runBlocking {
        service.restoreProbe = { point ->
            if (point == BackupService.RestorePoint.IN_TRANSACTION) {
                throw IOException("expected apply failure")
            }
        }

        val result = withTimeout(20_000) { service.import(Uri.fromFile(backup), recoveryKey) }

        result.shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.IO
        Unit
    }

    @Test
    fun applyAssertionErrorCompletesExceptionally() = runBlocking {
        service.restoreProbe = { point ->
            if (point == BackupService.RestorePoint.IN_TRANSACTION) {
                throw AssertionError("fatal apply failure")
            }
        }

        val failure = runCatching {
            withTimeout(20_000) { service.import(Uri.fromFile(backup), recoveryKey) }
        }.exceptionOrNull()

        failure.shouldBeInstanceOf<AssertionError>().message shouldBe "fatal apply failure"
        Unit
    }
}
