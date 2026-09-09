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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun retriesDoNotEnqueueAnotherBlockedCloseOnTheSameStream() = runBlocking {
        val entered = CountDownLatch(2)
        val closes = AtomicInteger(0)
        fun hung(): InputStream {
            val lock = Any()
            return object : InputStream() {
                override fun read(): Int {
                    synchronized(lock) {
                        entered.countDown()
                        releaseHung.await()
                        return -1
                    }
                }
                override fun close() {
                    closes.incrementAndGet()
                    synchronized(lock) { }
                }
            }
        }
        service.openInput = { hung() }
        val first = launch(Dispatchers.IO) { service.import(Uri.parse("content://quietinbox.test/hang"), recoveryKey) }
        val second = launch(Dispatchers.IO) { service.import(Uri.parse("content://quietinbox.test/hang"), recoveryKey) }
        withTimeout(5_000) { while (entered.count > 0) delay(10) }
        first.cancel()
        second.cancel()
        withTimeout(5_000) { first.join(); second.join() }
        withTimeout(5_000) { while (closes.get() < 2) delay(10) }
        val before = closes.get()
        before shouldBe 2
        repeat(8) {
            withTimeout(5_000) {
                service.import(Uri.parse("content://quietinbox.test/hang"), recoveryKey)
                    .shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.IO
            }
        }
        closes.get() shouldBe before
        Unit
    }

    @Test
    fun cancellingANeverReturningExportWriteReleasesTheCallerAndDoesNotHoldExclusive() = runBlocking {
        val entered = CountDownLatch(1)
        val lock = Any()
        service.openOutput = {
            object : OutputStream() {
                override fun write(b: Int) {
                    synchronized(lock) {
                        entered.countDown()
                        releaseHung.await()
                    }
                }
                override fun close() {
                    synchronized(lock) { }
                }
            }
        }
        val job = launch(Dispatchers.IO) { service.export(Uri.parse("content://quietinbox.test/hang-out"), "test") }
        withTimeout(15_000) { while (entered.count > 0) delay(10) }
        maintenance.isActive shouldBe false
        withTimeout(5_000) { maintenance.exclusive { 7 } shouldBe 7 }
        job.cancel()
        withTimeout(5_000) { job.join() }
        maintenance.isActive shouldBe false
        withTimeout(5_000) { maintenance.exclusive { 9 } shouldBe 9 }
        Unit
    }

    @Test
    fun cancellingOneHungImportDoesNotCloseAnotherStream() = runBlocking {
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondCloses = AtomicInteger(0)
        val n = AtomicInteger(0)
        service.openInput = {
            if (n.getAndIncrement() == 0) {
                val lock = Any()
                object : InputStream() {
                    override fun read(): Int {
                        synchronized(lock) {
                            firstEntered.countDown()
                            releaseHung.await()
                            return -1
                        }
                    }
                    override fun close() { synchronized(lock) { } }
                }
            } else {
                val lock = Any()
                object : InputStream() {
                    override fun read(): Int {
                        synchronized(lock) {
                            secondEntered.countDown()
                            releaseHung.await()
                            return -1
                        }
                    }
                    override fun close() {
                        secondCloses.incrementAndGet()
                        synchronized(lock) { }
                    }
                }
            }
        }
        val first = launch(Dispatchers.IO) { service.import(Uri.parse("content://quietinbox.test/hang-a"), recoveryKey) }
        withTimeout(5_000) { while (firstEntered.count > 0) delay(10) }
        val second = launch(Dispatchers.IO) { service.import(Uri.parse("content://quietinbox.test/hang-b"), recoveryKey) }
        withTimeout(5_000) { while (secondEntered.count > 0) delay(10) }
        first.cancel()
        withTimeout(5_000) { first.join() }
        repeat(8) {
            delay(25)
            secondCloses.get() shouldBe 0
        }
        second.cancel()
        withTimeout(5_000) { second.join() }
        Unit
    }

    @Test
    fun abortAfterStagingDoesNotApply() = runBlocking {
        val backup = exportOneMessage("abort-stage.qibk")
        holder.closeAndDeleteFiles() shouldBe true
        holder.retry()
        ready()
        service.afterStaged = { service.abort() }
        val result = service.import(Uri.fromFile(backup), recoveryKey)
        result.shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.ABORTED
        holder.db().messageDao().exportPage(0L, 10, System.currentTimeMillis()) shouldBe emptyList()
        backup.delete()
        Unit
    }

    @Test
    fun abortInsideApplyDoesNotLandTheWrite() = runBlocking {
        val backup = exportOneMessage("abort-apply.qibk")
        holder.closeAndDeleteFiles() shouldBe true
        holder.retry()
        ready()
        service.restoreProbe = { point ->
            if (point == BackupService.RestorePoint.IN_TRANSACTION) service.abort()
        }
        val result = service.import(Uri.fromFile(backup), recoveryKey)
        result.shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.ABORTED
        holder.db().messageDao().exportPage(0L, 10, System.currentTimeMillis()) shouldBe emptyList()
        backup.delete()
        Unit
    }

    @Test
    fun abortAfterCommitStillReportsTheRestore() = runBlocking {
        val backup = exportOneMessage("abort-after-commit.qibk")
        holder.closeAndDeleteFiles() shouldBe true
        holder.retry()
        ready()
        val result = AtomicReference<BackupResult?>(null)
        var waiter: kotlinx.coroutines.Job? = null
        service.restoreProbe = { point ->
            if (point == BackupService.RestorePoint.AFTER_COMMIT) {
                service.abort()
                waiter?.cancel()
            }
        }
        waiter = launch(Dispatchers.IO) { result.set(service.import(Uri.fromFile(backup), recoveryKey)) }
        withTimeout(20_000) { waiter!!.join() }
        result.get().shouldBeInstanceOf<BackupResult.Ok>()
        holder.db().messageDao().exportPage(0L, 10, System.currentTimeMillis()).size shouldBe 1
        backup.delete()
        Unit
    }

    @Test
    fun cancellingANeverReturningImportOpenReleasesTheCallerAndDoesNotHoldExclusive() = runBlocking {
        val entered = CountDownLatch(1)
        val result = AtomicReference<BackupResult?>(null)
        service.openInput = {
            entered.countDown()
            releaseHung.await()
            null
        }
        val job = launch(Dispatchers.IO) { result.set(service.import(Uri.parse("content://quietinbox.test/hang-open-in"), recoveryKey)) }
        withTimeout(5_000) { while (entered.count > 0) delay(10) }
        maintenance.isActive shouldBe false
        withTimeout(5_000) { maintenance.exclusive { 7 } shouldBe 7 }
        job.cancel()
        withTimeout(5_000) { job.join() }
        result.get().shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.ABORTED
        maintenance.isActive shouldBe false
        withTimeout(5_000) { maintenance.exclusive { 9 } shouldBe 9 }
        Unit
    }

    @Test
    fun cancellingANeverReturningExportOpenReleasesTheCallerAndDoesNotHoldExclusive() = runBlocking {
        val entered = CountDownLatch(1)
        val result = AtomicReference<BackupResult?>(null)
        service.openOutput = {
            entered.countDown()
            releaseHung.await()
            null
        }
        val job = launch(Dispatchers.IO) { result.set(service.export(Uri.parse("content://quietinbox.test/hang-open-out"), "test")) }
        withTimeout(15_000) { while (entered.count > 0) delay(10) }
        maintenance.isActive shouldBe false
        withTimeout(5_000) { maintenance.exclusive { 7 } shouldBe 7 }
        job.cancel()
        withTimeout(5_000) { job.join() }
        result.get().shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.EXPORT_ABORTED
        maintenance.isActive shouldBe false
        withTimeout(5_000) { maintenance.exclusive { 9 } shouldBe 9 }
        Unit
    }

    @Test
    fun aLateValidExportStreamAfterStopIsNotWritten() = runBlocking {
        val opened = CountDownLatch(1)
        val written = AtomicInteger(0)
        val result = AtomicReference<BackupResult?>(null)
        service.openOutput = {
            opened.countDown()
            releaseHung.await()
            object : OutputStream() {
                override fun write(b: Int) { written.incrementAndGet() }
                override fun write(b: ByteArray, off: Int, len: Int) { written.addAndGet(len) }
                override fun close() {}
            }
        }
        val job = launch(Dispatchers.IO) { result.set(service.export(Uri.parse("content://quietinbox.test/late-open"), "test")) }
        withTimeout(15_000) { while (opened.count > 0) delay(10) }
        job.cancel()
        service.abort()
        withTimeout(5_000) { job.join() }
        result.get().shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.EXPORT_ABORTED
        written.get() shouldBe 0
        releaseHung.countDown()
        delay(400)
        written.get() shouldBe 0
        Unit
    }

    @Test
    fun aLateValidExportStreamAfterWaiterCancelIsNotWritten() = runBlocking {
        val opened = CountDownLatch(1)
        val written = AtomicInteger(0)
        val result = AtomicReference<BackupResult?>(null)
        service.openOutput = {
            opened.countDown()
            releaseHung.await()
            object : OutputStream() {
                override fun write(b: Int) { written.incrementAndGet() }
                override fun write(b: ByteArray, off: Int, len: Int) { written.addAndGet(len) }
                override fun close() {}
            }
        }
        val job = launch(Dispatchers.IO) { result.set(service.export(Uri.parse("content://quietinbox.test/late-open-cancel"), "test")) }
        withTimeout(15_000) { while (opened.count > 0) delay(10) }
        job.cancel()
        withTimeout(5_000) { job.join() }
        result.get().shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.EXPORT_ABORTED
        written.get() shouldBe 0
        releaseHung.countDown()
        delay(400)
        written.get() shouldBe 0
        Unit
    }

    @Test
    fun aLateValidImportStreamAfterStopDoesNotApply() = runBlocking {
        val backup = exportOneMessage("late-import.qibk")
        holder.closeAndDeleteFiles() shouldBe true
        holder.retry()
        ready()
        val opened = CountDownLatch(1)
        val reads = AtomicInteger(0)
        service.openInput = {
            opened.countDown()
            releaseHung.await()
            object : InputStream() {
                private val inner = java.io.FileInputStream(backup)
                override fun read(): Int {
                    reads.incrementAndGet()
                    return inner.read()
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    reads.incrementAndGet()
                    return inner.read(b, off, len)
                }
                override fun close() { inner.close() }
            }
        }
        val result = AtomicReference<BackupResult?>(null)
        val job = launch(Dispatchers.IO) { result.set(service.import(Uri.parse("content://quietinbox.test/late-in"), recoveryKey)) }
        withTimeout(5_000) { while (opened.count > 0) delay(10) }
        job.cancel()
        service.abort()
        withTimeout(5_000) { job.join() }
        result.get().shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.ABORTED
        releaseHung.countDown()
        delay(200)
        reads.get() shouldBe 0
        holder.db().messageDao().exportPage(0L, 10, System.currentTimeMillis()) shouldBe emptyList()
        backup.delete()
        Unit
    }

    @Test
    fun aLateValidImportStreamAfterWaiterCancelDoesNotApply() = runBlocking {
        val backup = exportOneMessage("late-import-cancel.qibk")
        holder.closeAndDeleteFiles() shouldBe true
        holder.retry()
        ready()
        val opened = CountDownLatch(1)
        val reads = AtomicInteger(0)
        service.openInput = {
            opened.countDown()
            releaseHung.await()
            object : InputStream() {
                private val inner = java.io.FileInputStream(backup)
                override fun read(): Int {
                    reads.incrementAndGet()
                    return inner.read()
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    reads.incrementAndGet()
                    return inner.read(b, off, len)
                }
                override fun close() { inner.close() }
            }
        }
        val result = AtomicReference<BackupResult?>(null)
        val job = launch(Dispatchers.IO) { result.set(service.import(Uri.parse("content://quietinbox.test/late-in-cancel"), recoveryKey)) }
        withTimeout(5_000) { while (opened.count > 0) delay(10) }
        job.cancel()
        withTimeout(5_000) { job.join() }
        result.get().shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.ABORTED
        releaseHung.countDown()
        delay(200)
        reads.get() shouldBe 0
        holder.db().messageDao().exportPage(0L, 10, System.currentTimeMillis()) shouldBe emptyList()
        backup.delete()
        Unit
    }

    @Test
    fun exportCloseThrowIsFailedIoNotOk() = runBlocking {
        service.openOutput = {
            object : OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {}
                override fun flush() {}
                override fun close() { throw java.io.IOException("close") }
            }
        }
        val result = service.export(Uri.parse("content://quietinbox.test/close-throw"), "test")
        result.shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.IO
        Unit
    }

    @Test
    fun aBlockingExportCloseDoesNotReportOkAndDoesNotPinExclusive() = runBlocking {
        val entered = CountDownLatch(1)
        val result = AtomicReference<BackupResult?>(null)
        service.openOutput = {
            object : OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {}
                override fun flush() {}
                override fun close() {
                    entered.countDown()
                    releaseHung.await()
                }
            }
        }
        val job = launch(Dispatchers.IO) { result.set(service.export(Uri.parse("content://quietinbox.test/close-block"), "test")) }
        withTimeout(15_000) { while (entered.count > 0) delay(10) }
        result.get() shouldBe null
        maintenance.isActive shouldBe false
        withTimeout(5_000) { maintenance.exclusive { 7 } shouldBe 7 }
        job.cancel()
        service.abort()
        withTimeout(5_000) { job.join() }
        val seen = result.get()
        (seen == null || (seen is BackupResult.Failed && seen.reason == BackupResult.Reason.EXPORT_ABORTED)) shouldBe true
        Unit
    }

    @Test
    fun cancellingExportBDoesNotSurfaceExportAResult() = runBlocking {
        val aEntered = CountDownLatch(1)
        val aRelease = CountDownLatch(1)
        val bEntered = CountDownLatch(1)
        val bRelease = CountDownLatch(1)
        val n = AtomicInteger(0)
        val bWritten = AtomicInteger(0)
        service.openOutput = {
            if (n.getAndIncrement() == 0) {
                aEntered.countDown()
                aRelease.await()
                object : OutputStream() {
                    override fun write(b: Int) {}
                    override fun write(b: ByteArray, off: Int, len: Int) {}
                    override fun close() {}
                }
            } else {
                bEntered.countDown()
                bRelease.await()
                object : OutputStream() {
                    override fun write(b: Int) { bWritten.incrementAndGet() }
                    override fun write(b: ByteArray, off: Int, len: Int) { bWritten.addAndGet(len) }
                    override fun close() {}
                }
            }
        }
        val aResult = AtomicReference<BackupResult?>(null)
        val bResult = AtomicReference<BackupResult?>(null)
        val a = launch(Dispatchers.IO) { aResult.set(service.export(Uri.parse("content://quietinbox.test/op-a"), "test")) }
        withTimeout(15_000) { while (aEntered.count > 0) delay(10) }
        val b = launch(Dispatchers.IO) { bResult.set(service.export(Uri.parse("content://quietinbox.test/op-b"), "test")) }
        withTimeout(15_000) { while (bEntered.count > 0) delay(10) }
        aRelease.countDown()
        withTimeout(15_000) { a.join() }
        aResult.get().shouldBeInstanceOf<BackupResult.Ok>()
        b.cancel()
        withTimeout(5_000) { b.join() }
        bResult.get().shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.EXPORT_ABORTED
        bRelease.countDown()
        delay(400)
        bWritten.get() shouldBe 0
        aResult.get().shouldBeInstanceOf<BackupResult.Ok>()
        Unit
    }

    @Test
    fun abandonedBlockedExportClosesHoldTheWriteSlotCap() = runBlocking {
        val closes = AtomicInteger(0)
        val releaseClose = CountDownLatch(1)
        val openHold = AtomicReference(CountDownLatch(1))
        val opened = AtomicInteger(0)
        service.openOutput = {
            opened.incrementAndGet()
            openHold.get().await()
            object : OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {}
                override fun close() {
                    closes.incrementAndGet()
                    releaseClose.await()
                }
            }
        }
        try {
            repeat(2) {
                val hold = CountDownLatch(1)
                openHold.set(hold)
                val beforeOpen = opened.get()
                val beforeClose = closes.get()
                val job = launch(Dispatchers.IO) { service.export(Uri.parse("content://quietinbox.test/slot-cap"), "test") }
                withTimeout(15_000) { while (opened.get() == beforeOpen) delay(10) }
                job.cancel()
                service.abort()
                withTimeout(5_000) { job.join() }
                hold.countDown()
                withTimeout(5_000) { while (closes.get() == beforeClose) delay(10) }
            }
            withTimeout(5_000) {
                service.export(Uri.parse("content://quietinbox.test/slot-cap"), "test")
                    .shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.IO
            }
            closes.get() shouldBe 2
        } finally {
            releaseClose.countDown()
        }
        Unit
    }

    @Test
    fun aSecondIdempotentCloseDoesNotReleaseTheWriteSlotBeforeTheFirstCloseFinishes() = runBlocking {
        val closeHolds = ArrayList<CountDownLatch>()
        try {
            repeat(2) { i ->
                val enteredWrite = CountDownLatch(1)
                val holdWrite = CountDownLatch(1)
                val enteredClose = CountDownLatch(1)
                val holdClose = CountDownLatch(1)
                closeHolds += holdClose
                service.openOutput = {
                    object : OutputStream() {
                        private val firstClose = AtomicBoolean(true)
                        override fun write(b: Int) {
                            enteredWrite.countDown()
                            holdWrite.await()
                        }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            enteredWrite.countDown()
                            holdWrite.await()
                        }
                        override fun close() {
                            if (firstClose.getAndSet(false)) {
                                enteredClose.countDown()
                                holdClose.await()
                            }
                        }
                    }
                }
                val job = launch(Dispatchers.IO) { service.export(Uri.parse("content://quietinbox.test/double-close-$i"), "test") }
                withTimeout(15_000) { while (enteredWrite.count > 0) delay(10) }
                job.cancel()
                withTimeout(5_000) { job.join() }
                withTimeout(5_000) { while (enteredClose.count > 0) delay(10) }
                holdWrite.countDown()
                delay(200)
            }
            withTimeout(5_000) {
                service.export(Uri.parse("content://quietinbox.test/double-close-third"), "test")
                    .shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.IO
            }
            closeHolds[0].countDown()
            val fourthOpen = CountDownLatch(1)
            val fourthHold = CountDownLatch(1)
            service.openOutput = {
                fourthOpen.countDown()
                fourthHold.await()
                object : OutputStream() {
                    override fun write(b: Int) {}
                    override fun write(b: ByteArray, off: Int, len: Int) {}
                    override fun close() {}
                }
            }
            val fourth = launch(Dispatchers.IO) { service.export(Uri.parse("content://quietinbox.test/double-close-fourth"), "test") }
            try {
                withTimeout(15_000) { while (fourthOpen.count > 0) delay(10) }
            } finally {
                fourth.cancel()
                service.abort()
                withTimeout(5_000) { fourth.join() }
                fourthHold.countDown()
            }
        } finally {
            for (hold in closeHolds) hold.countDown()
        }
        Unit
    }

    @Test
    fun cancellingAfterEncryptedStagingIsReadyDoesNotLeaveAStagingFile() = runBlocking {
        val ready = CountDownLatch(1)
        val written = AtomicInteger(0)
        service.openOutput = {
            object : OutputStream() {
                override fun write(b: Int) { written.incrementAndGet() }
                override fun write(b: ByteArray, off: Int, len: Int) { written.addAndGet(len) }
                override fun close() {}
            }
        }
        service.afterEncryptedStagingReady = { file ->
            file.exists() shouldBe true
            ready.countDown()
            while (currentCoroutineContext().isActive) delay(10)
        }
        val before = stagingBackupFiles().toSet()
        val result = AtomicReference<BackupResult?>(null)
        val job = launch(Dispatchers.IO) { result.set(service.export(Uri.parse("content://quietinbox.test/staging-cancel"), "test")) }
        withTimeout(15_000) { while (ready.count > 0) delay(10) }
        (stagingBackupFiles().toSet() - before).size shouldBe 1
        job.cancel()
        withTimeout(5_000) { job.join() }
        result.get().shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.EXPORT_ABORTED
        written.get() shouldBe 0
        delay(200)
        (stagingBackupFiles().toSet() - before) shouldBe emptySet()
        Unit
    }

    private fun stagingBackupFiles(): List<File> =
        context.cacheDir.listFiles()?.filter { it.name.startsWith("backup-") && it.name.endsWith(".qibk") }.orEmpty()

    private suspend fun exportOneMessage(name: String): File {
        val snapshot = Fixtures.snapshot(Fixtures.bigText("Alice", "keep", tag = "t1"), packageName = KnownSources.TELEGRAM, eventId = "h-abort", observedAt = 1_700_000_000_000L)
        ingest.journal(snapshot, "gen", 60_000) shouldBe true
        val batch = parser.parse(snapshot)
        val id = identity.resolve(snapshot, batch)
        val r = reconciler.reconcile(snapshot.notificationKey, batch.messages, ingest.checkpoint(id.streamKey), lookupById = { null })
        ingest.commit(snapshot, batch, id, r, "gen", null, mediaAllowed = false)
        val backup = File(context.cacheDir, name)
        service.export(Uri.fromFile(backup), "test").shouldBeInstanceOf<BackupResult.Ok>()
        return backup
    }
}
