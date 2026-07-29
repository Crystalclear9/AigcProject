package com.suishouban.app.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun deleteTestDatabase() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrationTwoToThreePreservesCardsAndCreatesCandidateTableAndIndex() {
        openAtVersion(
            version = 2,
            onCreate = { db ->
                // Only columns relevant to this migration are needed: migration 2 -> 3 must not
                // rewrite the existing cards table.
                db.execSQL("CREATE TABLE cards (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)")
                db.execSQL("INSERT INTO cards (id, title) VALUES ('card-1', '保留事项')")
            },
        ).close()

        val migrated = openAtVersion(
            version = 3,
            onCreate = { error("version 2 database should already exist") },
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(2, oldVersion)
                assertEquals(3, newVersion)
                AppDatabase.MIGRATION_2_3.migrate(db)
            },
        )

        migrated.writableDatabase.query("SELECT title FROM cards WHERE id = 'card-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("保留事项", cursor.getString(0))
        }
        migrated.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'notification_candidates'",
        ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        migrated.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_notification_candidates_content_hash'",
        ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        migrated.close()
    }

    @Test
    fun migrationThreeToFourCreatesRefinementAndProfileTables() {
        openAtVersion(
            version = 3,
            onCreate = { db ->
                db.execSQL("CREATE TABLE cards (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO cards (id) VALUES ('card-1')")
            },
        ).close()

        val migrated = openAtVersion(
            version = 4,
            onCreate = { error("version 3 database should already exist") },
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(3, oldVersion)
                assertEquals(4, newVersion)
                AppDatabase.MIGRATION_3_4.migrate(db)
            },
        )

        listOf(
            "action_plans",
            "plan_items",
            "card_attachments",
            "card_refinement_preferences",
            "user_profiles",
            "profile_signal_stats",
        ).forEach { table ->
            migrated.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'",
            ).use { cursor -> assertTrue("$table should exist", cursor.moveToFirst()) }
        }
        migrated.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'index_action_plans_parent_card_id'",
        ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        migrated.close()
    }

    @Test
    fun migrationFourToFiveAddsProfileAndPlanQualityColumns() {
        openAtVersion(
            version = 4,
            onCreate = { db ->
                db.execSQL("CREATE TABLE user_profiles (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("CREATE TABLE action_plans (id TEXT NOT NULL PRIMARY KEY)")
            },
        ).close()

        val migrated = openAtVersion(
            version = 5,
            onCreate = { error("version 4 database should already exist") },
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(4, oldVersion)
                assertEquals(5, newVersion)
                AppDatabase.MIGRATION_4_5.migrate(db)
            },
        )

        val profileColumns = tableColumns(migrated.writableDatabase, "user_profiles")
        val planColumns = tableColumns(migrated.writableDatabase, "action_plans")
        assertTrue(profileColumns.containsAll(listOf(
            "buffer_preference",
            "weekend_policy",
            "assistant_tone",
            "questionnaire_version",
        )))
        assertTrue(planColumns.containsAll(listOf(
            "quality_score",
            "constraint_errors",
            "profile_effects",
            "verification_summary",
        )))
        migrated.close()
    }

    @Test
    fun migrationFiveToSixAddsPriorityWorkspaceAndIntakeSchemaWithoutLosingCards() {
        openAtVersion(
            version = 5,
            onCreate = { db ->
                db.execSQL("CREATE TABLE cards (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)")
                db.execSQL("INSERT INTO cards (id, title) VALUES ('card-1', '提交实验报告')")
            },
        ).close()

        val migrated = openAtVersion(
            version = 6,
            onCreate = { error("version 5 database should already exist") },
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(5, oldVersion)
                assertEquals(6, newVersion)
                AppDatabase.MIGRATION_5_6.migrate(db)
            },
        )

        val cardColumns = tableColumns(migrated.writableDatabase, "cards")
        assertTrue(cardColumns.containsAll(listOf(
            "priority_mode",
            "priority_score",
            "priority_reason",
            "priority_updated_at",
            "priority_locked",
            "workspace_type",
            "workspace_id",
            "assignee_id",
            "participant_ids",
            "deliverables",
            "source_session_id",
        )))
        listOf(
            "team_workspaces",
            "team_members",
            "team_assignments",
            "intake_sessions",
        ).forEach { table ->
            migrated.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'",
            ).use { cursor -> assertTrue("$table should exist", cursor.moveToFirst()) }
        }
        migrated.writableDatabase.query("SELECT title FROM cards WHERE id = 'card-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("提交实验报告", cursor.getString(0))
        }
        migrated.close()
    }

    @Test
    fun migrationSixToSevenAddsStructuredReminderNodesWithoutLosingCards() {
        openAtVersion(
            version = 6,
            onCreate = { db ->
                db.execSQL(
                    "CREATE TABLE cards (" +
                        "id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                        "reminders TEXT NOT NULL DEFAULT '[]')"
                )
                db.execSQL(
                    "INSERT INTO cards (id, title, reminders) " +
                        "VALUES ('card-1', '提交实验报告', '[\"截止前30分钟\"]')"
                )
            },
        ).close()

        val migrated = openAtVersion(
            version = 7,
            onCreate = { error("version 6 database should already exist") },
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(6, oldVersion)
                assertEquals(7, newVersion)
                AppDatabase.MIGRATION_6_7.migrate(db)
            },
        )

        assertTrue(tableColumns(migrated.writableDatabase, "cards").contains("reminder_nodes"))
        migrated.writableDatabase.query(
            "SELECT title, reminders, reminder_nodes FROM cards WHERE id = 'card-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("提交实验报告", cursor.getString(0))
            assertEquals("[\"截止前30分钟\"]", cursor.getString(1))
            assertEquals("[]", cursor.getString(2))
        }
        migrated.close()
    }

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun openAtVersion(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit,
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> },
    ): SupportSQLiteOpenHelper {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DATABASE)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        onUpgrade(db, oldVersion, newVersion)
                },
            )
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).also {
            // Opening writableDatabase is what runs onCreate/onUpgrade.
            it.writableDatabase
        }
    }

    private companion object {
        const val TEST_DATABASE = "mofei-migration-test.db"
    }
}
