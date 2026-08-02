package com.dearmarcus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dearmarcus.core.RefreshInsightsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val dataSource: HistoryDataSource,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = mutableUiState

    init {
        reload()
    }

    fun reload() = launchMutation { state -> state.copy(entries = dataSource.entriesNewestFirst()) }

    fun select(entryId: String) {
        mutableUiState.value = mutableUiState.value.copy(selectedEntryId = entryId, edit = null, statusMessage = null)
    }

    fun closeDetail() {
        mutableUiState.value = mutableUiState.value.copy(selectedEntryId = null, edit = null, statusMessage = null)
    }

    fun beginEdit() {
        val entry = mutableUiState.value.selectedEntry ?: return
        mutableUiState.value = mutableUiState.value.copy(
            edit = HistoryEditState(
                entryId = entry.entry.id,
                wentWell = entry.entry.wentWell,
                wentPoorly = entry.entry.wentPoorly,
                doDifferently = entry.entry.doDifferently,
            ),
            statusMessage = null,
        )
    }

    fun updateEdit(answer: HistoryAnswer, text: String) {
        val edit = mutableUiState.value.edit ?: return
        mutableUiState.value = mutableUiState.value.copy(
            edit = when (answer) {
                HistoryAnswer.WENT_WELL -> edit.copy(wentWell = text)
                HistoryAnswer.WENT_POORLY -> edit.copy(wentPoorly = text)
                HistoryAnswer.DO_DIFFERENTLY -> edit.copy(doDifferently = text)
            },
        )
    }

    fun cancelEdit() {
        mutableUiState.value = mutableUiState.value.copy(edit = null)
    }

    fun saveEdit() {
        val edit = mutableUiState.value.edit ?: return
        if (!edit.canSave) return
        launchMutation { state ->
            if (!dataSource.edit(edit.entryId, edit.answers())) {
                return@launchMutation state.copy(edit = null, statusMessage = "This entry is no longer available.")
            }
            state.copy(
                entries = dataSource.entriesNewestFirst(),
                edit = null,
                statusMessage = "Entry updated. Insights need refresh.",
            )
        }
    }

    fun requestDelete() {
        val entryId = mutableUiState.value.selectedEntryId ?: return
        mutableUiState.value = mutableUiState.value.copy(confirmation = HistoryConfirmation.Delete(entryId))
    }

    fun requestClearAll() {
        if (mutableUiState.value.entries.isEmpty()) return
        mutableUiState.value = mutableUiState.value.copy(confirmation = HistoryConfirmation.ClearAll)
    }

    fun dismissConfirmation() {
        mutableUiState.value = mutableUiState.value.copy(confirmation = null)
    }

    fun confirmDestructiveAction() {
        when (val confirmation = mutableUiState.value.confirmation) {
            is HistoryConfirmation.Delete -> launchMutation { state ->
                val deleted = dataSource.delete(confirmation.entryId)
                state.copy(
                    entries = dataSource.entriesNewestFirst(),
                    selectedEntryId = null,
                    confirmation = null,
                    statusMessage = if (deleted) "Entry deleted." else "This entry is no longer available.",
                )
            }
            HistoryConfirmation.ClearAll -> launchMutation { state ->
                dataSource.clearAll()
                state.copy(
                    entries = emptyList(),
                    selectedEntryId = null,
                    edit = null,
                    confirmation = null,
                    statusMessage = "All local journal data was cleared.",
                )
            }
            null -> Unit
        }
    }

    fun refreshInsights() {
        if (!mutableUiState.value.hasStaleInsights) return
        launchMutation { state ->
            val message = when (dataSource.refreshInsights()) {
                is RefreshInsightsResult.Completed -> "Insights refreshed."
                RefreshInsightsResult.NoRefreshRequired -> "Insights are already current."
                is RefreshInsightsResult.Stopped -> "Insights refresh stopped. Your entries remain saved."
            }
            state.copy(entries = dataSource.entriesNewestFirst(), statusMessage = message)
        }
    }

    private fun launchMutation(mutation: suspend (HistoryUiState) -> HistoryUiState) {
        if (mutableUiState.value.isWorking) return
        mutableUiState.value = mutableUiState.value.copy(isWorking = true, statusMessage = null)
        viewModelScope.launch(coroutineDispatcher) {
            try {
                mutableUiState.value = mutation(mutableUiState.value)
            } catch (error: CancellationException) {
                throw error
            } catch (_: LocalDataClearIncompleteException) {
                mutableUiState.value = mutableUiState.value.copy(
                    entries = emptyList(),
                    selectedEntryId = null,
                    edit = null,
                    isWorking = false,
                    confirmation = null,
                    statusMessage = "Entries and feedback were cleared, but some cached data could not be cleared.",
                )
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    isWorking = false,
                    confirmation = null,
                    statusMessage = "That action could not finish. Your saved entries remain available.",
                )
            } finally {
                mutableUiState.value = mutableUiState.value.copy(isWorking = false)
            }
        }
    }
}

enum class HistoryAnswer {
    WENT_WELL,
    WENT_POORLY,
    DO_DIFFERENTLY,
}
