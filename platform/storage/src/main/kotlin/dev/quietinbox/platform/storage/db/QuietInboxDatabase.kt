package dev.quietinbox.platform.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The single encrypted vault. Every table listed in plan section 8 lives here so that one
 * SQLCipher key protects messages, search index, checkpoints and media references together.
 *
 * Migrations must be explicit; `fallbackToDestructiveMigration()` is forbidden by the plan.
 */
@Database(
    entities = [
        SourceConfigurationEntity::class,
        CaptureSessionEntity::class,
        GapIntervalEntity::class,
        EventJournalEntity::class,
        CheckpointEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        MessageRevisionEntity::class,
        ObservationLinkEntity::class,
        MediaBlobEntity::class,
        DeletionSuppressionEntity::class,
        SearchTokenEntity::class,
        SummaryObservationEntity::class,
        DiagnosticEventEntity::class,
    ],
    version = QuietInboxDatabase.VERSION,
    exportSchema = true,
)
abstract class QuietInboxDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun journalDao(): JournalDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun revisionDao(): RevisionDao
    abstract fun observationLinkDao(): ObservationLinkDao
    abstract fun mediaDao(): MediaDao
    abstract fun suppressionDao(): SuppressionDao
    abstract fun searchDao(): SearchDao
    abstract fun healthDao(): HealthDao
    abstract fun diagnosticsDao(): DiagnosticsDao

    /** Debug-only demo seeding/clearing. Adds no table and no column. */
    abstract fun demoDao(): DemoDao

    companion object {
        const val VERSION = 4
        const val FILE_NAME = "quietinbox.vault"

        /**
         * v1 -> v2: deletion suppression is keyed by stable conversation identity instead of the
         * conversation row id (existing tokens are re-keyed through their conversation row, so a
         * deletion made before the upgrade still holds), and checkpoints remember the post time so
         * an active-notification resync is recognised as a repost. No user content is touched.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Re-key existing tokens through their conversation row so no suppression is lost.
                // The string must equal SourceScope.key + "#" + identityKey exactly.
                db.execSQL("ALTER TABLE deletion_suppression RENAME TO deletion_suppression_old")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS deletion_suppression (" +
                        "scopeKey TEXT NOT NULL, fingerprint TEXT NOT NULL, expiresAtEpochMs INTEGER NOT NULL, " +
                        "PRIMARY KEY(scopeKey, fingerprint))",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO deletion_suppression (scopeKey, fingerprint, expiresAtEpochMs) " +
                        "SELECT c.packageName || '|' || c.profileKey || " +
                        "CASE WHEN c.accountKey IS NULL THEN '' ELSE '|' || c.accountKey END || '#' || c.identityKey, " +
                        "s.fingerprint, s.expiresAtEpochMs " +
                        "FROM deletion_suppression_old s JOIN conversation c ON c.id = s.conversationId",
                )
                db.execSQL("DROP TABLE deletion_suppression_old")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_deletion_suppression_expiresAtEpochMs ON deletion_suppression (expiresAtEpochMs)")
                db.execSQL("ALTER TABLE notification_checkpoint ADD COLUMN postedAtEpochMs INTEGER")
            }
        }

        /**
         * v2 -> v3: the journal remembers the source package (so a disabled or removed source can
         * discard its pending rows) and a deletion token remembers the deleted message's source id
         * and post time (so suppression can tell a replay from a genuinely new message). Columns
         * are added nullable; no row is rewritten and no user content is touched.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE event_journal ADD COLUMN packageName TEXT")
                db.execSQL("ALTER TABLE deletion_suppression ADD COLUMN sourceMessageId TEXT")
                db.execSQL("ALTER TABLE deletion_suppression ADD COLUMN postedAtEpochMs INTEGER")
            }
        }

        /**
         * Two additive nullable columns, no rewrite of any row.
         *
         * `gap_interval.packageName` lets a gap say which source it belongs to — the disable and
         * pause gaps this release adds, and the two drop sites that know a package. It deliberately
         * has no conversation column: of the sixteen places that record a gap — fifteen in the
         * coordinator and one in `HealthRepository`, for a session the last process never closed —
         * ten are process-wide, and none of the rest can know a conversation either. The six that name a
         * source are written before identity is resolved — at the queue, at acceptance, or from a
         * policy change — so there is no conversation to name at the moment of writing, whether or
         * not an ingest follows.
         *
         * `message.truncationFlags` records what the snapshot had to cut, so a body that was
         * shortened is no longer displayed as if it were complete.
         *
         * `event_journal.lossRecorded` says whether the loss an event arrived with has reached the
         * gap table. A row carried over from 0.1.3 defaults to 0, which is the truth about it:
         * that release recorded such a loss nowhere, so nothing has been written for it yet, and
         * the upgrade path claims each one exactly once by moving this column with the gap in one
         * transaction. Schema 4 is unreleased — 0.1.3 shipped 3 — so the column joins this
         * migration rather than adding one nobody would ever run from, as round 33 decided for the
         * two columns above. No user content is touched.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gap_interval ADD COLUMN packageName TEXT")
                db.execSQL("ALTER TABLE message ADD COLUMN truncationFlags TEXT")
                db.execSQL("ALTER TABLE event_journal ADD COLUMN lossRecorded INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
