package com.dearmarcus.data

import androidx.room.withTransaction
import com.dearmarcus.export.JournalBackup
import com.dearmarcus.export.JournalBackupContract

class JournalRepository(
    private val database: JournalDatabase,
) {
    private val entries = database.journalEntryDao()
    private val reflections = database.reflectionDao()

    suspend fun saveEntry(entry: JournalEntryRecord): JournalEntryRecord = database.withTransaction {
        entries.insert(entry.toEntity())
        entry
    }

    suspend fun entry(id: String): JournalEntryRecord? = entries.findById(id)?.toRecord()

    suspend fun entries(): List<JournalEntryRecord> = entries.entriesOldestFirst().map { it.toRecord() }

    suspend fun exportSnapshot(): JournalExportSnapshot = database.withTransaction {
        val records = entries.entriesOldestFirst().map { it.toRecord() }
        JournalExportSnapshot(
            entries = records,
            reflectionsByEntryId = records.mapNotNull { entry ->
                reflections.findByEntryId(entry.id)?.toRecord()?.let { reflection ->
                    entry.id to reflection
                }
            }.toMap(),
            activeReflection = reflections.latestValid()?.toRecord(),
        )
    }

    suspend fun importBackup(backup: JournalBackup): JournalBackupImportSummary {
        require(JournalBackupContract.validationFailure(backup, requireCanonicalOrder = true) == null) {
            "Journal backup must contain a valid reflection chronology."
        }

        return database.withTransaction {
            val localEntries = entries.entriesOldestFirst().map { it.toRecord() }
            val localIds = localEntries.mapTo(mutableSetOf()) { it.id }
            val importedEntries = backup.entries.filter { it.entry.id !in localIds }
            val reflectionEntries = importedEntries.filter { it.reflection != null }
            val candidateReflections = reflectionEntries.mapNotNull { it.reflection }
            val lastLocalEntry = localEntries.lastOrNull()
            val importedReflections = if (
                candidateReflections.isNotEmpty() &&
                importedEntries.all { it.reflection != null } &&
                reflectionEntries.all { backupEntry ->
                    lastLocalEntry == null || backupEntry.entry.isAfter(lastLocalEntry)
                } &&
                reflections.entriesNeedingReflectionCount() == 0
            ) {
                val activeMemory = reflections.latestValid()?.memoryAfter.orEmpty()
                val highestValidRevision = reflections.highestValidMemoryRevision() ?: 0
                if (
                    candidateReflections.first().memoryBefore == activeMemory &&
                    candidateReflections.all { it.memoryRevision > highestValidRevision }
                ) {
                    candidateReflections
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }

            importedEntries.forEach { entries.insert(it.entry.toEntity()) }
            importedEntries
                .filter { importedEntry -> importedReflections.none { it.entryId == importedEntry.entry.id } }
                .forEach { importedEntry ->
                    reflections.invalidateAtOrAfterForUnresolvedPredecessor(
                        localDateTime = importedEntry.entry.localDateTime.toString(),
                        entryId = importedEntry.entry.id,
                    )
                }
            importedReflections.forEach { reflections.upsert(it.toEntity()) }

            JournalBackupImportSummary(
                importedEntries = importedEntries.size,
                skippedEntries = backup.entries.size - importedEntries.size,
                importedReflections = importedReflections.size,
                skippedReflections = backup.entries.count { it.entry.id in localIds && it.reflection != null } +
                    candidateReflections.size - importedReflections.size,
            )
        }
    }

    suspend fun saveReflection(reflection: ReflectionRecord): ReflectionRecord = database.withTransaction {
        require(reflection.isValid) { "Only successful reflections can be saved." }
        require(reflection.invalidationReason == null) { "A valid reflection cannot be invalidated." }
        check(entries.findById(reflection.entryId) != null) { "Reflection entry does not exist." }
        val highestRevision = reflections.highestMemoryRevision() ?: 0
        require(reflection.memoryRevision > highestRevision) {
            "Memory revisions must increase monotonically."
        }
        reflections.upsert(reflection.toEntity())
        reflection
    }

    suspend fun saveReflectionIfEntryUnchanged(
        entry: JournalEntryRecord,
        reflection: ReflectionRecord,
    ): Boolean = database.withTransaction {
        val currentEntry = entries.findById(entry.id) ?: return@withTransaction false
        if (currentEntry.toRecord() != entry) return@withTransaction false

        val currentReflection = reflections.findByEntryId(reflection.entryId)
        if (currentReflection?.isValid == true) return@withTransaction false

        require(reflection.isValid) { "Only successful reflections can be saved." }
        require(reflection.invalidationReason == null) { "A valid reflection cannot be invalidated." }
        val highestRevision = reflections.highestMemoryRevision() ?: 0
        require(reflection.memoryRevision > highestRevision) {
            "Memory revisions must increase monotonically."
        }
        reflections.upsert(reflection.toEntity())
        true
    }

    suspend fun reflection(entryId: String): ReflectionRecord? =
        reflections.findByEntryId(entryId)?.toRecord()

    suspend fun activeReflection(): ReflectionRecord? = reflections.latestValid()?.toRecord()

    suspend fun latestValidReflectionBefore(entry: JournalEntryRecord): ReflectionRecord? =
        reflections.latestValidBefore(entry.localDateTime.toString(), entry.id)?.toRecord()

    suspend fun highestMemoryRevision(): Int = reflections.highestMemoryRevision() ?: 0

    suspend fun activeMemory(): String = activeReflection()?.memoryAfter.orEmpty()

    suspend fun hasInvalidReflections(): Boolean = reflections.entriesNeedingReflectionCount() > 0

    suspend fun invalidateReflectionsAtOrAfter(entry: JournalEntryRecord) = database.withTransaction {
        reflections.invalidateAtOrAfterForUnresolvedPredecessor(
            localDateTime = entry.localDateTime.toString(),
            entryId = entry.id,
        )
    }

    suspend fun editEntry(entry: JournalEntryRecord): Boolean = database.withTransaction {
        val current = entries.findById(entry.id) ?: return@withTransaction false
        require(current.localDateTime == entry.localDateTime.toString()) {
            "Entry time cannot change during an answer edit."
        }
        if (
            entries.updateAnswers(
                id = entry.id,
                wentWell = entry.wentWell,
                wentPoorly = entry.wentPoorly,
                doDifferently = entry.doDifferently,
                updatedAtEpochMillis = entry.updatedAt.toEpochMilli(),
            ) == 0
        ) {
            return@withTransaction false
        }
        reflections.invalidateAtOrAfter(
            localDateTime = current.localDateTime,
            entryId = current.id,
            reason = ReflectionInvalidationReason.ENTRY_EDITED,
        )
        true
    }

    suspend fun deleteEntry(id: String): Boolean = database.withTransaction {
        val current = entries.findById(id) ?: return@withTransaction false
        reflections.deleteByEntryId(id)
        if (entries.deleteById(id) == 0) return@withTransaction false
        reflections.invalidateAfter(
            localDateTime = current.localDateTime,
            entryId = current.id,
            reason = ReflectionInvalidationReason.ENTRY_DELETED,
        )
        true
    }

    suspend fun clearAll() = database.withTransaction {
        reflections.deleteAll()
        entries.deleteAll()
    }
}

data class JournalExportSnapshot(
    val entries: List<JournalEntryRecord>,
    val reflectionsByEntryId: Map<String, ReflectionRecord>,
    val activeReflection: ReflectionRecord?,
)

data class JournalBackupImportSummary(
    val importedEntries: Int,
    val skippedEntries: Int,
    val importedReflections: Int,
    val skippedReflections: Int,
)

private fun JournalEntryRecord.isAfter(other: JournalEntryRecord): Boolean =
    localDateTime > other.localDateTime || (localDateTime == other.localDateTime && id > other.id)
