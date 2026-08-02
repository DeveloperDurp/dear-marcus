package com.dearmarcus.core

class RefreshInsights(
    private val store: JournalInsightStore,
    private val reflectionGenerator: ReflectionGenerator,
    private val clock: JournalClock,
) {
    /** Call only from a direct foreground user action. */
    suspend fun refresh(): RefreshInsightsResult {
        val entries = store.entriesOldestFirst()
        val firstInvalidIndex = entries.indexOfFirst { entry ->
            store.reflectionFor(entry.id())?.isValid() != true
        }
        if (firstInvalidIndex == -1) return RefreshInsightsResult.NoRefreshRequired

        store.invalidateReflectionsAtOrAfter(entries[firstInvalidIndex])
        val refreshedEntryIds = mutableListOf<String>()
        for (entry in entries.drop(firstInvalidIndex)) {
            if (store.reflectionFor(entry.id())?.isValid() == true) continue

            when (val result = store.generateAndPersistReflection(entry, reflectionGenerator, clock)) {
                is ReflectionPersistenceResult.Saved -> refreshedEntryIds += entry.id()
                is ReflectionPersistenceResult.NotGenerated -> {
                    return RefreshInsightsResult.Stopped(refreshedEntryIds, result.failure)
                }
                ReflectionPersistenceResult.EntryChanged -> {
                    return RefreshInsightsResult.Stopped(
                        refreshedEntryIds,
                        ReflectionFailure.ENTRY_CHANGED,
                    )
                }
            }
        }

        return if (refreshedEntryIds.isEmpty()) {
            RefreshInsightsResult.NoRefreshRequired
        } else {
            RefreshInsightsResult.Completed(refreshedEntryIds)
        }
    }
}

sealed interface RefreshInsightsResult {
    data object NoRefreshRequired : RefreshInsightsResult

    data class Completed(val refreshedEntryIds: List<String>) : RefreshInsightsResult

    data class Stopped(
        val refreshedEntryIds: List<String>,
        val failure: ReflectionFailure,
    ) : RefreshInsightsResult
}
