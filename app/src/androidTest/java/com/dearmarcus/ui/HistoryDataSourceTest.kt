package com.dearmarcus.ui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dearmarcus.data.JournalDatabase
import com.dearmarcus.data.JournalEntryRecord
import com.dearmarcus.data.JournalRepository
import com.dearmarcus.data.ReflectionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class HistoryDataSourceTest {
    private lateinit var database: JournalDatabase
    private lateinit var repository: JournalRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            JournalDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = JournalRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun seededEntriesAreReturnedReverseChronologicallyWithTheirDatedFeedback() = runBlocking {
        seedThreeDays()
        val source = source()

        val history = source.entriesNewestFirst()

        assertEquals(listOf("entry-3", "entry-2", "entry-1"), history.map { it.entry.id })
        assertEquals("feedback 3", history.first().reflection?.feedback)
        assertEquals(Instant.parse("2026-08-03T20:00:00Z"), history.first().reflection?.generatedAt)
    }

    @Test
    fun editDayOneUsesRepositoryInvalidationSoLaterRowsAreStale() = runBlocking {
        seedThreeDays()

        assertEquals(
            true,
            source().edit("entry-1", HistoryAnswers("changed", "poorly 1", "differently 1")),
        )

        assertFalse(source().entriesNewestFirst().all { it.reflection?.isValid == true })
        assertEquals(listOf(false, false, false), source().entriesNewestFirst().reversed().map { it.reflection?.isValid })
    }

    @Test
    fun clearAllRemovesEntriesAndReflectionsBeforeClearingTheRemainingLocalData() = runBlocking {
        seedThreeDays()
        var localDataWasCleared = false

        source { localDataWasCleared = true }.clearAll()

        assertTrue(localDataWasCleared)
        assertTrue(repository.entries().isEmpty())
        assertNull(repository.reflection("entry-1"))
        assertNull(repository.reflection("entry-2"))
        assertNull(repository.reflection("entry-3"))
        assertEquals("", repository.activeMemory())
    }

    @Test
    fun clearAllPropagatesCleanerCancellationInsteadOfReportingAnIncompleteClear() = runBlocking {
        seedThreeDays()

        val result = runCatching {
            source { throw CancellationException("test cancellation") }.clearAll()
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(repository.entries().isEmpty())
        assertNull(repository.reflection("entry-1"))
        assertEquals("", repository.activeMemory())
    }

    private fun source(localDataCleaner: LocalDataCleaner = {}) = RepositoryHistoryDataSource(
        repository = repository,
        clock = { Instant.parse("2026-08-04T00:00:00Z") },
        localDataCleaner = localDataCleaner,
    )

    private suspend fun seedThreeDays() {
        (1..3).forEach { day ->
            val entry = JournalEntryRecord(
                id = "entry-$day",
                localDateTime = LocalDateTime.of(2026, 8, day, 18, 30),
                wentWell = "well $day",
                wentPoorly = "poorly $day",
                doDifferently = "differently $day",
                updatedAt = Instant.parse("2026-08-0${day}T18:30:00Z"),
            )
            repository.saveEntry(entry)
            repository.saveReflection(
                ReflectionRecord(
                    entryId = entry.id,
                    feedback = "feedback $day",
                    memoryBefore = "",
                    memoryAfter = "memory $day",
                    memoryRevision = day,
                    generatedAt = Instant.parse("2026-08-0${day}T20:00:00Z"),
                ),
            )
        }
    }
}
