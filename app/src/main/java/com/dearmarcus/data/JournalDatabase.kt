package com.dearmarcus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [JournalEntryEntity::class, ReflectionEntity::class],
    version = JournalDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(ReflectionInvalidationReasonConverter::class)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun journalEntryDao(): JournalEntryDao

    abstract fun reflectionDao(): ReflectionDao

    companion object {
        const val VERSION = 3
        const val DATABASE_NAME = "dear-marcus.db"

        fun create(context: Context): JournalDatabase = Room.databaseBuilder(
            context.applicationContext,
            JournalDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(*JournalMigrations.ALL).build()
    }
}

object JournalMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE `reflections` (
                    `entry_id` TEXT NOT NULL,
                    `feedback` TEXT NOT NULL,
                    `memory_before` TEXT NOT NULL,
                    `memory_after` TEXT NOT NULL,
                    `memory_revision` INTEGER NOT NULL,
                    `generated_at_epoch_millis` INTEGER NOT NULL,
                    `ai_status` TEXT NOT NULL,
                    `is_valid` INTEGER NOT NULL,
                    PRIMARY KEY(`entry_id`),
                    FOREIGN KEY(`entry_id`) REFERENCES `journal_entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `reflections` ADD COLUMN `invalidation_reason` TEXT")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
