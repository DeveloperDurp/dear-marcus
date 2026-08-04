package com.dearmarcus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dearmarcus.core.JournalAnswers
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun HistoryDetail(
    entry: HistoryEntry,
    edit: HistoryEditState?,
    isWorking: Boolean,
    statusMessage: String?,
    onClose: () -> Unit,
    onBeginEdit: () -> Unit,
    onEditChanged: (HistoryAnswer, String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRequestDelete: () -> Unit,
    onRefreshInsights: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .testTag(HistoryTestTags.DETAIL)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(entry.entry.localDateTime.display(), style = MaterialTheme.typography.headlineMedium)
        if (edit == null) {
            ReadOnlyAnswers(entry)
            Feedback(entry, onRefreshInsights)
            statusMessage?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Button(enabled = !isWorking, onClick = onBeginEdit, modifier = Modifier.fillMaxWidth()) {
                Text("Edit entry")
            }
            TextButton(enabled = !isWorking, onClick = onRequestDelete, modifier = Modifier.fillMaxWidth()) {
                Text("Delete entry")
            }
        } else {
            EditAnswers(edit, isWorking, onEditChanged)
            Button(
                enabled = edit.canSave && !isWorking,
                onClick = onSaveEdit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save changes") }
            TextButton(enabled = !isWorking, onClick = onCancelEdit, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel edit")
            }
        }
        TextButton(onClick = onClose) { Text("Back to history") }
    }
}

@Composable
private fun ReadOnlyAnswers(entry: HistoryEntry) {
    AnswerBlock("What went well today?", entry.entry.wentWell)
    AnswerBlock("What went poorly?", entry.entry.wentPoorly)
    AnswerBlock("What would you do differently?", entry.entry.doDifferently)
}

@Composable
private fun Feedback(entry: HistoryEntry, onRefreshInsights: () -> Unit) {
    val reflection = entry.reflection
    if (reflection == null) {
        Text(
            "No feedback was created for this entry.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text("Feedback · ${reflection.generatedAt.display()}", style = MaterialTheme.typography.titleMedium)
    Text(reflection.feedback, style = MaterialTheme.typography.bodyLarge)
    if (!reflection.isValid) {
        Text(
            "Insights need refresh",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRefreshInsights) { Text("Refresh insights") }
    }
}

@Composable
private fun EditAnswers(
    edit: HistoryEditState,
    isWorking: Boolean,
    onEditChanged: (HistoryAnswer, String) -> Unit,
) {
    EditAnswerField("What went well today?", edit.wentWell, HistoryTestTags.WENT_WELL, !isWorking) {
        onEditChanged(HistoryAnswer.WENT_WELL, it)
    }
    EditAnswerField("What went poorly?", edit.wentPoorly, HistoryTestTags.WENT_POORLY, !isWorking) {
        onEditChanged(HistoryAnswer.WENT_POORLY, it)
    }
    EditAnswerField("What would you do differently?", edit.doDifferently, HistoryTestTags.DO_DIFFERENTLY, !isWorking) {
        onEditChanged(HistoryAnswer.DO_DIFFERENTLY, it)
    }
}

@Composable
private fun EditAnswerField(
    label: String,
    value: String,
    tag: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .testTag(tag),
            minLines = 4,
            supportingText = {
                Text(
                    "${value.codePointCount(0, value.length)} / ${JournalAnswers.MAXIMUM_CODE_POINTS}",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
    }
}

@Composable
private fun AnswerBlock(label: String, answer: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(answer, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun LocalDateTime.display(): String = format(DateTimeFormatter.ofPattern("MMM d, uuuu · HH:mm"))

private fun Instant.display(): String = atZone(ZoneId.systemDefault()).toLocalDateTime().display()
