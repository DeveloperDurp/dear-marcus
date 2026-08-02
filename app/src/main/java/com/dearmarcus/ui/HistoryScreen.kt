package com.dearmarcus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onSelect: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onBeginEdit: () -> Unit,
    onEditChanged: (HistoryAnswer, String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRequestDelete: () -> Unit,
    onRequestClearAll: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onConfirmDestructiveAction: () -> Unit,
    onRefreshInsights: () -> Unit,
    onExportJournal: () -> Unit,
    isExportingJournal: Boolean,
    journalExportStatus: String?,
    modifier: Modifier = Modifier,
) {
    val selected = state.selectedEntry
    if (selected == null) {
        HistoryList(
            state = state,
            onSelect = onSelect,
            onRequestClearAll = onRequestClearAll,
            onRefreshInsights = onRefreshInsights,
            onExportJournal = onExportJournal,
            isExportingJournal = isExportingJournal,
            journalExportStatus = journalExportStatus,
            modifier = modifier,
        )
    } else {
        HistoryDetail(
            entry = selected,
            edit = state.edit,
            isWorking = state.isWorking,
            statusMessage = state.statusMessage,
            onClose = onCloseDetail,
            onBeginEdit = onBeginEdit,
            onEditChanged = onEditChanged,
            onSaveEdit = onSaveEdit,
            onCancelEdit = onCancelEdit,
            onRequestDelete = onRequestDelete,
            onRefreshInsights = onRefreshInsights,
            modifier = modifier,
        )
    }
    HistoryConfirmationDialog(state.confirmation, onDismissConfirmation, onConfirmDestructiveAction)
}

@Composable
private fun HistoryList(
    state: HistoryUiState,
    onSelect: (String) -> Unit,
    onRequestClearAll: () -> Unit,
    onRefreshInsights: () -> Unit,
    onExportJournal: () -> Unit,
    isExportingJournal: Boolean,
    journalExportStatus: String?,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.testTag(HistoryTestTags.LIST),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("History", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Button(
                enabled = !state.isWorking && !isExportingJournal,
                onClick = onExportJournal,
                modifier = Modifier.testTag(HistoryTestTags.EXPORT),
            ) {
                Text(if (isExportingJournal) "Preparing export…" else "Export journal as Markdown")
            }
        }
        if (state.hasStaleInsights) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Insights need refresh",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Changes to earlier entries made later feedback stale.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(enabled = !state.isWorking, onClick = onRefreshInsights) {
                        Text("Refresh insights")
                    }
                }
            }
        }
        state.statusMessage?.let { message ->
            item { Text(message, style = MaterialTheme.typography.bodyLarge) }
        }
        journalExportStatus?.let { message ->
            item { Text(message, style = MaterialTheme.typography.bodyLarge) }
        }
        if (state.entries.isEmpty() && !state.isWorking) {
            item {
                Text(
                    "No saved entries yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.entries, key = { it.entry.id }) { item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Open entry from ${item.entry.localDateTime.display()}"
                    }
                    .clickable(role = Role.Button) { onSelect(item.entry.id) }
                    .testTag("history-entry-${item.entry.id}")
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(item.entry.localDateTime.display(), style = MaterialTheme.typography.titleMedium)
                Text(
                    item.entry.wentWell,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.reflection?.isValid == false) {
                    Text(
                        "Insights need refresh",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (state.entries.isNotEmpty()) {
            item {
                TextButton(enabled = !state.isWorking, onClick = onRequestClearAll) {
                    Text("Clear all local data")
                }
            }
        }
    }
}

@Composable
private fun HistoryConfirmationDialog(
    confirmation: HistoryConfirmation?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    when (confirmation) {
        is HistoryConfirmation.Delete -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete entry?") },
            text = { Text("This permanently removes this local entry and its feedback. Later insights need refresh.") },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Delete entry") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        HistoryConfirmation.ClearAll -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Clear all local data?") },
            text = { Text("This removes entries, feedback, local preferences, and cached app data from this device.") },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Clear all") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        null -> Unit
    }
}
