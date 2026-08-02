package com.dearmarcus.core

import java.time.LocalDateTime

class SubmitJournal(
    private val store: JournalInsightStore,
    private val reflectionGenerator: ReflectionGenerator,
    private val idGenerator: JournalIdGenerator,
    private val clock: JournalClock,
) {
    /** Call only from a direct foreground user action. */
    suspend fun submit(
        localDateTime: LocalDateTime,
        whatWentWell: String,
        whatWentPoorly: String,
        whatWouldYouDoDifferently: String,
    ): SubmitJournalResult {
        val answers = JournalAnswers.of(whatWentWell, whatWentPoorly, whatWouldYouDoDifferently)
        val entry = JournalEntry.create(idGenerator, clock, localDateTime, answers)
        store.saveEntry(entry)

        return when (val result = store.generateAndPersistReflection(entry, reflectionGenerator, clock)) {
            is ReflectionPersistenceResult.Saved -> SubmitJournalResult.Reflected(entry, result.reflection)
            is ReflectionPersistenceResult.NotGenerated ->
                SubmitJournalResult.SavedWithoutReflection(entry, result.failure)
            ReflectionPersistenceResult.EntryChanged ->
                SubmitJournalResult.SavedWithoutReflection(entry, ReflectionFailure.ENTRY_CHANGED)
        }
    }
}

sealed interface SubmitJournalResult {
    data class Reflected(
        val entry: JournalEntry,
        val reflection: Reflection,
    ) : SubmitJournalResult

    data class SavedWithoutReflection(
        val entry: JournalEntry,
        val failure: ReflectionFailure,
    ) : SubmitJournalResult
}
