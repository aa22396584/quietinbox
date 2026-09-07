package dev.quietinbox.platform.media

import dev.quietinbox.core.model.MediaState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The media pipeline's failure mapping. Every branch here was implemented but untested — the module
 * had no test source at all — and two of them were wrong: a provider that had gone away was
 * reported as "media too large", and a provider that never answered hung the whole vault.
 */
class MediaReadTest : StringSpec({

    val max = 8L * 1024 * 1024

    "a provider that is gone returns no stream and that is an expired link, not an over-size one" {
        val read = readMedia(max) { null }
        read.shouldBeInstanceOf<Read.Failed>().state shouldBe MediaState.URI_EXPIRED
    }

    "a stream longer than the cap is the only thing that may be called too large" {
        val read = readMedia(16) { ByteArrayInputStream(ByteArray(64)) }
        read.shouldBeInstanceOf<Read.Failed>().state shouldBe MediaState.TOO_LARGE
    }

    "a revoked grant is a denial" {
        val read = readMedia(max) { throw SecurityException("revoked") }
        read.shouldBeInstanceOf<Read.Failed>().state shouldBe MediaState.PERMISSION_DENIED
    }

    "a missing file is an expired link" {
        val read = readMedia(max) { throw FileNotFoundException("gone") }
        read.shouldBeInstanceOf<Read.Failed>().state shouldBe MediaState.URI_EXPIRED
    }

    "a stream that dies mid-read is a failure, not a partial copy" {
        val read = readMedia(max) {
            object : InputStream() {
                override fun read(): Int = throw IOException("provider died")
                override fun read(b: ByteArray): Int = throw IOException("provider died")
            }
        }
        read.shouldBeInstanceOf<Read.Failed>().state shouldBe MediaState.FAILED
    }

    "an empty payload is an expired link, never an empty local copy" {
        val read = readMedia(max) { ByteArrayInputStream(ByteArray(0)) }
        read.shouldBeInstanceOf<Read.Failed>().state shouldBe MediaState.URI_EXPIRED
    }

    "bytes that arrive are returned whole" {
        val read = readMedia(max) { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
        read.shouldBeInstanceOf<Read.Ok>().bytes.toList() shouldBe listOf<Byte>(1, 2, 3, 4)
    }

    "a provider that never answers is abandoned instead of parking the caller for ever" {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            runBlocking {
                val result = withTimeout(5_000) {
                    readWithTimeout(scope, timeoutMs = 200, maxBytes = max) {
                        entered.countDown()
                        release.await() // a provider that opened the stream and then went silent
                        ByteArrayInputStream(ByteArray(0))
                    }
                }
                result shouldBe null
            }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
        } finally {
            release.countDown()
        }
    }

    /**
     * The regression that matters: the caller must be free again, because `VaultMaintenance`
     * joins its workers before "Delete everything" or "Restore" may take the pipeline lock.
     */
    "the caller is released while the read is still stuck, so a joiner is not blocked" {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val release = CountDownLatch(1)
        val joinerFinished = AtomicBoolean(false)
        try {
            runBlocking {
                val worker = launch(Dispatchers.IO) {
                    readWithTimeout(scope, timeoutMs = 200, maxBytes = max) {
                        release.await()
                        ByteArrayInputStream(ByteArray(0))
                    }
                }
                // Whoever joins the worker — as an exclusive maintenance run does — gets to run.
                withTimeout(5_000) {
                    val joiner = async { worker.join(); joinerFinished.set(true) }
                    joiner.await()
                }
                joinerFinished.get() shouldBe true
            }
        } finally {
            release.countDown()
        }
    }

    "a cancelled caller stays cancelled and does not become a failed copy" {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            runBlocking {
                val job = launch(Dispatchers.IO) {
                    started.countDown()
                    readWithTimeout(scope, timeoutMs = 60_000, maxBytes = max) {
                        release.await()
                        ByteArrayInputStream(ByteArray(0))
                    }
                }
                started.await(5, TimeUnit.SECONDS) shouldBe true
                withTimeout(5_000) { job.cancelAndJoin() }
                job.isCancelled shouldBe true
            }
        } finally {
            release.countDown()
        }
    }
})
