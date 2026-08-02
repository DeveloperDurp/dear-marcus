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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class RepositoryJournalMarkdownExportTest {
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
    fun staleReflectionIsLabelledAndExportLeavesPersistedRowsUnchanged() = runBlocking {
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

        val document = RepositoryJournalMarkdownExport(
            repository = repository,
            exporter = JournalMarkdownExporter(),
            clock = { Instant.parse("2026-08-02T00:00:00Z") },
            zoneId = ZoneOffset.UTC,
        ).createDocument()

        assertEquals(entriesBefore, repository.entries())
        assertEquals(reflectionBefore, repository.reflection(original.id))
        assertEquals("DearMarcus-2026-08-02.md", document.fileName)
        assertTrue(document.markdown.contains("Unavailable or stale"))
        assertTrue(document.markdown.contains("No valid condensed memory yet"))
        assertTrue(document.markdown.contains("I listened after checking the facts."))
        assertFalse(document.markdown.contains("This must not be exported after the edit."))
        assertFalse(document.markdown.contains("This invalid memory must not be current."))
    }
}
