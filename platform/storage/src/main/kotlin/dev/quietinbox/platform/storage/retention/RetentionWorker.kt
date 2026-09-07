package dev.quietinbox.platform.storage.retention

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultUnavailableException
import kotlinx.coroutines.CancellationException
import dev.quietinbox.platform.storage.settings.SettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TTL clean-up (plan section 8): expired messages with their revisions, links, index tokens and
 * media files; journal rows past their TTL; suppression tokens; old diagnostics and gaps.
 * WorkManager gives no millisecond guarantee, so the UI filters expired content separately.
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val retention: RetentionService,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        // Refused (null) or cancelled by a maintenance run: try again later, nothing was lost.
        if (retention.runOnce(System.currentTimeMillis()) == null) Result.retry() else Result.success()
    } catch (e: VaultUnavailableException) {
        Result.retry()
    } catch (e: CancellationException) {
        if (isStopped) throw e
        Result.retry()
    } catch (e: Exception) {
        Result.failure()
    }
}

@Singleton
class RetentionService @Inject constructor(
    private val holder: DatabaseHolder,
    private val settings: SettingsRepository,
    private val mediaDir: MediaDirectory,
    private val maintenance: VaultMaintenance,
) {
    /** Null when a maintenance run (reset, restore) is in progress; the worker retries later. */
    suspend fun runOnce(now: Long): RetentionReport? = maintenance.work { sweep(now) }

    private suspend fun sweep(now: Long): RetentionReport {
        val db = holder.db()
        val s = settings.current()
        var deletedMessages = 0
        while (true) {
            val ids = db.messageDao().expiredIds(now, 500)
            if (ids.isEmpty()) break
            // Media rows and the projection go with the messages in one transaction; files after it.
            val files = db.withTransaction {
                val conversations = db.messageDao().conversationIdsOf(ids)
                val blobs = db.mediaDao().forMessages(ids)
                if (blobs.isNotEmpty()) db.mediaDao().delete(blobs.map { it.id })
                db.messageDao().delete(ids)
                db.conversationDao().rebuildProjection(conversations, now)
                blobs.flatMap { listOfNotNull(it.fileName, it.thumbFileName) }
            }
            for (f in files) mediaDir.delete(f)
            deletedMessages += ids.size
        }
        val orphans = db.mediaDao().orphans()
        for (blob in orphans) {
            mediaDir.delete(blob.fileName)
            blob.thumbFileName?.let { mediaDir.delete(it) }
        }
        if (orphans.isNotEmpty()) db.mediaDao().delete(orphans.map { it.id })

        // Row-level reclamation above cannot see a file whose row never committed — process death
        // between `BlobCipher.encryptToFile` and the transaction, or an interrupted restore, leaves
        // permanent dead weight nothing ever looks at (QI-MEDIA-017). Names are compared against
        // every name a row still points at, and a file younger than the grace window is left alone:
        // `work {}` runs concurrently with a copy that has written its file but not yet committed.
        val live = db.mediaDao().allFileNames().toHashSet()
        val strayCutoff = now - STRAY_FILE_GRACE_MS
        val stray = mediaDir.namesWithAge().filter { (name, modified) -> name !in live && modified < strayCutoff }
        for ((name, _) in stray) mediaDir.delete(name)

        // `pendingMedia` had no caller anywhere: a copy that threw inside store(), or was cancelled
        // by a reset or restore, left its row PENDING for ever and the bubble showed an hourglass
        // that never resolved (QI-MEDIA-015). The state written is FAILED, not URI_EXPIRED: the
        // copy is known not to have finished, but nothing here observed why.
        val stalePending = db.messageDao().pendingMedia(500).filter { it.observedAtEpochMs < now - PENDING_MEDIA_GRACE_MS }
        for (row in stalePending) db.messageDao().setMedia(row.id, MediaState.FAILED.name, null)

        val journal = db.journalDao().deleteExpired(now)
        val suppression = db.suppressionDao().deleteExpired(now)
        val diagCutoff = now - 30L * DAY_MS
        val diagnostics = db.diagnosticsDao().deleteBefore(diagCutoff)
        val gapCutoff = now - s.retentionDays.toLong() * DAY_MS
        db.healthDao().deleteGapsBefore(gapCutoff)
        db.healthDao().deleteSessionsBefore(gapCutoff)
        db.healthDao().deleteSummariesBefore(gapCutoff)
        db.checkpointDao().deleteStale(now - 14L * DAY_MS)
        val emptyConversations = db.conversationDao().emptyOlderThan(now - 7L * DAY_MS)
        for (id in emptyConversations) db.conversationDao().delete(id)
        return RetentionReport(deletedMessages, orphans.size, journal, suppression, diagnostics, emptyConversations.size, stray.size, stalePending.size)
    }

    companion object {
        const val DAY_MS: Long = 24L * 60 * 60 * 1000

        /** A file younger than this may belong to a copy that has not committed its row yet. */
        const val STRAY_FILE_GRACE_MS: Long = 60L * 60 * 1000

        /** How long a media copy may stay PENDING before the sweep calls it failed. */
        const val PENDING_MEDIA_GRACE_MS: Long = 60L * 60 * 1000
        const val WORK_NAME = "quietinbox.retention"

        fun schedule(context: Context) {
            // No battery constraint: the sweep is a few indexed deletes, and an expired copy that
            // lingers because the phone is at 14 % is a privacy defect, not a saving (QI-DATA-004).
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

data class RetentionReport(
    val deletedMessages: Int,
    val deletedMedia: Int,
    val deletedJournal: Int,
    val deletedSuppressions: Int,
    val deletedDiagnostics: Int,
    val deletedEmptyConversations: Int,
    /** Files on disk that no blob row pointed at any more. */
    val deletedStrayFiles: Int = 0,
    /** Copies stuck in PENDING long enough to be called failed. */
    val settledPendingMedia: Int = 0,
)

/** Location of encrypted media blobs; file names are opaque ids only. */
@Singleton
class MediaDirectory @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
) {
    val dir: File = File(context.filesDir, "media")

    fun file(name: String): File = File(dir, name)

    fun delete(name: String) {
        runCatching { File(dir, name).delete() }
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** File name to last-modified time, for the sweep that reclaims files no row points at. */
    fun namesWithAge(): List<Pair<String, Long>> = dir.listFiles()?.map { it.name to it.lastModified() } ?: emptyList()

    /** Deletes every blob; false when a file survived (the caller must not report a reset as done). */
    fun deleteAll(): Boolean {
        dir.listFiles()?.forEach { it.delete() }
        return dir.listFiles().isNullOrEmpty()
    }
}
