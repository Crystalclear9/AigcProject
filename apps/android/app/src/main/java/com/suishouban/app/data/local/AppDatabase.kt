package com.suishouban.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ActionCardEntity::class], version = 2, exportSchema = false)
@TypeConverters(StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cardDao(): ActionCardDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "suishouban.db"
            )
                .addMigrations(MIGRATION_1_2)
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
    }
}
