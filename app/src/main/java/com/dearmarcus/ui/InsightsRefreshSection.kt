package com.dearmarcus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun InsightsRefreshSection(
    state: SettingsUiState,
    onRefreshInsights: () -> Unit,
) {
    if (!state.needsInsightsRefresh &&
        !state.isRefreshingInsights &&
        state.insightsRefreshStatusMessage == null
    ) {
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("On-device reflections", style = MaterialTheme.typography.titleMedium)
        if (state.needsInsightsRefresh) {
            Text(
                "Regenerate reflections for entries with missing or stale insights.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                enabled = !state.isRefreshingInsights,
                onClick = onRefreshInsights,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.REFRESH_INSIGHTS),
            ) {
                Text("Refresh insights")
            }
        }
        if (state.isRefreshingInsights) {
            Text("Refreshing insights…", style = MaterialTheme.typography.bodyLarge)
        }
        state.insightsRefreshStatusMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
