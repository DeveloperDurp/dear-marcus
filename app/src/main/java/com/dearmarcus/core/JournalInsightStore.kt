package com.dearmarcus.core

import com.dearmarcus.data.JournalEntryRecord
import com.dearmarcus.data.JournalRepository
import com.dearmarcus.data.ReflectionRecord

interface JournalInsightStore {
    suspend fun saveEntry(entry: JournalEntry)

    suspend fun entriesOldestFirst(): List<JournalEntry>

    suspend fun reflectionFor(entryId: String): Reflection?

    suspend fun invalidateReflectionsAtOrAfter(entry: JournalEntry)

    suspend fun latestValidReflectionBefore(entry: JournalEntry): Reflection?

    suspend fun highestMemoryRevision(): Int

    suspend fun saveReflectionIfEntryUnchanged(
        entry: JournalEntry,
        reflection: Reflection,
    ): Boolean
}

class RoomJournalInsightStore(
    private val repository: JournalRepository,
) : JournalInsightStore {
    override suspend fun saveEntry(entry: JournalEntry) {
        repository.saveEntry(entry.toRecord())
    }

    override suspend fun entriesOldestFirst(): List<JournalEntry> =
        repository.entries().map(JournalEntryRecord::toCoreEntry)

    override suspend fun reflectionFor(entryId: String): Reflection? =
        repository.reflection(entryId)?.toCoreReflection()

    override suspend fun invalidateReflectionsAtOrAfter(entry: JournalEntry) {
        repository.invalidateReflectionsAtOrAfter(entry.toRecord())
    }

    override suspend fun latestValidReflectionBefore(entry: JournalEntry): Reflection? =
        repository.latestValidReflectionBefore(entry.toRecord())?.toCoreReflection()

    override suspend fun highestMemoryRevision(): Int = repository.highestMemoryRevision()

    override suspend fun saveReflectionIfEntryUnchanged(
        entry: JournalEntry,
        reflection: Reflection,
    ): Boolean = repository.saveReflectionIfEntryUnchanged(entry.toRecord(), reflection.toRecord())
}

internal sealed interface ReflectionPersistenceResult {
    data class Saved(val reflection: Reflection) : ReflectionPersistenceResult

    data class NotGenerated(val failure: ReflectionFailure) : ReflectionPersistenceResult

    data object EntryChanged : ReflectionPersistenceResult
}

internal suspend fun JournalInsightStore.generateAndPersistReflection(
    entry: JournalEntry,
    reflectionGenerator: ReflectionGenerator,
    clock: JournalClock,
): ReflectionPersistenceResult {
    val memoryBefore = latestValidReflectionBefore(entry)?.memoryAfter().orEmpty()
    return when (
        val generated = reflectionGenerator.generate(ReflectionInput(memoryBefore, entry.answers()))
    ) {
        is ReflectionGenerationResult.Success -> {
            val reflection = Reflection.successful(
                entry.id(),
                generated.feedback,
                memoryBefore,
                generated.memoryAfter,
                Reflection.nextMemoryRevision(highestMemoryRevision()),
                clock,
            )
            if (saveReflectionIfEntryUnchanged(entry, reflection)) {
                ReflectionPersistenceResult.Saved(reflection)
            } else {
                ReflectionPersistenceResult.EntryChanged
            }
        }
        is ReflectionGenerationResult.NoReflection ->
            ReflectionPersistenceResult.NotGenerated(generated.failure)
    }
}

private fun JournalEntry.toRecord(): JournalEntryRecord = JournalEntryRecord(
    id = id(),
    localDateTime = localDateTime(),
    wentWell = answers().whatWentWell(),
    wentPoorly = answers().whatWentPoorly(),
    doDifferently = answers().whatWouldYouDoDifferently(),
    updatedAt = updatedAt(),
)

private fun JournalEntryRecord.toCoreEntry(): JournalEntry {
    val record = this
    return JournalEntry.create(
        JournalIdGenerator { record.id },
        JournalClock { record.updatedAt },
        record.localDateTime,
        JournalAnswers.of(record.wentWell, record.wentPoorly, record.doDifferently),
    )
}

private fun Reflection.toRecord(): ReflectionRecord = ReflectionRecord(
    entryId = entryId(),
    feedback = feedback(),
    memoryBefore = memoryBefore(),
    memoryAfter = memoryAfter(),
    memoryRevision = memoryRevision(),
    generatedAt = generatedAt(),
    aiStatus = aiStatus().name,
    isValid = isValid(),
)

private fun ReflectionRecord.toCoreReflection(): Reflection {
    val record = this
    require(record.aiStatus == AiStatus.AVAILABLE.name) {
        "Stored reflections must originate from validated available inference."
    }
    val reflection = Reflection.successful(
        record.entryId,
        record.feedback,
        record.memoryBefore,
        record.memoryAfter,
        record.memoryRevision,
        JournalClock { record.generatedAt },
    )
    return if (record.isValid) reflection else reflection.invalidated()
}
