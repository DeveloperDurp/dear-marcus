package com.dearmarcus.export

import com.dearmarcus.data.JournalRepository
import com.dearmarcus.data.JournalEntryRecord
import java.time.Instant

class RepositoryJournalBackupExport(
    private val repository: JournalRepository,
    private val codec: JournalBackupCodec,
    private val clock: () -> Instant,
) {
    suspend fun createDocument(): JournalBackupDocument {
        val snapshot = repository.exportSnapshot()
        val entries = snapshot.entries.sortedWith(
            compareBy<JournalEntryRecord> { it.localDateTime }.thenBy { it.id },
        )
        val activeReflectionIndex = snapshot.activeReflection
            ?.entryId
            ?.let { activeEntryId -> entries.indexOfFirst { it.id == activeEntryId } }
            ?: -1
        var validPrefix = activeReflectionIndex >= 0
        var chronology = JournalBackupChronology()

        return codec.encode(
            JournalBackup(
                exportedAt = clock(),
                entries = entries.mapIndexed { index, entry ->
                    val reflection = snapshot.reflectionsByEntryId[entry.id]
                    val backupReflection = reflection?.takeIf {
                        validPrefix &&
                            index <= activeReflectionIndex &&
                            JournalBackupContract.reflectionIsValidForExport(
                                reflection = it,
                                entryId = entry.id,
                                chronology = chronology,
                            )
                    }
                    if (backupReflection == null) {
                        validPrefix = false
                    } else {
                        chronology = chronology.advancedBy(backupReflection)
                    }
                    JournalBackupEntry(entry = entry, reflection = backupReflection)
                },
            ),
        )
    }
}
