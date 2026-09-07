package dev.quietinbox.platform.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.platform.storage.db.QuietInboxDatabase
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema migrations run against the exported schema JSON (plan section 8 forbids destructive
 * migration). The SQL is engine-agnostic, so the framework SQLite driver is enough here; the
 * SQLCipher path is covered by [VaultRoundTripTest].
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        QuietInboxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2ReKeysExistingSuppressionTokens() {
        val name = "$dbName-rekey"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO conversation (id, packageName, profileKey, accountKey, identityKey, identityConfidence, title, isGroup, pinned, archived, createdAtEpochMs, lastActivityEpochMs, lastViewedEpochMs, messageCount, ambiguousCount, summaryOnlyCount, lastMessagePreview, lastSenderName) " +
                    "VALUES (7, 'jp.naver.line.android', 'user:0', NULL, 'shortcut:abc', 'INFERRED_FROM_STREAM', 'A', NULL, 0, 0, 1, 1, NULL, 0, 0, 0, NULL, NULL)",
            )
            db.execSQL("INSERT INTO deletion_suppression (conversationId, fingerprint, expiresAtEpochMs) VALUES (7, 'fp-deleted', 99)")
        }
        val migrated = helper.runMigrationsAndValidate(name, 2, true, QuietInboxDatabase.MIGRATION_1_2)
        migrated.query("SELECT scopeKey, fingerprint, expiresAtEpochMs FROM deletion_suppression").use { c ->
            c.moveToFirst() shouldBe true
            c.getString(0) shouldBe "jp.naver.line.android|user:0#shortcut:abc"
            c.getString(1) shouldBe "fp-deleted"
            c.getLong(2) shouldBe 99L
        }
        migrated.close()
    }

    @Test
    fun migrate1To2RecreatesSuppressionKeyedByIdentity() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL("INSERT INTO deletion_suppression (conversationId, fingerprint, expiresAtEpochMs) VALUES (5, 'fp', 1)")
            db.execSQL(
                "INSERT INTO conversation (packageName, profileKey, accountKey, identityKey, identityConfidence, title, isGroup, pinned, archived, createdAtEpochMs, lastActivityEpochMs, lastViewedEpochMs, messageCount, ambiguousCount, summaryOnlyCount, lastMessagePreview, lastSenderName) " +
                    "VALUES ('pkg', 'user:0', NULL, 'title:A', 'UNRESOLVED', 'A', NULL, 0, 0, 1, 1, NULL, 0, 0, 0, NULL, NULL)",
            )
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 2, true, QuietInboxDatabase.MIGRATION_1_2)
        // The pre-upgrade token (conversationId 5 did not exist) is dropped; nothing else to keep.
        migrated.query("SELECT COUNT(*) FROM deletion_suppression").use { c ->
            c.moveToFirst() shouldBe true
            c.getInt(0) shouldBe 0
        }
        // User content is untouched by the migration.
        migrated.query("SELECT COUNT(*) FROM conversation").use { c ->
            c.moveToFirst() shouldBe true
            c.getInt(0) shouldBe 1
        }
        migrated.execSQL("INSERT INTO deletion_suppression (scopeKey, fingerprint, expiresAtEpochMs) VALUES ('pkg|user:0#title:A', 'fp', 1)")
        migrated.close()
    }

    /** v3 only adds nullable columns: every v2 row survives unchanged and reads back with nulls. */
    @Test
    fun migrate2To3AddsNullableColumnsAndKeepsRows() {
        val name = "$dbName-v3"
        helper.createDatabase(name, 2).use { db ->
            db.execSQL(
                "INSERT INTO event_journal (eventId, generation, receivedAtEpochMs, expiresAtEpochMs, state, attempts, failureCode, payload) " +
                    "VALUES ('e1', 'g', 1, 2, 'PENDING', 0, NULL, '{}')",
            )
            db.execSQL("INSERT INTO deletion_suppression (scopeKey, fingerprint, expiresAtEpochMs) VALUES ('pkg|user:0#title:A', 'fp', 1)")
        }
        val migrated = helper.runMigrationsAndValidate(name, 3, true, QuietInboxDatabase.MIGRATION_2_3)
        migrated.query("SELECT eventId, state, payload, packageName FROM event_journal").use { c ->
            c.moveToFirst() shouldBe true
            c.getString(0) shouldBe "e1"
            c.getString(1) shouldBe "PENDING"
            c.getString(2) shouldBe "{}"
            c.isNull(3) shouldBe true
        }
        migrated.query("SELECT scopeKey, fingerprint, sourceMessageId, postedAtEpochMs FROM deletion_suppression").use { c ->
            c.moveToFirst() shouldBe true
            c.getString(0) shouldBe "pkg|user:0#title:A"
            c.isNull(2) shouldBe true
            c.isNull(3) shouldBe true
        }
        migrated.execSQL("INSERT INTO event_journal (eventId, generation, receivedAtEpochMs, expiresAtEpochMs, state, attempts, failureCode, payload, packageName) VALUES ('e2', 'g', 1, 2, 'PENDING', 0, NULL, '{}', 'pkg')")
        migrated.close()
    }

    /**
     * v4 adds `gap_interval.packageName`, `message.truncationFlags` and
     * `event_journal.lossRecorded`. All three are additive: an existing gap stays a gap and an
     * existing message stays readable, with the new columns null rather than a fabricated value,
     * and a pending journal row keeps its payload and comes back unsettled — which is the truth
     * about it, since 0.1.3 recorded the loss such a row arrived with nowhere. A 1 here would tell
     * the upgrade path the record already exists and lose it for good.
     */
    @Test
    fun migrate3To4AddsNullableColumnsAndKeepsRows() {
        val name = "$dbName-v4"
        helper.createDatabase(name, 3).use { db ->
            db.execSQL(
                "INSERT INTO gap_interval (startEpochMs, endEpochMs, reason, precision, createdAtEpochMs) " +
                    "VALUES (10, 20, 'LISTENER_DISCONNECTED', 'EXACT', 30)",
            )
            db.execSQL(
                "INSERT INTO conversation (packageName, profileKey, identityKey, identityConfidence, pinned, archived, createdAtEpochMs, " +
                    "lastActivityEpochMs, messageCount, ambiguousCount, summaryOnlyCount) " +
                    "VALUES ('pkg', 'user:0', 'k', 'INFERRED_FROM_STREAM', 0, 0, 5, 5, 1, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO message (conversationId, isSelf, body, kind, timestampQuality, observedAtEpochMs, origin, contentStatus, dedupState, " +
                    "revisionCount, observationCount, mediaState, fingerprint, eventId, sortKey) " +
                    "VALUES (1, 0, 'body', 'TEXT', 'OBSERVED_ONLY', 5, 'LIVE', 'FULL_STRUCTURED', 'CANDIDATE', 0, 1, 'NONE', 'fp', 'e1', 5)",
            )
            db.execSQL(
                "INSERT INTO event_journal (eventId, generation, receivedAtEpochMs, expiresAtEpochMs, state, attempts, failureCode, payload, packageName) " +
                    "VALUES ('e-old', 'g', 1, 2, 'PENDING', 0, NULL, '{\"truncated\":[\"LINES\"]}', 'pkg')",
            )
        }
        val migrated = helper.runMigrationsAndValidate(name, 4, true, QuietInboxDatabase.MIGRATION_3_4)
        migrated.query("SELECT reason, startEpochMs, packageName FROM gap_interval").use { c ->
            c.moveToFirst() shouldBe true
            c.getString(0) shouldBe "LISTENER_DISCONNECTED"
            c.getLong(1) shouldBe 10L
            // A gap that predates the column is process-wide as far as anyone knows: null, not "".
            c.isNull(2) shouldBe true
        }
        migrated.query("SELECT body, fingerprint, truncationFlags FROM message").use { c ->
            c.moveToFirst() shouldBe true
            c.getString(0) shouldBe "body"
            c.getString(1) shouldBe "fp"
            c.isNull(2) shouldBe true
        }
        migrated.query("SELECT eventId, state, payload, lossRecorded FROM event_journal").use { c ->
            c.moveToFirst() shouldBe true
            c.getString(0) shouldBe "e-old"
            // Still pending, and still carrying the only evidence of what it lost.
            c.getString(1) shouldBe "PENDING"
            c.getString(2) shouldBe "{\"truncated\":[\"LINES\"]}"
            c.getInt(3) shouldBe 0
        }
        migrated.execSQL("INSERT INTO gap_interval (startEpochMs, endEpochMs, reason, precision, createdAtEpochMs, packageName) VALUES (40, NULL, 'SOURCE_PAUSED_BY_USER', 'EXACT', 40, 'pkg')")
        migrated.execSQL(
            "INSERT INTO event_journal (eventId, generation, receivedAtEpochMs, expiresAtEpochMs, state, attempts, failureCode, payload, packageName, lossRecorded) " +
                "VALUES ('e-new', 'g', 1, 2, 'PENDING', 0, NULL, '{}', 'pkg', 1)",
        )
        migrated.close()
    }

}