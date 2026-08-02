package com.dearmarcus.data

import androidx.room.withTransaction

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
