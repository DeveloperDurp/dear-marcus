package com.dearmarcus.ui

import com.dearmarcus.core.JournalAnswers

sealed interface HistoryConfirmation {
    data class Delete(val entryId: String) : HistoryConfirmation

    data object ClearAll : HistoryConfirmation
}

data class HistoryEditState(
    val entryId: String,
    val wentWell: String,
    val wentPoorly: String,
    val doDifferently: String,
) {
    val canSave: Boolean
        get() = listOf(wentWell, wentPoorly, doDifferently).all {
            it.isNotBlank() && it.codePointCount(0, it.length) <= JournalAnswers.MAXIMUM_CODE_POINTS
        }

    fun answers(): HistoryAnswers = HistoryAnswers(wentWell, wentPoorly, doDifferently)
}

data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
    val selectedEntryId: String? = null,
    val edit: HistoryEditState? = null,
    val confirmation: HistoryConfirmation? = null,
    val isWorking: Boolean = false,
    val statusMessage: String? = null,
) {
    val selectedEntry: HistoryEntry?
        get() = entries.firstOrNull { it.entry.id == selectedEntryId }

    val hasStaleInsights: Boolean
        get() = entries.any { it.reflection?.isValid != true }
}

object HistoryTestTags {
    const val LIST = "history-list"
    const val DETAIL = "history-detail"
    const val WENT_WELL = "history-edit-went-well"
    const val WENT_POORLY = "history-edit-went-poorly"
    const val DO_DIFFERENTLY = "history-edit-do-differently"
}
