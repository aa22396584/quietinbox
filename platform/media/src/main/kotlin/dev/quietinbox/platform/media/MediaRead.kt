package dev.quietinbox.platform.media

import dev.quietinbox.core.model.MediaState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.io.InputStream

/** Outcome of reading a `content://` stream: the bytes, or the state that says why there are none. */
internal sealed interface Read {
    class Ok(val bytes: ByteArray) : Read
    data class Failed(val state: MediaState) : Read
}

/**
 * Reads at most [maxBytes] from [open], mapping every failure to its own [MediaState].
 *
 * Blocking on purpose; [readWithTimeout] is what makes it abandonable. A provider that is gone
 * returns a *null stream* rather than throwing, which used to fall into the same `null` as the
 * over-size early return and be labelled "media too large" for a URI that had simply expired
 * (QI-MEDIA-013).
 */
internal fun readMedia(maxBytes: Long, open: () -> InputStream?): Read = try {
    val stream = open() ?: return Read.Failed(MediaState.URI_EXPIRED)
    stream.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) return Read.Failed(MediaState.TOO_LARGE)
            out.write(buf, 0, n)
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) Read.Failed(MediaState.URI_EXPIRED) else Read.Ok(bytes)
    }
} catch (e: InterruptedException) {
    throw e
} catch (e: SecurityException) {
    Read.Failed(MediaState.PERMISSION_DENIED)
} catch (e: FileNotFoundException) {
    Read.Failed(MediaState.URI_EXPIRED)
} catch (e: Exception) {
    Read.Failed(MediaState.FAILED)
}

/**
 * Runs [readMedia] in [scope] and waits at most [timeoutMs] for it; null means it did not finish.
 *
 * The read must not run in the caller's job. `openInputStream` and `InputStream.read` are blocking
 * binder calls with no suspension point, so a `withTimeout` wrapped straight around them cannot
 * fire until `read` has already returned — a provider that accepts the open and never delivers
 * bytes parked the calling coroutine for ever. That coroutine held a copier permit and stayed
 * registered in `VaultMaintenance.workers`, which an `exclusive` run joins before it takes the
 * pipeline lock, so one hung provider hung "Delete everything" and "Restore" for ever
 * (QI-MEDIA-014).
 *
 * `await()` is a real suspension point, so the wait ends on time; the thread is abandoned rather
 * than joined. Cancelling the deferred interrupts it, which is best effort only — on ART an
 * interrupt does not reliably unblock a read on a pipe.
 */
internal suspend fun readWithTimeout(
    scope: CoroutineScope,
    timeoutMs: Long,
    maxBytes: Long,
    open: () -> InputStream?,
): Read? {
    val read = scope.async { runInterruptible { readMedia(maxBytes, open) } }
    val result = try {
        withTimeoutOrNull(timeoutMs) { read.await() }
    } catch (e: CancellationException) {
        read.cancel()
        throw e
    }
    if (result == null) read.cancel()
    return result
}
