package dev.quietinbox.platform.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.platform.crypto.BlobCipher
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.crypto.KeyResult
import dev.quietinbox.platform.crypto.RecoveryKeyCodec
import dev.quietinbox.platform.storage.db.ConversationEntity
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.MediaBlobEntity
import dev.quietinbox.platform.storage.db.MessageEntity
import dev.quietinbox.platform.storage.db.MessageRevisionEntity
import dev.quietinbox.platform.storage.db.QuietInboxDatabase
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.settings.SettingsRepository
import dev.quietinbox.platform.storage.db.SearchTokenEntity
import dev.quietinbox.platform.storage.db.SourceConfigurationEntity
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.core.model.SearchNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BackupResult {
    /**
     * [skippedMedia] > 0 means media is missing from the file: on export, rows that could not be
     * read or were too large; on restore, messages that still claim `LOCAL_COPY` but the backup
     * never contained a Media record. [mediaNotRestored] > 0 means a restore inserted messages
     * whose media *was* in the file but could not be written to the vault (a bad or oversized blob,
     * a failed encryption). The two are counted separately so one message is never both. Those
     * rows carry `FAILED`, and either count is what the "Done" line qualifies itself with
     * (audit-2 ATOM-3).
     */
    data class Ok(val counts: Counts, val skippedMedia: Int = 0, val mediaNotRestored: Int = 0) : BackupResult
    data class Failed(val reason: Reason, val detail: String? = null) : BackupResult

    enum class Reason { NO_RECOVERY_KEY, KEY_UNAVAILABLE, IO, BAD_HEADER, WRONG_KEY_OR_TAMPERED, CORRUPT, TRUNCATED, COUNT_MISMATCH, TOO_LARGE, UNSUPPORTED_VERSION, VAULT_UNAVAILABLE, MAINTENANCE, LOW_SPACE, ABORTED, EXPORT_ABORTED }
}

/**
 * Encrypted export / import through SAF (plan section 11). Export is a logically consistent
 * snapshot serialised as a stream, read in keyset pages inside one read transaction (never the
 * whole vault in memory) and never containing a copy that is already expired; import stages
 * everything, verifies EOF, counts and every authentication tag, and only then applies in one
 * transaction. Wrong key, truncation and tampering leave the existing vault untouched.
 *
 * Both run under the maintenance gate (QI-BACKUP-016): export snapshots the vault as cancellable
 * work, then copies the finished ciphertext to the user's document *outside* that gate so an
 * uncooperative sink cannot pin "Delete everything"; import stages and verifies the file outside
 * exclusive maintenance (so an uncooperative `read` cannot pin it either), then applies as an
 * exclusive run so capture, media copies, retention and a reset cannot interleave with the write.
 * Each operation owns its own stream; [abort] invalidates in-flight apply/copy without closing
 * another operation's stream.
 */
