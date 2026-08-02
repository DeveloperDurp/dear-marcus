package com.dearmarcus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class JournalDatabaseMigrationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun versionOneDatabase_migratesThroughVersionsTwoAndThreeWithoutLosingRawEntries() = runBlocking {
        val databaseName = "journal-migration-${UUID.randomUUID()}.db"
        createVersionOneDatabase(databaseName)

        val database = Room.databaseBuilder(context, JournalDatabase::class.java, databaseName)
            .addMigrations(*JournalMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(3, database.openHelper.writableDatabase.version)
            assertEquals("entry-1", database.journalEntryDao().findById("entry-1")?.id)

            database.reflectionDao().upsert(
                ReflectionEntity(
                    entryId = "entry-1",
                    feedback = "feedback",
                    memoryBefore = "",
                    memoryAfter = "memory",
                    memoryRevision = 1,
                    generatedAtEpochMillis = 1_000,
                    aiStatus = "AVAILABLE",
                    isValid = true,
                    invalidationReason = null,
                ),
            )
            database.reflectionDao().invalidateAtOrAfter(
                localDateTime = "2026-08-01T18:30",
                entryId = "entry-1",
                reason = ReflectionInvalidationReason.ENTRY_EDITED,
            )

            val migratedReflection = database.reflectionDao().findByEntryId("entry-1")
            assertEquals(false, migratedReflection?.isValid)
            assertEquals(ReflectionInvalidationReason.ENTRY_EDITED, migratedReflection?.invalidationReason)
            assertNull(database.reflectionDao().latestValid())
            assertTrue(database.journalEntryDao().entriesOldestFirst().isNotEmpty())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun createVersionOneDatabase(databaseName: String) {
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, VersionOneJournalDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            database.journalEntryDao().insert(
                JournalEntryEntity(
                    id = "entry-1",
                    localDateTime = "2026-08-01T18:30",
                    wentWell = "well",
                    wentPoorly = "poorly",
                    doDifferently = "differently",
                    updatedAtEpochMillis = 1_000,
                ),
            )
        } finally {
            database.close()
        }
    }
}

@Database(entities = [JournalEntryEntity::class], version = 1, exportSchema = false)
abstract class VersionOneJournalDatabase : RoomDatabase() {
    abstract fun journalEntryDao(): JournalEntryDao
}
