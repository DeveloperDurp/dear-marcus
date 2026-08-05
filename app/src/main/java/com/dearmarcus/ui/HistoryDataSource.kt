package com.dearmarcus.ui

import com.dearmarcus.core.JournalAnswers
import com.dearmarcus.data.JournalEntryRecord
import com.dearmarcus.data.JournalRepository
import com.dearmarcus.data.ReflectionRecord
import java.time.Instant
import kotlinx.coroutines.CancellationException

data class HistoryEntry(
    val entry: JournalEntryRecord,
    val reflection: ReflectionRecord?,
)

typealias LocalDataCleaner = suspend () -> Unit

class LocalDataClearIncompleteException(cause: Throwable) : IllegalStateException(cause)

interface HistoryDataSource {
    suspend fun entriesNewestFirst(): List<HistoryEntry>

    suspend fun edit(entryId: String, answers: HistoryAnswers): Boolean

    suspend fun delete(entryId: String): Boolean

    suspend fun clearAll()
}

data class HistoryAnswers(
    val wentWell: String,
    val wentPoorly: String,
    val doDifferently: String,
)

class RepositoryHistoryDataSource(
    private val repository: JournalRepository,
    private val clock: () -> Instant,
    private val localDataCleaner: LocalDataCleaner = {},
) : HistoryDataSource {
    override suspend fun entriesNewestFirst(): List<HistoryEntry> = repository.entries()
        .map { entry -> HistoryEntry(entry, repository.reflection(entry.id)) }
        .sortedWith(compareByDescending<HistoryEntry> { it.entry.localDateTime }.thenByDescending { it.entry.id })

    override suspend fun edit(entryId: String, answers: HistoryAnswers): Boolean {
        JournalAnswers.of(answers.wentWell, answers.wentPoorly, answers.doDifferently)
        val current = repository.entry(entryId) ?: return false
        return repository.editEntry(
            current.copy(
                wentWell = answers.wentWell,
                wentPoorly = answers.wentPoorly,
                doDifferently = answers.doDifferently,
                updatedAt = clock(),
            ),
        )
    }

    override suspend fun delete(entryId: String): Boolean = repository.deleteEntry(entryId)

    override suspend fun clearAll() {
        repository.clearAll()
        try {
            localDataCleaner()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw LocalDataClearIncompleteException(error)
        }
    }
}
