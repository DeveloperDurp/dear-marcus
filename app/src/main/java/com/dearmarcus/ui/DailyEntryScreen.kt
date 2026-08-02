package com.dearmarcus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dearmarcus.ai.AiReadiness
import com.dearmarcus.ai.AiUserAction
import com.dearmarcus.ai.AiUserGuidanceMapper

@Composable
fun DailyEntryScreen(
    state: DailyEntryUiState,
    onAnswerChanged: (DailyQuestion, String) -> Unit,
    onSave: () -> Unit,
    onDownloadModel: () -> Unit = {},
    onRetryAi: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = "Daily reflection",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Answer each question honestly. Your entry is saved on this device before reflection is attempted.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AiStatusFeedback(
            readiness = state.aiReadiness,
            onDownloadModel = onDownloadModel,
            onRetryAi = onRetryAi,
        )

        DailyAnswerField(
            question = DailyQuestion.WENT_WELL,
            label = "What went well today?",
            state = state,
            testTag = DailyEntryTestTags.WENT_WELL,
            onAnswerChanged = onAnswerChanged,
        )
        DailyAnswerField(
            question = DailyQuestion.WENT_POORLY,
            label = "What went poorly?",
            state = state,
            testTag = DailyEntryTestTags.WENT_POORLY,
            onAnswerChanged = onAnswerChanged,
        )
        DailyAnswerField(
            question = DailyQuestion.DO_DIFFERENTLY,
            label = "What would you do differently?",
            state = state,
            testTag = DailyEntryTestTags.DO_DIFFERENTLY,
            onAnswerChanged = onAnswerChanged,
        )

        Button(
            enabled = state.canSave,
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    state.submission is DailySubmissionState.Saving -> "Saving entry"
                    state.aiReadiness == AiReadiness.Available -> "Save entry and create reflection"
                    else -> "Save entry"
                },
            )
        }

        SubmissionFeedback(state.submission)
    }
}

@Composable
private fun AiStatusFeedback(
    readiness: AiReadiness?,
    onDownloadModel: () -> Unit,
    onRetryAi: () -> Unit,
) {
    if (readiness == null) return

    val guidance = AiUserGuidanceMapper.forReadiness(readiness)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DailyEntryTestTags.AI_STATUS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "On-device reflection",
            style = MaterialTheme.typography.titleMedium,
        )
        if (readiness == AiReadiness.Downloading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "On-device model download in progress" },
            )
            Text(
                text = guidance.message,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                text = guidance.message,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        when (guidance.action) {
            AiUserAction.DownloadOnDeviceModel -> Button(
                onClick = onDownloadModel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Download on-device model")
            }
            AiUserAction.RetryInForeground -> TextButton(onClick = onRetryAi) {
                Text("Check on-device AI again")
            }
            AiUserAction.EditEntry -> Text(
                text = "Shorten an answer, then save again to retry reflection on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AiUserAction.None,
            AiUserAction.Generate,
            -> Unit
        }
    }
}

@Composable
private fun DailyAnswerField(
    question: DailyQuestion,
    label: String,
    state: DailyEntryUiState,
    testTag: String,
    onAnswerChanged: (DailyQuestion, String) -> Unit,
) {
    val validationMessage = state.validationMessage(question)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = state.answerFor(question),
            onValueChange = { answer -> onAnswerChanged(question, answer) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .testTag(testTag)
                .semantics { contentDescription = label },
            enabled = state.submission !is DailySubmissionState.Saving,
            isError = validationMessage != null,
            minLines = 4,
            supportingText = {
                Column {
                    Text(
                        text = state.counterFor(question),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    validationMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun SubmissionFeedback(submission: DailySubmissionState) {
    when (submission) {
        DailySubmissionState.Idle -> Unit
        DailySubmissionState.Saving -> Text(
            text = "Saving your entry on this device.",
            style = MaterialTheme.typography.bodyLarge,
        )
        is DailySubmissionState.Reflected -> {
            Text(
                text = "Reflection",
                style = MaterialTheme.typography.titleMedium,
            )
            SelectionContainer {
                Text(
                    text = submission.feedback,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        is DailySubmissionState.SavedWithoutReflection -> {
            Text(
                text = "Entry saved.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = submission.message,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        is DailySubmissionState.SaveFailed -> Text(
            text = submission.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
