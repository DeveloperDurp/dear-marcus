package com.dearmarcus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReviewScreen(
    state: ReviewUiState,
    onRefreshInsights: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .testTag(ReviewTestTags.SCREEN)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("Stoic review", style = MaterialTheme.typography.headlineMedium)

        val reflection = state.currentReflection
        if (reflection == null) {
            Text("No valid review yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Save an entry and create a supported on-device reflection to see a local review.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Current condensed memory", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(
                    text = reflection.memoryAfter,
                    modifier = Modifier.testTag(ReviewTestTags.CURRENT_MEMORY),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                "Latest feedback · ${reflection.generatedAt.display()}",
                style = MaterialTheme.typography.titleMedium,
            )
            SelectionContainer {
                Text(
                    text = reflection.feedback,
                    modifier = Modifier.testTag(ReviewTestTags.LATEST_FEEDBACK),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        if (state.needsRefresh) {
            Column(
                modifier = Modifier.testTag(ReviewTestTags.STALE_NOTICE),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Insights need refresh",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    if (reflection == null) {
                        "No valid local review is available yet."
                    } else {
                        "Showing the last valid local review."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !state.isWorking,
                    onClick = onRefreshInsights,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ReviewTestTags.REFRESH),
                ) {
                    Text("Refresh Insights")
                }
            }
        }

        state.statusMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun Instant.display(): String = atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MMM d, uuuu · HH:mm"))
