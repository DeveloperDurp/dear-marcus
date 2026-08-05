package com.dearmarcus.export

import com.dearmarcus.data.JournalEntryRecord
import com.dearmarcus.data.ReflectionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class JournalBackupCodecTest {
    private val codec = JournalBackupCodec()

    @Test
    fun encodesDeterministicUtf8JsonAndDecodesTheCompleteValidChronology() {
        val backup = backup()

        val document = codec.encode(backup)
        val decoded = codec.decode(document.content)

        assertEquals("DearMarcus-backup-2026-08-01.json", document.fileName)
        assertEquals(JournalBackupDocument.MIME_TYPE, document.mimeType)
        assertEquals(
            """{"format":"dear-marcus.local-backup","version":1,"exportedAtEpochMillis":1785587400000,"entries":[{"id":"earlier","localDateTime":"2026-07-31T09:15","wentWell":"I arrived early.","wentPoorly":"I checked my phone.","doDifferently":"I will leave it away.","updatedAtEpochMillis":1785490500000,"reflection":{"entryId":"earlier","feedback":"Protect attention.","memoryBefore":"","memoryAfter":"Protect attention.","memoryRevision":1,"generatedAtEpochMillis":1785493200000,"aiStatus":"AVAILABLE"}},{"id":"later","localDateTime":"2026-08-01T18:30","wentWell":"I listened.\nWith quotes: \"yes\".","wentPoorly":"I rushed.","doDifferently":"I will pause.","updatedAtEpochMillis":1785609000000,"reflection":{"entryId":"later","feedback":"Notice the first impulse.","memoryBefore":"Protect attention.","memoryAfter":"Pause before replying.","memoryRevision":2,"generatedAtEpochMillis":1785610800000,"aiStatus":"AVAILABLE"}}]}""",
            document.content,
        )
        assertEquals(document.content, codec.encode(backup.copy(entries = backup.entries.reversed())).content)
        assertTrue(decoded is JournalBackupDecodeResult.Success)
        assertEquals(backup, (decoded as JournalBackupDecodeResult.Success).backup)
        assertEquals("later", (decoded.backup.activeReflection ?: error("Expected active reflection")).entryId)
        assertEquals("Pause before replying.", decoded.backup.activeMemory)
    }

    private fun backup() = JournalBackup(
        exportedAt = Instant.parse("2026-08-01T12:30:00Z"),
        entries = listOf(
            JournalBackupEntry(
                entry = JournalEntryRecord(
                    id = "earlier",
                    localDateTime = LocalDateTime.of(2026, 7, 31, 9, 15),
                    wentWell = "I arrived early.",
                    wentPoorly = "I checked my phone.",
                    doDifferently = "I will leave it away.",
                    updatedAt = Instant.parse("2026-07-31T09:35:00Z"),
                ),
                reflection = ReflectionRecord(
                    entryId = "earlier",
                    feedback = "Protect attention.",
                    memoryBefore = "",
                    memoryAfter = "Protect attention.",
                    memoryRevision = 1,
                    generatedAt = Instant.parse("2026-07-31T10:20:00Z"),
                ),
            ),
            JournalBackupEntry(
                entry = JournalEntryRecord(
                    id = "later",
                    localDateTime = LocalDateTime.of(2026, 8, 1, 18, 30),
                    wentWell = "I listened.\nWith quotes: \"yes\".",
                    wentPoorly = "I rushed.",
                    doDifferently = "I will pause.",
                    updatedAt = Instant.parse("2026-08-01T18:30:00Z"),
                ),
                reflection = ReflectionRecord(
                    entryId = "later",
                    feedback = "Notice the first impulse.",
                    memoryBefore = "Protect attention.",
                    memoryAfter = "Pause before replying.",
                    memoryRevision = 2,
                    generatedAt = Instant.parse("2026-08-01T19:00:00Z"),
                ),
            ),
        ),
    )
}
