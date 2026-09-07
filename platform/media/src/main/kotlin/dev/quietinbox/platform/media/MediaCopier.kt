package dev.quietinbox.platform.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.withTransaction
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.platform.crypto.BlobCipher
import dev.quietinbox.platform.crypto.KeyResult
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.MediaBlobEntity
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded, time-limited copy of media referenced by a notification into encrypted blobs
 * (plan section 10). Only `content://` URIs and bitmaps the notification already carried are
 * accepted; nothing is downloaded. Failures keep a specific [MediaState] so the UI can show why.
 *
 * Runs as vault work (QI-SEC-003): refused during a reset or restore, cancelled when one starts.
 * The blob row and the message link are written in one transaction, and every file written for a
 * copy that did not reach that commit is removed on the way out (QI-MEDIA-006).
 */
@Singleton
class MediaCopier @Inject constructor(
    private val streams: MediaStreams,
    private val holder: DatabaseHolder,
    private val cipher: BlobCipher,
    private val dir: MediaDirectory,
    private val settings: SettingsRepository,
    private val maintenance: VaultMaintenance,
) {
    private val parallelism = Semaphore(2)

    /**
     * Provider reads run in this scope, not in the caller's job, on purpose. `openInputStream` and
     * `InputStream.read` are blocking binder calls with no suspension point, so a provider that
     * accepts the open and then never delivers bytes cannot be cancelled: the old `withTimeout`
     * around them could not fire until `read` had already returned. The blocked worker held a
     * [parallelism] permit and stayed registered in `VaultMaintenance.workers`, whose `exclusive`
     * run does `joinAll()` before taking the pipeline lock — so one hung provider hung
     * "Delete everything" and "Restore" forever (QI-MEDIA-014).
     *
     * Awaiting a [kotlinx.coroutines.Deferred] from a scope of our own gives the timeout a real
     * suspension point. A thread stuck in a blocking read is orphaned rather than joined, and
     * [READ_PARALLELISM] bounds how many can be: once they are all parked, later copies time out
     * and are recorded as failures instead of piling up.
     */
    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(READ_PARALLELISM))

    suspend fun copyPending(messageIds: List<Long>, bitmap: Bitmap?) {
        maintenance.work {
            withContext(Dispatchers.IO) {
                val db = holder.db()
                // Re-read the effective setting at the moment of copying: a switch turned off (or a
                // disclosure never accepted) between commit and copy must win (QI-PRIV-002).
                if (!settings.current().mediaCopyEnabled) {
                    for (id in messageIds) {
                        val row = db.messageDao().get(id) ?: continue
                        if (row.mediaState == MediaState.PENDING.name) db.messageDao().setMedia(id, MediaState.DISABLED_BY_USER.name, null)
                    }
                    return@withContext
                }
                // A Bitmap is not thread-safe: compress it once here, then share the immutable bytes.
                val bitmapBytes: ByteArray? = bitmap?.let { b ->
                    val out = ByteArrayOutputStream()
                    if (runCatching { b.compress(Bitmap.CompressFormat.PNG, 100, out) }.getOrDefault(false)) out.toByteArray() else null
                }
                // supervisorScope, and a catch per id: one failing copy used to cancel its siblings
                // and leave their rows PENDING, which the bubble shows as an hourglass that never
                // resolves. Every id ends in a terminal state or is deliberately left for the
                // retention sweep to rescue (QI-MEDIA-015).
                supervisorScope {
                    messageIds.map { id ->
                        async {
                            parallelism.withPermit {
                                try {
                                    val row = db.messageDao().get(id) ?: return@withPermit
                                    if (row.mediaState != MediaState.PENDING.name) return@withPermit
                                    val state = when {
                                        row.mediaUri != null -> copyUri(id, Uri.parse(row.mediaUri), row.mediaMimeType)
                                        bitmap != null -> copyBitmapBytes(id, bitmapBytes)
                                        else -> MediaState.FAILED
                                    }
                                    // LOCAL_COPY was linked inside store()'s transaction; only failures are written here.
                                    if (state != MediaState.LOCAL_COPY) db.messageDao().setMedia(id, state.name, null)
                                } catch (e: CancellationException) {
                                    // A reset or restore is about to take the pipeline lock: writing
                                    // here would race it. The row stays PENDING and the retention
                                    // sweep settles it.
                                    throw e
                                } catch (e: Exception) {
                                    runCatching { db.messageDao().setMedia(id, MediaState.FAILED.name, null) }
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        }
    }

    private suspend fun copyUri(messageId: Long, uri: Uri, mimeType: String?): MediaState {
        if (uri.scheme != "content") return MediaState.PLACEHOLDER_ONLY
        val result = readWithTimeout(readScope, READ_TIMEOUT_MS, MAX_BYTES) { streams.open(uri) }
            ?: return MediaState.FAILED
        return when (result) {
            is Read.Failed -> result.state
            is Read.Ok -> store(messageId, result.bytes, mimeType)
        }
    }

    private suspend fun copyBitmapBytes(messageId: Long, bytes: ByteArray?): MediaState {
        if (bytes == null) return MediaState.FAILED
        if (bytes.size > MAX_BYTES) return MediaState.TOO_LARGE
        return store(messageId, bytes, "image/png")
    }

    /**
     * Writes the blob (and thumbnail), then links row and message in one transaction. Any failure
     * after a file was written removes that file: no orphan survives a cancelled or failed copy.
     */
    /** Test seam: runs after the files are written and before the linking transaction; production never sets it. */
    internal var beforeLink: suspend (messageId: Long) -> Unit = {}

    private suspend fun store(messageId: Long, bytes: ByteArray, mimeType: String?): MediaState {
        // The vault quota is checked here, where both paths meet: it used to sit in copyUri only,
        // so notification bitmaps ignored the 512 MB cap entirely (QI-MEDIA-016). A full store is
        // its own state — calling a 4 KB thumbnail "too large" was never true.
        if (dir.totalBytes() > QUOTA_BYTES) return MediaState.VAULT_MEDIA_FULL
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val isImage = opts.outWidth > 0 && opts.outHeight > 0
        if (!isImage && mimeType?.startsWith("image/") == true) return MediaState.FAILED

        val name = UUID.randomUUID().toString().replace("-", "")
        val written = ArrayList<String>(2)
        try {
            when (cipher.encryptToFile(bytes, dir.file(name))) {
                is KeyResult.Failed -> return MediaState.FAILED
                is KeyResult.Ok -> written += name
            }
            var thumbName: String? = null
            if (isImage) {
                val candidate = "$name.t"
                val thumb = thumbnail(bytes, opts.outWidth, opts.outHeight)
                // A thumbnail that failed to encrypt is simply absent; its name is never recorded.
                if (thumb != null && cipher.encryptToFile(thumb, dir.file(candidate)) is KeyResult.Ok) {
                    thumbName = candidate
                    written += candidate
                } else {
                    dir.delete(candidate)
                }
            }
            val db = holder.db()
            beforeLink(messageId)
            val linked = db.withTransaction {
                val id = db.mediaDao().insert(
                    MediaBlobEntity(
                        messageId = messageId,
                        fileName = name,
                        thumbFileName = thumbName,
                        mimeType = mimeType ?: if (isImage) "image/*" else null,
                        byteCount = bytes.size.toLong(),
                        width = opts.outWidth.takeIf { isImage },
                        height = opts.outHeight.takeIf { isImage },
                        state = MediaState.LOCAL_COPY.name,
                        failureReason = null,
                        createdAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                // The message can leave between copyPending's read of its row and this write (a
                // delete, an expiry sweep), and media_blob has no foreign key to it: the blob row
                // used to outlive the message as an orphan, file and all, until the next sweep
                // happened to find it. Linking zero rows undoes the insert, and the file goes with
                // it below, because the list is cleared only on a link (audit-2 MED-7).
                if (db.messageDao().setMedia(messageId, MediaState.LOCAL_COPY.name, id) != 1) {
                    db.mediaDao().delete(listOf(id))
                    return@withTransaction false
                }
                // Cleared inside the transaction: a cancellation landing between commit and return
                // must not delete files the committed rows now point at (round-10 finding).
                written.clear()
                true
            }
            return if (linked) MediaState.LOCAL_COPY else MediaState.FAILED
        } finally {
            for (f in written) dir.delete(f)
        }
    }

    private fun thumbnail(bytes: ByteArray, w: Int, h: Int): ByteArray? {
        var sample = 1
        while (w / sample > THUMB_MAX || h / sample > THUMB_MAX) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Decrypts a blob (or its thumbnail) into memory. No plaintext disk cache exists anywhere. */
    suspend fun load(blobId: Long, thumbnail: Boolean): ByteArray? = withContext(Dispatchers.IO) {
        val blob = holder.db().mediaDao().get(blobId) ?: return@withContext null
        val name = if (thumbnail) blob.thumbFileName ?: blob.fileName else blob.fileName
        when (val r = cipher.decryptFile(dir.file(name))) {
            is KeyResult.Ok -> r.value
            is KeyResult.Failed -> null
        }
    }

    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        const val QUOTA_BYTES = 512L * 1024 * 1024
        const val THUMB_MAX = 512
        const val READ_TIMEOUT_MS = 10_000L

        /** How many provider reads may be parked at once before later copies fail fast. */
        const val READ_PARALLELISM = 2
    }
}