@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: DatabaseHolder,
    private val keyMaterial: KeyMaterial,
    private val blobCipher: BlobCipher,
    private val mediaDir: MediaDirectory,
    private val settings: SettingsRepository,
    private val maintenance: VaultMaintenance,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    /**
     * Provider reads for restore run in this scope, not in the caller's job. `InputStream.read`
     * is a blocking call with no suspension point, so a source that never delivers bytes cannot
     * be cancelled cooperatively. Staging therefore waits on a [CompletableDeferred] the IO job
     * completes, and cancellation closes the stream and returns without joining that job — so
     * exclusive maintenance is never held across the hang, and a later exclusive run (including
     * "Delete everything") can start. [MAX_LIVE_IMPORT_READS] bounds abandoned threads.
     */
    private val importReads = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exportWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val liveImportReads = AtomicInteger(0)
    private val liveExportWrites = AtomicInteger(0)
    /** Unique stream-map key per import; not an abort signal. */
    private val importSeq = AtomicLong(0)
    /** Unique stream-map key per export; not an abort signal. */
    private val exportSeq = AtomicLong(0)
    /** Bumped only by [abort]; a sibling import must not look like a stop. */
    private val importGen = AtomicLong(0)
    /** Bumped only by [abort]; a sibling export must not look like a stop. */
    private val exportGen = AtomicLong(0)
    private val importStreams = ConcurrentHashMap<Long, InputStream>()
    private val exportStreams = ConcurrentHashMap<Long, OutputStream>()
    private val importIo = Any()
    private val exportIo = Any()
    private val lastImportOp = AtomicReference<BackupOp?>(null)
    private val lastExportOp = AtomicReference<BackupOp?>(null)

    private class BackupOp(val token: Long, val gen: Long) {
        val settled = AtomicReference<BackupResult?>(null)
        /** This waiter's Job.cancel; [abort] still only bumps gen so a sibling is not stopped. */
        val cancelled = AtomicBoolean(false)
    }

    private companion object {
        /** Rows per keyset page while exporting. */
        const val PAGE = 500
        /** Abandoned uncooperative backup reads; a third import is refused until one finishes. */
        const val MAX_LIVE_IMPORT_READS = 2
        /** Abandoned uncooperative backup writes; a third export is refused until one finishes. */
        const val MAX_LIVE_EXPORT_WRITES = 2
    }

    private class BackupAborted : RuntimeException()

    /**
     * Invalidates in-flight import apply and export dest-copy, and closes this service's streams
     * without waiting. Does not close another operation's stream: each token owns its own.
     */
    fun abort() {
        val inputs: List<InputStream>
        val outputs: List<OutputStream>
        synchronized(importIo) {
            importGen.incrementAndGet()
            inputs = importStreams.values.toList()
            importStreams.clear()
        }
        synchronized(exportIo) {
            exportGen.incrementAndGet()
            outputs = exportStreams.values.toList()
            exportStreams.clear()
        }
        for (s in inputs) abandonClose(s)
        for (s in outputs) abandonCloseOutput(s)
    }

    fun settledImport(): BackupResult? = lastImportOp.get()?.settled?.get()
    fun settledExport(): BackupResult? = lastExportOp.get()?.settled?.get()

    /** The recovery key as text the user must save; created on first call. */
    fun recoveryKeyText(): KeyResult<String> = when (val r = keyMaterial.recovery.getOrCreate()) {
        is KeyResult.Failed -> r
        is KeyResult.Ok -> KeyResult.Ok(RecoveryKeyCodec.encode(r.value)).also { r.value.fill(0) }
    }

    suspend fun export(target: Uri, appVersion: String): BackupResult {
        val op: BackupOp
        synchronized(exportIo) {
            op = BackupOp(exportSeq.incrementAndGet(), exportGen.get())
            lastExportOp.set(op)
        }
        return try {
            val staged = maintenance.work { writeStagingFile(appVersion) }
                ?: return BackupResult.Failed(BackupResult.Reason.MAINTENANCE)
            when (staged) {
                is StagingWrite.Fail -> staged.result
                is StagingWrite.Ok -> {
                    if (!exportOpActive(op)) {
                        staged.file.delete()
                        BackupResult.Failed(BackupResult.Reason.EXPORT_ABORTED)
                    } else {
                        copyStagingToTarget(op, staged, target)
                    }
                }
            }
        } catch (e: CancellationException) {
            dropExport(op)?.let { abandonCloseOutput(it) }
            op.settled.get() ?: BackupResult.Failed(BackupResult.Reason.EXPORT_ABORTED)
        }
    }

    private sealed class StagingWrite {
        class Ok(val file: File, val counts: Counts, val skippedMedia: Int) : StagingWrite()
        class Fail(val result: BackupResult.Failed) : StagingWrite()
    }

    /**
     * Builds the ciphertext in a private temp file under cancellable vault work. The copy to the
     * user's document is [copyStagingToTarget], outside this gate: a sink that never accepts bytes
     * must not stay registered as a worker that exclusive maintenance has to join.
     */
    private suspend fun writeStagingFile(appVersion: String): StagingWrite = withContext(Dispatchers.IO) {
        val key = when (val r = keyMaterial.recovery.getOrCreate()) {
            is KeyResult.Failed -> return@withContext StagingWrite.Fail(BackupResult.Failed(BackupResult.Reason.KEY_UNAVAILABLE))
            is KeyResult.Ok -> r.value
        }
        val db = try {
            holder.db()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext StagingWrite.Fail(BackupResult.Failed(BackupResult.Reason.VAULT_UNAVAILABLE))
        }
        val staging = File(context.cacheDir, "backup-" + UUID.randomUUID().toString().replace("-", "") + ".qibk")
        try {
            val salt = ByteArray(BackupCrypto.SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val header = BackupCrypto.header(salt)
            val saead = BackupCrypto.streamingAead(key, salt)
            val written = FileOutputStream(staging).use { raw ->
                raw.write(header)
                saead.newEncryptingStream(raw, header).use { enc ->
                    writeRecords(db, enc.bufferedWriter(Charsets.UTF_8), appVersion)
                }
            }
            StagingWrite.Ok(staging, written.counts, written.skippedMedia)
        } catch (e: Exception) {
            staging.delete()
            if (e is CancellationException) throw e
            StagingWrite.Fail(BackupResult.Failed(BackupResult.Reason.IO, e::class.java.simpleName))
        } finally {
            key.fill(0)
        }
    }

    private suspend fun copyStagingToTarget(op: BackupOp, staged: StagingWrite.Ok, target: Uri): BackupResult {
        val result = CompletableDeferred<BackupResult>()
        exportWrites.launch {
            if (liveExportWrites.incrementAndGet() > MAX_LIVE_EXPORT_WRITES) {
                liveExportWrites.decrementAndGet()
                staged.file.delete()
                result.complete(BackupResult.Failed(BackupResult.Reason.IO, "write slot"))
                return@launch
            }
            var dest: OutputStream? = null
            var closed = false
            try {
                if (!exportOpActive(op)) {
                    result.complete(BackupResult.Failed(BackupResult.Reason.EXPORT_ABORTED))
                    return@launch
                }
                val opened = openOutput(target) ?: throw java.io.IOException("open")
                val claimed: Boolean
                synchronized(exportIo) {
                    if (op.cancelled.get() || op.gen != exportGen.get()) {
                        dest = opened
                        claimed = false
                    } else {
                        dest = opened
                        exportStreams[op.token] = opened
                        claimed = true
                    }
                }
                if (!claimed) {
                    result.complete(BackupResult.Failed(BackupResult.Reason.EXPORT_ABORTED))
                    return@launch
                }
                FileInputStream(staged.file).use { input -> copyWhileExportActive(input, opened, op) }
                opened.flush()
                opened.close()
                closed = true
                synchronized(exportIo) { exportStreams.remove(op.token, opened) }
                dest = null
                if (!exportOpActive(op)) {
                    result.complete(BackupResult.Failed(BackupResult.Reason.EXPORT_ABORTED))
                } else {
                    val ok = BackupResult.Ok(staged.counts, staged.skippedMedia)
                    op.settled.set(ok)
                    result.complete(ok)
                }
            } catch (e: BackupAborted) {
                if (!result.isCompleted) result.complete(BackupResult.Failed(BackupResult.Reason.EXPORT_ABORTED))
            } catch (e: Throwable) {
                if (!result.isCompleted) {
                    val aborted = !exportOpActive(op)
                    when {
                        e is CancellationException && aborted -> result.complete(BackupResult.Failed(BackupResult.Reason.EXPORT_ABORTED))
                        e is CancellationException -> result.completeExceptionally(e)
                        aborted -> result.complete(BackupResult.Failed(BackupResult.Reason.EXPORT_ABORTED))
                        else -> result.complete(BackupResult.Failed(BackupResult.Reason.IO, e::class.java.simpleName))
                    }
                }
            } finally {
                if (!closed) {
                    dest?.let { stream ->
                        synchronized(exportIo) { exportStreams.remove(op.token, stream) }
                        // Same thread as the slot: a close that never returns keeps the cap.
                        runCatching { stream.close() }
                    }
                }
                liveExportWrites.decrementAndGet()
                staged.file.delete()
            }
        }
        try {
            return result.await()
        } catch (e: CancellationException) {
            dropExport(op)?.let { abandonCloseOutput(it) }
            return op.settled.get() ?: BackupResult.Failed(BackupResult.Reason.EXPORT_ABORTED)
        }
    }

    private fun exportOpActive(op: BackupOp): Boolean = synchronized(exportIo) {
        !op.cancelled.get() && op.gen == exportGen.get()
    }

    private fun importOpActive(op: BackupOp): Boolean = synchronized(importIo) {
        !op.cancelled.get() && op.gen == importGen.get()
    }

    private fun importStillActive(gen: Long): Boolean = synchronized(importIo) { gen == importGen.get() }

    private fun dropExport(op: BackupOp): OutputStream? = synchronized(exportIo) {
        op.cancelled.set(true)
        exportStreams.remove(op.token)
    }

    private fun dropImport(op: BackupOp): InputStream? = synchronized(importIo) {
        op.cancelled.set(true)
        importStreams.remove(op.token)
    }

    private fun copyWhileExportActive(input: FileInputStream, dest: OutputStream, op: BackupOp) {
        val buf = ByteArray(8192)
        while (true) {
            if (!exportOpActive(op)) throw BackupAborted()
            val n = input.read(buf)
            if (n < 0) break
            dest.write(buf, 0, n)
            if (!exportOpActive(op)) throw BackupAborted()
        }
    }

    private class Written(val counts: Counts, val skippedMedia: Int)

    /**
     * Streams the row tables (sources, conversations, messages, revisions) in keyset pages inside
     * one read transaction, so the manifest counts and those rows agree even while capture
     * continues; each page is serialised and written to the encrypted stream as it is read, so no
     * table is held in memory as a whole. Expired copies are not exported: a backup holds what the
     * user could see.
     *
     * Media is handled *after* the transaction, one keyset page at a time: read a page, decrypt and
     * stream its files, read the next. The pages are bounded to the highest blob id read *inside*
     * the transaction, so a picture committed after the snapshot cannot be exported without its
     * message (round-13); a blob deleted meanwhile is simply missing. The manifest's media count is
     * the number of rows at the snapshot; `End.actual.media` is the number written and
     * `End.skippedMedia` the ones that could not be read (the stager checks `End`, not the
     * manifest, for media). The transaction therefore holds the write lock for the row
     * serialisation only — seconds on a very large vault, never the media decryption (round-11 /
     * round-12 findings).
     */
    private suspend fun writeRecords(db: QuietInboxDatabase, w: BufferedWriter, appVersion: String): Written {
        fun line(r: BackupRecord) {
            w.write(json.encodeToString(BackupRecord.serializer(), r))
            w.write("\n")
        }
        val now = System.currentTimeMillis()
        val (expected, mediaMaxId) = db.withTransaction {
            val sources = db.sourceDao().all()
            val expected = Counts(
                sources = sources.size,
                conversations = db.conversationDao().count(),
                messages = db.messageDao().exportCount(now),
                revisions = db.revisionDao().exportCount(now),
                media = db.mediaDao().exportCount(now),
            )
            line(BackupRecord.Manifest(BackupCrypto.FORMAT_VERSION.toInt(), QuietInboxDatabase.VERSION, appVersion, now, expected))
            for (s in sources) line(BackupRecord.Source(s.packageName, s.displayName, s.enabled, s.paused, s.retentionDays, s.mediaEnabled, s.addedAtEpochMs, s.adapterId))
            var after = 0L
            while (true) {
                val page = db.conversationDao().exportPage(after, PAGE)
                if (page.isEmpty()) break
                for (c in page) line(BackupRecord.Conversation(c.id, c.packageName, c.profileKey, c.accountKey, c.identityKey, c.identityConfidence, c.title, c.isGroup, c.pinned, c.archived, c.createdAtEpochMs, c.lastActivityEpochMs, c.lastViewedEpochMs))
                after = page.last().id
            }
            after = 0L
            while (true) {
                val page = db.messageDao().exportPage(after, PAGE, now)
                if (page.isEmpty()) break
                for (m in page) line(
                    // Named, not positional: a field inserted anywhere but the end used to shift
                    // every argument after it, silently and without a compile error.
                    BackupRecord.Message(
                        id = m.id, conversationId = m.conversationId, sourceMessageId = m.sourceMessageId,
                        senderName = m.senderName, senderKey = m.senderKey, isSelf = m.isSelf, body = m.body,
                        kind = m.kind, sourceTimestampEpochMs = m.sourceTimestampEpochMs,
                        timestampQuality = m.timestampQuality, observedAtEpochMs = m.observedAtEpochMs,
                        postedAtEpochMs = m.postedAtEpochMs, origin = m.origin, contentStatus = m.contentStatus,
                        dedupState = m.dedupState, revisionCount = m.revisionCount,
                        observationCount = m.observationCount, mediaState = m.mediaState,
                        mediaBlobId = m.mediaBlobId, mediaMimeType = m.mediaMimeType,
                        fingerprint = m.fingerprint, sortKey = m.sortKey, expiresAtEpochMs = m.expiresAtEpochMs,
                        truncationFlags = m.truncationFlags,
                    ),
                )
                after = page.last().id
            }
            after = 0L
            while (true) {
                val page = db.revisionDao().exportPage(after, PAGE, now)
                if (page.isEmpty()) break
                for (r in page) line(BackupRecord.Revision(r.messageId, r.body, r.observedAtEpochMs))
                after = page.last().id
            }
            expected to db.mediaDao().maxId()
        }
        // Outside the transaction, one page at a time: disk reads and AEAD decryption of every blob.
        var mediaWritten = 0
        var skipped = 0
        var after = 0L
        while (true) {
            val page = db.mediaDao().exportPage(after, mediaMaxId, PAGE, now)
            if (page.isEmpty()) break
            for (b in page) {
                val bytes = when (val r = blobCipher.decryptFile(mediaDir.file(b.fileName))) {
                    is KeyResult.Ok -> r.value
                    is KeyResult.Failed -> {
                        skipped++
                        continue
                    }
                }
                if (bytes.size > BackupLimits.MAX_MEDIA_BYTES) {
                    skipped++
                    continue
                }
                line(BackupRecord.Media(b.id, b.messageId, b.mimeType, b.width, b.height, b.createdAtEpochMs, Base64.encodeToString(bytes, Base64.NO_WRAP)))
                mediaWritten++
            }
            after = page.last().id
        }
        val actual = expected.copy(media = mediaWritten)
        line(BackupRecord.End(actual, skipped))
        w.flush()
        return Written(actual, skipped)
    }

    /**
     * Stage and verify the file *outside* exclusive maintenance, then apply under a short exclusive
     * section. Holding the exclusive lock across `InputStream.read` used to pin "Delete everything"
     * for as long as an uncooperative provider stayed silent.
     */
    suspend fun import(source: Uri, recoveryKeyText: String): BackupResult {
        val op: BackupOp
        synchronized(importIo) {
            op = BackupOp(importSeq.incrementAndGet(), importGen.get())
            lastImportOp.set(op)
        }
        val gen = op.gen
        val epoch = keyMaterial.epoch
        val staged = try {
            stageFromSource(op, source, recoveryKeyText)
        } catch (e: CancellationException) {
            // stageFromSource already marked the op cancelled and abandoned its stream.
            return op.settled.get() ?: BackupResult.Failed(BackupResult.Reason.ABORTED)
        } catch (e: java.io.IOException) {
            return BackupResult.Failed(BackupResult.Reason.WRONG_KEY_OR_TAMPERED, e::class.java.simpleName)
        } catch (e: java.security.GeneralSecurityException) {
            return BackupResult.Failed(BackupResult.Reason.WRONG_KEY_OR_TAMPERED, e::class.java.simpleName)
        } catch (e: kotlinx.serialization.SerializationException) {
            return BackupResult.Failed(BackupResult.Reason.CORRUPT, "record")
        } catch (e: StagingException) {
            return BackupResult.Failed(e.reason, e.message)
        } catch (e: Exception) {
            if (e is CancellationException) {
                return op.settled.get() ?: BackupResult.Failed(BackupResult.Reason.ABORTED)
            }
            return BackupResult.Failed(BackupResult.Reason.IO, e::class.java.simpleName)
        }
        try {
            if (!importOpActive(op)) return BackupResult.Failed(BackupResult.Reason.ABORTED)
            afterStaged()
            if (!importOpActive(op)) return BackupResult.Failed(BackupResult.Reason.ABORTED)
            if (keyMaterial.epoch != epoch) return BackupResult.Failed(BackupResult.Reason.KEY_UNAVAILABLE)
            val done = CompletableDeferred<BackupResult>()
            importReads.launch {
                try {
                    val r = withContext(Dispatchers.IO) {
                        val mediaBytes = staged.media.sumOf { it.dataBase64.length.toLong() * 3 / 4 }
                        val free = freeBytes()
                        if (free < mediaBytes + LOW_SPACE_FLOOR_BYTES) {
                            return@withContext BackupResult.Failed(BackupResult.Reason.LOW_SPACE, "need ${mediaBytes + LOW_SPACE_FLOOR_BYTES}, free $free")
                        }
                        maintenance.exclusive {
                            if (!importStillActive(gen)) return@exclusive BackupResult.Failed(BackupResult.Reason.ABORTED)
                            if (keyMaterial.epoch != epoch) return@exclusive BackupResult.Failed(BackupResult.Reason.KEY_UNAVAILABLE)
                            val db = try {
                                holder.db()
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                return@exclusive BackupResult.Failed(BackupResult.Reason.VAULT_UNAVAILABLE)
                            }
                            apply(db, staged, gen, op)
                        }
                    }
                    if (r is BackupResult.Ok) op.settled.compareAndSet(null, r)
                    done.complete(r)
                } catch (e: Throwable) {
                    val published = op.settled.get()
                    if (published != null) done.complete(published)
                    else done.complete(
                        if (e is CancellationException) BackupResult.Failed(BackupResult.Reason.ABORTED)
                        else BackupResult.Failed(BackupResult.Reason.IO, e::class.java.simpleName),
                    )
                }
            }
            return try {
                done.await()
            } catch (e: CancellationException) {
                // The vault write is not the waiter's job: wait for COMMIT to publish, never
                // treat "not yet settled" as "the vault was not changed".
                withContext(NonCancellable) { done.await() }
            }
        } catch (e: CancellationException) {
            // Apply already launched: do not revoke the op, waiters above use NonCancellable.
            return op.settled.get() ?: BackupResult.Failed(BackupResult.Reason.ABORTED)
        }
    }

    /** Test seam: production opens the user's document. */
    internal var openInput: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) }

    /** Test seam: production opens the user's chosen destination. */
    internal var openOutput: (Uri) -> OutputStream? = { context.contentResolver.openOutputStream(it, "wt") }

    /** Test seam: production encrypts a restored blob to a vault file. */
    internal var writeMedia: (ByteArray, File) -> Boolean = { bytes, file ->
        blobCipher.encryptToFile(bytes, file) is KeyResult.Ok
    }

    /**
     * Test seam: called after the stream has been staged and verified, before exclusive apply.
     * Production is a no-op.
     */
    internal var afterStaged: suspend () -> Unit = {}

    /** Test seam: production is a no-op. Called on the thread that is about to write the vault. */
    internal var applyThreadProbe: () -> Unit = {}

    /**
     * Close a provider stream on the import-read pool without waiting. Callers must pass a stream
     * they have already taken off [importStreams] so each stream is closed at most once from
     * here; retries must not enqueue another blocked close.
     */
    private fun abandonClose(stream: InputStream) {
        importReads.launch { runCatching { stream.close() } }
    }

    private fun abandonCloseOutput(stream: OutputStream) {
        exportWrites.launch { runCatching { stream.close() } }
    }

    private suspend fun stageFromSource(op: BackupOp, source: Uri, recoveryKeyText: String): Staged {
        val result = CompletableDeferred<Staged>()
        importReads.launch {
            if (liveImportReads.incrementAndGet() > MAX_LIVE_IMPORT_READS) {
                liveImportReads.decrementAndGet()
                result.completeExceptionally(StagingException(BackupResult.Reason.IO, "read slot"))
                return@launch
            }
            var input: InputStream? = null
            var key: ByteArray? = null
            try {
                val opened = openInput(source) ?: throw StagingException(BackupResult.Reason.IO, "open")
                val claimed: Boolean
                synchronized(importIo) {
                    if (op.cancelled.get() || op.gen != importGen.get()) {
                        input = opened
                        claimed = false
                    } else {
                        input = opened
                        importStreams[op.token] = opened
                        claimed = true
                    }
                }
                if (!claimed) throw StagingException(BackupResult.Reason.ABORTED, "aborted")
                key = RecoveryKeyCodec.decode(recoveryKeyText)
                    ?: throw StagingException(BackupResult.Reason.WRONG_KEY_OR_TAMPERED, "key format")
                result.complete(readAndStage(opened, key))
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            } finally {
                key?.fill(0)
                input?.let { stream ->
                    synchronized(importIo) { importStreams.remove(op.token, stream) }
                    runCatching { stream.close() }
                }
                liveImportReads.decrementAndGet()
            }
        }
        try {
            return result.await()
        } catch (e: CancellationException) {
            dropImport(op)?.let { abandonClose(it) }
            throw e
        }
    }

    private fun readAndStage(raw: InputStream, key: ByteArray): Staged {
        val header = ByteArray(BackupCrypto.HEADER_BYTES)
        var read = 0
        while (read < header.size) {
            val n = raw.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        // A file that ends inside its own header is a half-copied file, not a foreign one:
        // the zero-filled tail used to pass the magic and version checks and die in Tink
        // as "wrong key or modified" (audit-2 ATOM-2).
        if (read < header.size) throw StagingException(BackupResult.Reason.TRUNCATED, "header $read/${header.size}")
        val salt = BackupCrypto.parseHeader(header) ?: throw StagingException(BackupResult.Reason.BAD_HEADER)
        val saead = BackupCrypto.streamingAead(key, salt)
        val dec = saead.newDecryptingStream(raw, header)
        return stage(dec.bufferedReader(Charsets.UTF_8))
    }

    /** Bytes the restore wants free beyond the decoded media: room for the rows and the WAL. */
    internal val LOW_SPACE_FLOOR_BYTES: Long = 32L * 1024 * 1024

    /** Free bytes on the volume that holds the vault and its media; a test seam, production never sets it. */
    internal var freeBytes: () -> Long = { android.os.StatFs(mediaDir.dir.parentFile?.path ?: mediaDir.dir.path).availableBytes }

    private val stager = BackupStager(json)

    internal fun stage(reader: BufferedReader): Staged = stager.stage(reader)

    /**
     * Merge-restore: existing conversations are matched by scope + identity. Only messages that
     * already existed *before* this import are skipped (same fingerprint + sort key + observed
     * time), so legitimate duplicates inside the backup keep their multiplicity.
     */
    private suspend fun apply(db: QuietInboxDatabase, s: Staged, abortGen: Long, op: BackupOp): BackupResult {
        applyThreadProbe()
        fun checkToken() {
            if (abortGen != importGen.get()) throw BackupAborted()
        }
        // Every blob written to disk and not yet owned by a committed row. Trimmed to the unused
        // ones *inside* the transaction (see below), so the same list is right on every exit.
        val writtenFiles = ArrayList<String>()
        val usedFiles = HashSet<String>() // blobs referenced by a message that was actually inserted
        var committed: BackupResult.Ok? = null
        // Blobs are decoded and encrypted to disk BEFORE the write transaction so the SQLite write
        // lock is never held during Tink work and file I/O (live capture would otherwise stall).
        class Prepared(val fileName: String, val byteCount: Long)
        val prepared = HashMap<Long, Prepared?>() // old message id -> encrypted file, or null when it failed
        val now = System.currentTimeMillis()
        val retentionMs = settings.current().retentionDays * 24L * 60L * 60L * 1000L
        return try {
            // Inside the try so a failure or cancellation while encrypting still removes every file.
            for (media in s.media) {
                checkToken()
                val oldId = media.messageId ?: continue
                val bytes = runCatching { Base64.decode(media.dataBase64, Base64.NO_WRAP) }.getOrNull()
                // Empty is what Android Base64 returns for a string of invalid characters: it is
                // not a picture, and must not become a LOCAL_COPY of zero bytes (audit-2 ATOM-3).
                if (bytes == null || bytes.isEmpty() || bytes.size > BackupLimits.MAX_MEDIA_BYTES) {
                    prepared[oldId] = null
                    continue
                }
                val name = UUID.randomUUID().toString().replace("-", "")
                if (writeMedia(bytes, mediaDir.file(name))) {
                    writtenFiles += name
                    prepared[oldId] = Prepared(name, bytes.size.toLong())
                } else {
                    prepared[oldId] = null
                }
            }
            val counts = db.withTransaction {
                checkToken()
                for (src in s.sources) {
                    if (db.sourceDao().get(src.packageName) == null) {
                        // Restoring never silently starts capturing: sources come back disabled and are re-enabled in Capture.
                        db.sourceDao().upsert(SourceConfigurationEntity(src.packageName, src.displayName, false, src.paused, src.retentionDays, src.mediaEnabled, src.addedAtEpochMs, src.adapterId))
                    }
                }
                val convMap = HashMap<Long, Long>()
                for (c in s.conversations) {
                    val existing = db.conversationDao().find(c.packageName, c.profileKey, c.accountKey, c.identityKey)
                    convMap[c.id] = existing?.id ?: db.conversationDao().insert(
                        ConversationEntity(
                            packageName = c.packageName, profileKey = c.profileKey, accountKey = c.accountKey, identityKey = c.identityKey,
                            identityConfidence = c.identityConfidence, title = c.title, isGroup = c.isGroup, pinned = c.pinned, archived = c.archived,
                            createdAtEpochMs = c.createdAtEpochMs, lastActivityEpochMs = c.lastActivityEpochMs, lastViewedEpochMs = c.lastViewedEpochMs,
                            messageCount = 0, ambiguousCount = 0, summaryOnlyCount = 0, lastMessagePreview = null, lastSenderName = null,
                        ),
                    )
                }
                val msgMap = HashMap<Long, Long>()
                val mediaByOldMessage = s.media.filter { it.messageId != null }.associateBy { it.messageId!! }
                // Pre-existing content per conversation, computed once (O(n)), before any insert.
                // Kept per key as the rows themselves, so an existing duplicate consumes exactly
                // one backup copy — and so the copy it consumed can still add what the row lacks.
                val preExisting = HashMap<Long, HashMap<String, ArrayDeque<MessageEntity>>>()
                for (cid in convMap.values.distinct()) {
                    val rows = HashMap<String, ArrayDeque<MessageEntity>>()
                    for (row in db.messageDao().forConversation(cid)) rows.getOrPut("${row.fingerprint}|${row.sortKey}|${row.observedAtEpochMs}") { ArrayDeque() }.addLast(row)
                    preExisting[cid] = rows
                }
                var inserted = 0
                var mediaNotRestored = 0
                var mediaAbsent = 0
                var skippedOrphans = 0
                for (m in s.messages) {
                    val cid = convMap[m.conversationId]
                    if (cid == null) {
                        skippedOrphans++
                        continue
                    }
                    val dupKey = "${m.fingerprint}|${m.sortKey}|${m.observedAtEpochMs}"
                    val existing = preExisting.getValue(cid)[dupKey]?.removeFirstOrNull()
                    if (existing != null) {
                        // The row is already here, but the copy may know something it does not:
                        // the truncation flag is set on a row *after* insert when a later repost of
                        // the same bounded body arrives shortened, and that update changes none of
                        // the three columns the key is made of. A backup taken after that update,
                        // merged into a vault restored from one taken before it, used to lose the
                        // flag on the way in — and then the bubble read as complete again (round 35
                        // Codex I2). Single-valued, so "add what is missing" is exactly the write
                        // the live path makes; nothing here ever clears a flag the row already has.
                        val flag = m.truncationFlags
                        if (flag != null && existing.truncationFlags == null) db.messageDao().markTruncated(existing.id, flag)
                        val media = mediaByOldMessage[m.id]
                        val blob = media?.let { prepared[m.id] }
                        val leaveAttach = existing.mediaState == MediaState.DISABLED_BY_USER.name ||
                            (existing.mediaState == MediaState.LOCAL_COPY.name && existing.mediaBlobId != null)
                        if (leaveAttach) continue
                        if (media != null && blob == null) {
                            mediaNotRestored++
                            continue
                        }
                        if (blob != null) {
                            usedFiles += blob.fileName
                            val blobId = db.mediaDao().insert(
                                MediaBlobEntity(
                                    messageId = existing.id, fileName = blob.fileName, thumbFileName = null,
                                    mimeType = media.mimeType, byteCount = blob.byteCount, width = media.width,
                                    height = media.height, state = MediaState.LOCAL_COPY.name, failureReason = null,
                                    createdAtEpochMs = media.createdAtEpochMs,
                                ),
                            )
                            db.messageDao().setMedia(existing.id, MediaState.LOCAL_COPY.name, blobId)
                        }
                        continue
                    }
                    // Insert the message first, then the blob bound to its new id (retention treats
                    // messageId == null blobs as orphans and would delete them otherwise).
                    var mediaState = m.mediaState
                    val media = mediaByOldMessage[m.id]
                    val blob = media?.let { prepared[m.id] }
                    if (media != null && blob == null) {
                        // In the file, not in the vault: the row says so, and so does the result.
                        mediaState = MediaState.FAILED.name
                        mediaNotRestored++
                    } else if (media == null && m.mediaState == MediaState.LOCAL_COPY.name) {
                        // Export skipped the blob and kept the message. Distinct from a decode/write fail.
                        mediaState = MediaState.FAILED.name
                        mediaAbsent++
                    }
                    if (blob != null) mediaState = MediaState.PENDING.name
                    val newId = db.messageDao().insert(
                        MessageEntity(
                            conversationId = cid, sourceMessageId = m.sourceMessageId, senderName = m.senderName, senderKey = m.senderKey, isSelf = m.isSelf,
                            body = m.body, kind = m.kind, sourceTimestampEpochMs = m.sourceTimestampEpochMs, timestampQuality = m.timestampQuality,
                            observedAtEpochMs = m.observedAtEpochMs, postedAtEpochMs = m.postedAtEpochMs, origin = m.origin, contentStatus = m.contentStatus,
                            dedupState = m.dedupState, revisionCount = m.revisionCount, observationCount = m.observationCount, mediaState = mediaState,
                            mediaBlobId = null, mediaUri = null, mediaMimeType = m.mediaMimeType, fingerprint = m.fingerprint, eventId = "restore:${s.manifest.createdAtEpochMs}",
                            sortKey = m.sortKey, truncationFlags = m.truncationFlags,
                            // A backup older than the retention window must not be swept on the next
                            // retention run; expiry is re-based on the current setting.
                            expiresAtEpochMs = m.expiresAtEpochMs?.let { maxOf(it, now + retentionMs) },
                        ),
                    )
                    if (blob != null) {
                        usedFiles += blob.fileName
                        val blobId = db.mediaDao().insert(MediaBlobEntity(messageId = newId, fileName = blob.fileName, thumbFileName = null, mimeType = media.mimeType, byteCount = blob.byteCount, width = media.width, height = media.height, state = MediaState.LOCAL_COPY.name, failureReason = null, createdAtEpochMs = media.createdAtEpochMs))
                        db.messageDao().setMedia(newId, MediaState.LOCAL_COPY.name, blobId)
                    }
                    msgMap[m.id] = newId
                    inserted++
                    val tokens = SearchNormalizer.tokens(SearchNormalizer.normalize(m.body))
                    if (tokens.isNotEmpty()) db.searchDao().insertTokens(tokens.map { SearchTokenEntity(it, newId) })
                }
                var restoredRevisions = 0
                for (r in s.revisions) {
                    val mid = msgMap[r.messageId] ?: continue
                    restoredRevisions++
                    db.revisionDao().insert(MessageRevisionEntity(messageId = mid, body = r.body, observedAtEpochMs = r.observedAtEpochMs, eventId = "restore"))
                }
                // The one projection rebuild every deletion, expiry and restore shares (QI-DATA-004).
                db.conversationDao().rebuildProjection(convMap.values.distinct(), now)
                if (skippedOrphans > 0) {
                    db.diagnosticsDao().insert(dev.quietinbox.platform.storage.db.DiagnosticEventEntity(code = "RESTORE_ORPHAN_MESSAGES", detail = skippedOrphans.toString(), packageName = null, atEpochMs = System.currentTimeMillis()))
                }
                restoreProbe(RestorePoint.IN_TRANSACTION)
                checkToken()
                // Trimmed inside the transaction, as MediaCopier.store clears its list: a
                // cancellation landing between the commit and the return of withTransaction is
                // delivered as an exception from a call whose rows are already durable, and a flag
                // set after the call cannot tell that exit from a rollback. Until this line the list
                // is every blob (a rollback owns none of them); from here it is only the blobs no
                // inserted message references (duplicates, orphans), which is all either exit may
                // remove. A commit that fails after this line leaves the linked blobs as orphan
                // files for the retention sweep, a leak, never a loss (audit-2 ATOM-4).
                writtenFiles.removeAll(usedFiles)
                // The trim belongs in this transaction: moving it to after withTransaction returns
                // leaves used files on the cleanup list, and a cancellation on the way out deletes
                // the blobs the committed rows point at (round 40, Codex I2).
                check(usedFiles.none { it in writtenFiles })
                Triple(Counts(s.sources.size, convMap.size, inserted, restoredRevisions, usedFiles.size), mediaNotRestored, mediaAbsent)
            }
            committed = BackupResult.Ok(counts.first, skippedMedia = counts.third, mediaNotRestored = counts.second)
            op.settled.set(committed)
            restoreProbe(RestorePoint.AFTER_COMMIT)
            // Blobs prepared for messages that were skipped (duplicates, orphans) have no row: remove them.
            for (f in writtenFiles) mediaDir.delete(f)
            committed
        } catch (e: Exception) {
            // Before the commit every blob is an orphan; after it only the unreferenced ones are
            // still on the list. Runs before the rethrow. A stop after the rows are durable is
            // still a completed restore, not "cancelled, nothing changed".
            for (f in writtenFiles) mediaDir.delete(f)
            committed?.let { return it }
            if (e is BackupAborted) return BackupResult.Failed(BackupResult.Reason.ABORTED)
            if (e is CancellationException) throw e
            BackupResult.Failed(BackupResult.Reason.IO, "apply:${e::class.java.simpleName}")
        }
    }

    /** Where a restore can be interrupted by a test; production never sets [restoreProbe]. */
    internal enum class RestorePoint { IN_TRANSACTION, AFTER_COMMIT }

    /**
     * Test seam: called at each [RestorePoint] of a restore. A cancellation thrown from it lands
     * exactly where a real one would, so the instrumented tests can prove what each exit keeps.
     */
    internal var restoreProbe: suspend (RestorePoint) -> Unit = {}
}
