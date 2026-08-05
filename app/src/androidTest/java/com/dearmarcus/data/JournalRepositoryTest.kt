package com.dearmarcus.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class JournalRepositoryTest {
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
    fun saveEntry_commitsRawEntryBeforeAnInferenceObserverCanReadIt() = runBlocking {
        val entry = entry(day = 1)

        repository.saveEntry(entry)

        val observed = database.journalEntryDao().findById(entry.id)
        assertEquals(entry.id, observed?.id)
        assertEquals(entry.localDateTime.toString(), observed?.localDateTime)
        assertEquals(entry.wentWell, observed?.wentWell)
        assertEquals(entry.wentPoorly, observed?.wentPoorly)
        assertEquals(entry.doDifferently, observed?.doDifferently)
    }

    @Test
    fun threeChronologicalReflections_selectVersionThreeAsActiveMemory() = runBlocking {
        seedThreeDays()

        val active = repository.activeReflection()

        assertEquals(3, active?.memoryRevision)
        assertEquals("memory 3", active?.memoryAfter)
    }

    @Test
    fun saveEntryAndSaveReflection_requireAnExistingEntryAndIncreasingRevision() = runBlocking {
        val firstEntry = entry(day = 1)
        val firstReflection = reflection(firstEntry.id, 1)

        assertTrue(runCatching { repository.saveReflection(firstReflection) }.isFailure)
        assertNull(repository.reflection(firstEntry.id))

        repository.saveEntry(firstEntry)
        repository.saveReflection(firstReflection)

        assertTrue(
            runCatching {
                repository.saveReflection(firstReflection.copy(feedback = "replacement feedback"))
            }.isFailure,
        )
        assertEquals(firstEntry, repository.entry(firstEntry.id))
        assertEquals(firstReflection, repository.reflection(firstEntry.id))
    }

    @Test
    fun rawOnlyEarlierEntry_hidesLaterReflectionUntilChronologyIsRepaired() = runBlocking {
        val firstEntry = entry(day = 1)
        val secondEntry = entry(day = 2)
        repository.saveEntry(firstEntry)
        repository.saveEntry(secondEntry)
        repository.saveReflection(reflection(secondEntry.id, 2))

        assertNull(repository.activeReflection())
        assertEquals("", repository.activeMemory())
        assertTrue(repository.hasInvalidReflections())
        assertTrue(repository.reflection(secondEntry.id)?.isValid == true)
    }

    @Test
    fun editEarliestEntry_invalidatesThatAndEveryLaterReflection() = runBlocking {
        seedThreeDays()

        assertTrue(
            repository.editEntry(
                entry(day = 1).copy(
                    wentWell = "revised answer",
                    updatedAt = Instant.parse("2026-08-01T12:00:00Z"),
                ),
            ),
        )

        listOf("entry-1", "entry-2", "entry-3").forEach { entryId ->
            val reflection = repository.reflection(entryId)
            assertFalse(reflection?.isValid ?: true)
            assertEquals(ReflectionInvalidationReason.ENTRY_EDITED, reflection?.invalidationReason)
        }
        assertTrue(repository.hasInvalidReflections())
        assertNull(repository.activeReflection())
        assertEquals("", repository.activeMemory())
    }

    @Test
    fun deleteMiddleEntry_removesItsRowsAndInvalidatesOnlyLaterReflections() = runBlocking {
        seedThreeDays()

        assertTrue(repository.deleteEntry("entry-2"))

        assertEquals(true, repository.reflection("entry-1")?.isValid)
        assertNull(repository.entry("entry-2"))
        assertNull(repository.reflection("entry-2"))
        assertEquals(false, repository.reflection("entry-3")?.isValid)
        assertEquals(
            ReflectionInvalidationReason.ENTRY_DELETED,
            repository.reflection("entry-3")?.invalidationReason,
        )
        assertTrue(repository.hasInvalidReflections())
        assertEquals("memory 1", repository.activeMemory())
    }

    @Test
    fun clearAll_removesEntriesReflectionsAndActiveMemoryInOneRepositoryOperation() = runBlocking {
        seedThreeDays()

        repository.clearAll()

        assertTrue(repository.entries().isEmpty())
        assertNull(repository.reflection("entry-1"))
        assertNull(repository.reflection("entry-2"))
        assertNull(repository.reflection("entry-3"))
        assertFalse(repository.hasInvalidReflections())
        assertEquals("", repository.activeMemory())
    }

    @Test
    fun repeatedInterruptedTransactions_rollBackRawAndDerivedWrites() = runBlocking {
        (1..2).forEach { day ->
            val entry = entry(day)
            val reflection = reflection(entryId = entry.id, version = day)

            val result = runCatching {
                database.withTransaction {
                    database.journalEntryDao().insert(
                        JournalEntryEntity(
                            id = entry.id,
                            localDateTime = entry.localDateTime.toString(),
                            wentWell = entry.wentWell,
                            wentPoorly = entry.wentPoorly,
                            doDifferently = entry.doDifferently,
                            updatedAtEpochMillis = entry.updatedAt.toEpochMilli(),
                        ),
                    )
                    database.reflectionDao().upsert(
                        ReflectionEntity(
                            entryId = reflection.entryId,
                            feedback = reflection.feedback,
                            memoryBefore = reflection.memoryBefore,
                            memoryAfter = reflection.memoryAfter,
                            memoryRevision = reflection.memoryRevision,
                            generatedAtEpochMillis = reflection.generatedAt.toEpochMilli(),
                            aiStatus = reflection.aiStatus,
                            isValid = reflection.isValid,
                            invalidationReason = reflection.invalidationReason,
                        ),
                    )
                    error("simulated interruption")
                }
            }

            assertTrue(result.isFailure)
            assertNull(repository.entry(entry.id))
            assertNull(repository.reflection(entry.id))
        }
    }

    private suspend fun seedThreeDays() {
        (1..3).forEach { day ->
            val entry = entry(day)
            repository.saveEntry(entry)
            repository.saveReflection(reflection(entry.id, day))
        }
    }

    private fun entry(day: Int) = JournalEntryRecord(
        id = "entry-$day",
        localDateTime = LocalDateTime.of(2026, 8, day, 18, 30),
        wentWell = "well $day",
        wentPoorly = "poorly $day",
        doDifferently = "differently $day",
        updatedAt = Instant.parse("2026-08-0${day}T10:00:00Z"),
    )

    private fun reflection(entryId: String, version: Int) = ReflectionRecord(
        entryId = entryId,
        feedback = "feedback $version",
        memoryBefore = if (version == 1) "" else "memory ${version - 1}",
        memoryAfter = "memory $version",
        memoryRevision = version,
        generatedAt = Instant.parse("2026-08-0${version}T10:00:00Z"),
    )
}
