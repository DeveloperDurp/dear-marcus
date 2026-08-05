package com.dearmarcus.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dearmarcus.export.JournalBackup
import com.dearmarcus.export.JournalBackupEntry
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalBackupImportTest {
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
    fun importBackup_restoresEntriesReflectionsAndActiveMemory() = runBlocking {
        val first = entry(day = 1)
        val second = entry(day = 2)
        val backup = backup(
            JournalBackupEntry(first, reflection(first.id, 1)),
            JournalBackupEntry(second, reflection(second.id, 2)),
        )

        val summary = repository.importBackup(backup)

        assertEquals(2, summary.importedEntries)
        assertEquals(0, summary.skippedEntries)
        assertEquals(2, summary.importedReflections)
        assertEquals(0, summary.skippedReflections)
        assertEquals(listOf(first, second), repository.entries())
        assertEquals(reflection(first.id, 1), repository.reflection(first.id))
        assertEquals(reflection(second.id, 2), repository.reflection(second.id))
        assertEquals(second.id, database.reflectionDao().findByEntryId(second.id)?.entryId)
        assertEquals("memory 2", repository.activeMemory())
    }

    @Test
    fun importBackup_keepsLocalDuplicateAndSkipsItsReflection() = runBlocking {
        val duplicate = entry(day = 1).copy(wentWell = "local answer")
        val newEntry = entry(day = 2)
        repository.saveEntry(duplicate)
        val backup = backup(
            JournalBackupEntry(entry(day = 1), reflection("entry-1", 1)),
            JournalBackupEntry(newEntry, null),
        )

        val summary = repository.importBackup(backup)

        assertEquals(1, summary.importedEntries)
        assertEquals(1, summary.skippedEntries)
        assertEquals(0, summary.importedReflections)
        assertEquals(1, summary.skippedReflections)
        assertEquals(duplicate, repository.entry(duplicate.id))
        assertNull(repository.reflection(duplicate.id))
        assertEquals(newEntry, repository.entry(newEntry.id))
    }

    @Test
    fun importBackup_rejectsInvalidBackupBeforeWritingRows() = runBlocking {
        val existing = entry(day = 1)
        val invalidEntry = entry(day = 2)
        repository.saveEntry(existing)
        val invalidBackup = backup(
            JournalBackupEntry(
                invalidEntry,
                reflection(invalidEntry.id, 1).copy(memoryBefore = "unexpected memory"),
            ),
        )

        assertTrue(runCatching { repository.importBackup(invalidBackup) }.isFailure)

        assertEquals(listOf(existing), repository.entries())
        assertNull(repository.entry(invalidEntry.id))
        assertNull(repository.reflection(invalidEntry.id))
    }

    @Test
    fun importBackup_restoresReflectionChainThatContinuesLocalChronology() = runBlocking {
        val existing = entry(day = 1)
        val existingReflection = reflection(existing.id, 5).copy(
            memoryBefore = "",
            memoryAfter = "memory 5",
        )
        val imported = entry(day = 2)
        val importedReflection = reflection(imported.id, 6).copy(
            memoryBefore = "memory 5",
            memoryAfter = "memory 6",
        )
        repository.saveEntry(existing)
        repository.saveReflection(existingReflection)
        val backup = backup(
            JournalBackupEntry(existing, existingReflection),
            JournalBackupEntry(imported, importedReflection),
        )

        val summary = repository.importBackup(backup)

        assertEquals(1, summary.importedEntries)
        assertEquals(1, summary.skippedEntries)
        assertEquals(1, summary.importedReflections)
        assertEquals(1, summary.skippedReflections)
        assertEquals(importedReflection, repository.reflection(imported.id))
        assertEquals("memory 6", repository.activeMemory())
    }

    @Test
    fun importBackup_skipsReflectionThatDoesNotFollowLocalChronology() = runBlocking {
        // Given
        val existing = entry(day = 1)
        val imported = entry(day = 2)
        repository.saveEntry(existing)
        repository.saveReflection(reflection(existing.id, 1).copy(memoryAfter = "local memory"))

        // When
        val summary = repository.importBackup(backup(JournalBackupEntry(imported, reflection(imported.id, 1))))

        // Then
        assertEquals(1, summary.importedEntries)
        assertEquals(0, summary.importedReflections)
        assertEquals(1, summary.skippedReflections)
        assertEquals(listOf(existing, imported), repository.entries())
        assertEquals(imported, repository.entry(imported.id))
        assertNull(repository.reflection(imported.id))
        assertEquals("local memory", repository.activeMemory())
    }

    @Test
    fun importBackup_importsOlderRawEntryInvalidatesLaterReflectionAsAnUnresolvedPredecessor() = runBlocking {
        // Given
        val existing = entry(day = 2)
        val imported = entry(day = 1)
        repository.saveEntry(existing)
        repository.saveReflection(reflection(existing.id, 1).copy(memoryAfter = "local memory"))

        // When
        val summary = repository.importBackup(backup(JournalBackupEntry(imported, null)))

        // Then
        assertEquals(1, summary.importedEntries)
        assertEquals(0, summary.importedReflections)
        assertEquals(listOf(imported, existing), repository.entries())
        assertEquals(imported, repository.entry(imported.id))
        assertNull(repository.activeReflection())
        assertEquals("", repository.activeMemory())
        assertTrue(repository.hasInvalidReflections())
        assertFalse(requireNotNull(repository.reflection(existing.id)).isValid)
        assertEquals(
            ReflectionInvalidationReason.PREDECESSOR_UNRESOLVED,
            requireNotNull(repository.reflection(existing.id)).invalidationReason,
        )
    }

    @Test
    fun importBackup_importsOlderRawEntryButSkipsItsIncompatibleReflection() = runBlocking {
        // Given
        val existing = entry(day = 2)
        val imported = entry(day = 1)
        repository.saveEntry(existing)
        repository.saveReflection(reflection(existing.id, 1).copy(memoryAfter = "local memory"))

        // When
        val summary = repository.importBackup(backup(JournalBackupEntry(imported, reflection(imported.id, 1))))

        // Then
        assertEquals(1, summary.importedEntries)
        assertEquals(0, summary.importedReflections)
        assertEquals(1, summary.skippedReflections)
        assertEquals(imported, repository.entry(imported.id))
        assertNull(repository.reflection(imported.id))
        assertEquals("", repository.activeMemory())
    }

    @Test
    fun importBackup_emptyBackupReturnsAnEmptySummary() = runBlocking {
        val summary = repository.importBackup(backup())

        assertEquals(0, summary.importedEntries)
        assertEquals(0, summary.skippedEntries)
        assertEquals(0, summary.importedReflections)
        assertEquals(0, summary.skippedReflections)
        assertTrue(repository.entries().isEmpty())
    }

    @Test
    fun importBackup_repeatedBackupSkipsExistingRows() = runBlocking {
        val imported = entry(day = 1)
        val backup = backup(JournalBackupEntry(imported, reflection(imported.id, 1)))

        repository.importBackup(backup)
        val summary = repository.importBackup(backup)

        assertEquals(0, summary.importedEntries)
        assertEquals(1, summary.skippedEntries)
        assertEquals(0, summary.importedReflections)
        assertEquals(1, summary.skippedReflections)
        assertEquals(listOf(imported), repository.entries())
        assertEquals("memory 1", repository.activeMemory())
    }

    @Test
    fun importBackup_rollsBackEntriesWhenReflectionWriteFails() = runBlocking {
        val imported = entry(day = 1)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_reflection_insert
            BEFORE INSERT ON reflections
            BEGIN
                SELECT RAISE(ABORT, 'forced reflection failure');
            END
            """.trimIndent(),
        )

        assertTrue(
            runCatching {
                repository.importBackup(backup(JournalBackupEntry(imported, reflection(imported.id, 1))))
            }.isFailure,
        )

        assertNull(repository.entry(imported.id))
        assertNull(repository.reflection(imported.id))
    }

    private fun backup(vararg entries: JournalBackupEntry) = JournalBackup(
        exportedAt = Instant.parse("2026-08-04T00:00:00Z"),
        entries = entries.toList(),
    )

    private fun entry(day: Int) = JournalEntryRecord(
        id = "entry-$day",
        localDateTime = LocalDateTime.of(2026, 8, day, 18, 30),
        wentWell = "well $day",
        wentPoorly = "poorly $day",
        doDifferently = "differently $day",
        updatedAt = Instant.parse("2026-08-0${day}T18:30:00Z"),
    )

    private fun reflection(entryId: String, revision: Int) = ReflectionRecord(
        entryId = entryId,
        feedback = "feedback $revision",
        memoryBefore = if (revision == 1) "" else "memory ${revision - 1}",
        memoryAfter = "memory $revision",
        memoryRevision = revision,
        generatedAt = Instant.parse("2026-08-0${revision.coerceAtMost(9)}T19:00:00Z"),
    )
}
