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
