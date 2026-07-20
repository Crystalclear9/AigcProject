package com.suishouban.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ActionCardEntity::class, NotificationCandidateEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cardDao(): ActionCardDao
    abstract fun notificationCandidateDao(): NotificationCandidateDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "suishouban.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN action_id TEXT")
                db.execSQL("ALTER TABLE cards ADD COLUMN dependencies TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE cards ADD COLUMN evidence_summary TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** Adds a separate local-only queue; existing cards remain untouched. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notification_candidates (
                        id TEXT NOT NULL,
                        notification_key TEXT NOT NULL,
                        package_name TEXT NOT NULL,
                        app_label TEXT NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        posted_at_millis INTEGER NOT NULL,
                        content_hash TEXT NOT NULL,
                        expires_at_millis INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_notification_candidates_content_hash " +
                        "ON notification_candidates(content_hash)",
                )
            }
        }
    }
}
