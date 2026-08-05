package com.dearmarcus.export

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dearmarcus.data.JournalDatabase
import com.dearmarcus.data.JournalEntryRecord
import com.dearmarcus.data.JournalRepository
import com.dearmarcus.data.ReflectionRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class RepositoryJournalBackupExportTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val repository = JournalRepository(database)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun staleReflectionIsExcludedAndExportLeavesPersistedRowsUnchanged() = runBlocking {
        val original = JournalEntryRecord(
            id = "entry-1",
            localDateTime = LocalDateTime.of(2026, 8, 1, 18, 30),
            wentWell = "I listened.",
            wentPoorly = "I rushed.",
            doDifferently = "I will pause.",
            updatedAt = Instant.parse("2026-08-01T18:30:00Z"),
        )
        repository.saveEntry(original)
        repository.saveReflection(
            ReflectionRecord(
                entryId = original.id,
                feedback = "This must not be exported after the edit.",
                memoryBefore = "",
                memoryAfter = "This invalid memory must not be current.",
                memoryRevision = 1,
                generatedAt = Instant.parse("2026-08-01T19:00:00Z"),
            ),
        )
        repository.editEntry(
            original.copy(
                wentWell = "I listened after checking the facts.",
                updatedAt = Instant.parse("2026-08-01T20:00:00Z"),
            ),
        )
        val entriesBefore = repository.entries()
        val reflectionBefore = repository.reflection(original.id)

        val document = RepositoryJournalBackupExport(
            repository = repository,
            codec = JournalBackupCodec(),
            clock = { Instant.parse("2026-08-02T00:00:00Z") },
        ).createDocument()
        val decoded = JournalBackupCodec().decode(document.content)

        assertEquals(entriesBefore, repository.entries())
        assertEquals(reflectionBefore, repository.reflection(original.id))
        assertEquals("DearMarcus-backup-2026-08-02.json", document.fileName)
        assertEquals(JournalBackupDocument.MIME_TYPE, document.mimeType)
        assertTrue(decoded is JournalBackupDecodeResult.Success)
        val backup = (decoded as JournalBackupDecodeResult.Success).backup
        assertEquals("I listened after checking the facts.", backup.entries.single().entry.wentWell)
        assertNull(backup.entries.single().reflection)
        assertEquals("", backup.activeMemory)
        assertFalse(document.content.contains("This must not be exported after the edit."))
        assertFalse(document.content.contains("This invalid memory must not be current."))
    }

    @Test
    fun exportSnapshotRetainsStaleRowsAndSeparatelyDerivesCurrentValidReflection() = runBlocking {
        val first = JournalEntryRecord(
            id = "entry-1",
            localDateTime = LocalDateTime.of(2026, 8, 1, 18, 30),
            wentWell = "well 1",
            wentPoorly = "poorly 1",
            doDifferently = "differently 1",
            updatedAt = Instant.parse("2026-08-01T18:30:00Z"),
        )
        val second = first.copy(
            id = "entry-2",
            localDateTime = LocalDateTime.of(2026, 8, 2, 18, 30),
            updatedAt = Instant.parse("2026-08-02T18:30:00Z"),
        )
        val third = first.copy(
            id = "entry-3",
            localDateTime = LocalDateTime.of(2026, 8, 3, 18, 30),
            updatedAt = Instant.parse("2026-08-03T18:30:00Z"),
        )
        for (entry in listOf(first, second, third)) {
            repository.saveEntry(entry)
        }
        for ((index, entry) in listOf(first, second, third).withIndex()) {
            repository.saveReflection(
                ReflectionRecord(
                    entryId = entry.id,
                    feedback = "feedback ${index + 1}",
                    memoryBefore = if (index == 0) "" else "memory $index",
                    memoryAfter = "memory ${index + 1}",
                    memoryRevision = index + 1,
                    generatedAt = Instant.parse("2026-08-0${index + 1}T19:00:00Z"),
                ),
            )
        }

        repository.editEntry(second.copy(wentWell = "revised", updatedAt = Instant.parse("2026-08-04T00:00:00Z")))

        val snapshot = repository.exportSnapshot()

        assertEquals(listOf(first, second.copy(wentWell = "revised", updatedAt = Instant.parse("2026-08-04T00:00:00Z")), third), snapshot.entries)
        assertEquals(true, snapshot.reflectionsByEntryId.getValue(first.id).isValid)
        assertFalse(snapshot.reflectionsByEntryId.getValue(second.id).isValid)
        assertFalse(snapshot.reflectionsByEntryId.getValue(third.id).isValid)
        assertEquals(first.id, snapshot.activeReflection?.entryId)
        assertEquals("memory 1", snapshot.activeReflection?.memoryAfter)
    }
}
