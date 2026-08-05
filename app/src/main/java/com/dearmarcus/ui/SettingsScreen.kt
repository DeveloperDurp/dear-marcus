package com.dearmarcus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dearmarcus.ai.AiReadiness
import com.dearmarcus.ai.AiUserAction
import com.dearmarcus.ai.AiUserGuidanceMapper
import com.dearmarcus.reminder.ReminderSettings
import com.dearmarcus.reminder.ReminderTime

data class SettingsUiState(
    val reminderSettings: ReminderSettings = ReminderSettings(enabled = false, time = ReminderTime(20, 0)),
    val isReminderPermissionDenied: Boolean = false,
    val reminderStatusMessage: String? = null,
    val exportStatus: SettingsBackupStatus = SettingsBackupStatus.Idle,
    val importStatus: SettingsBackupStatus = SettingsBackupStatus.Idle,
    val needsInsightsRefresh: Boolean = false,
    val isRefreshingInsights: Boolean = false,
    val insightsRefreshStatusMessage: String? = null,
)

sealed interface SettingsBackupStatus {
    data object Idle : SettingsBackupStatus

    data object Working : SettingsBackupStatus

    data object Cancelled : SettingsBackupStatus

    data class Succeeded(val message: String) : SettingsBackupStatus

    data class Failed(val message: String) : SettingsBackupStatus
}

object SettingsTestTags {
    const val SCREEN = "settings-screen"
    const val AI_STATUS = "settings-ai-status"
    const val DOWNLOAD_MODEL = "settings-download-model"
    const val RETRY_AI = "settings-retry-ai"
    const val REFRESH_INSIGHTS = "settings-refresh-insights"
    const val REMINDER_ENABLED = "settings-reminder-enabled"
    const val REMINDER_TIME = "settings-reminder-time"
    const val EXPORT_BACKUP = "settings-export-backup"
    const val IMPORT_BACKUP = "settings-import-backup"
}

@Composable
fun SettingsScreen(
    dailyState: DailyEntryUiState,
    state: SettingsUiState,
    onDownloadModel: () -> Unit,
    onRetryAi: () -> Unit,
    onRefreshInsights: () -> Unit = {},
    onReminderEnabledChanged: (Boolean) -> Unit,
    onReminderTimeClick: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .testTag(SettingsTestTags.SCREEN)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        AiStatusFeedback(dailyState.aiReadiness, onDownloadModel, onRetryAi)
        InsightsRefreshSection(
            state = state,
            onRefreshInsights = onRefreshInsights,
        )
        ReminderSettingsSection(
            state = state,
            onReminderEnabledChanged = onReminderEnabledChanged,
            onReminderTimeClick = onReminderTimeClick,
        )
        BackupSection(
            exportStatus = state.exportStatus,
            importStatus = state.importStatus,
            onExportBackup = onExportBackup,
            onImportBackup = onImportBackup,
        )
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
            .testTag(SettingsTestTags.AI_STATUS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("On-device reflection", style = MaterialTheme.typography.titleMedium)
        if (readiness == AiReadiness.Downloading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "On-device model download in progress" },
            )
        }
        Text(guidance.message, style = MaterialTheme.typography.bodyLarge)
        when (guidance.action) {
            AiUserAction.DownloadOnDeviceModel -> Button(
                onClick = onDownloadModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.DOWNLOAD_MODEL),
            ) {
                Text("Download on-device model")
            }
            AiUserAction.RetryInForeground -> TextButton(
                onClick = onRetryAi,
                modifier = Modifier.testTag(SettingsTestTags.RETRY_AI),
            ) {
                Text("Check on-device AI again")
            }
            AiUserAction.EditEntry -> Text(
                "Shorten an answer, then save again to retry reflection on this device.",
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
private fun ReminderSettingsSection(
    state: SettingsUiState,
    onReminderEnabledChanged: (Boolean) -> Unit,
    onReminderTimeClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Daily reminder", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Enable daily reminder", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = state.reminderSettings.enabled,
                onCheckedChange = onReminderEnabledChanged,
                modifier = Modifier
                    .testTag(SettingsTestTags.REMINDER_ENABLED)
                    .semantics { contentDescription = "Enable daily reminder" },
            )
        }
        TextButton(
            onClick = onReminderTimeClick,
            modifier = Modifier.testTag(SettingsTestTags.REMINDER_TIME),
        ) {
            Text("Reminder time: ${state.reminderSettings.time.display()}")
        }
        if (state.isReminderPermissionDenied) {
            Text(
                "Notifications are disabled. Allow them in Android settings to receive reminders.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.reminderStatusMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun BackupSection(
    exportStatus: SettingsBackupStatus,
    importStatus: SettingsBackupStatus,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local backup", style = MaterialTheme.typography.titleMedium)
        BackupAction(
            status = exportStatus,
            actionLabel = "Export local backup",
            workingLabel = "Preparing backup…",
            cancelledLabel = "Backup export was cancelled.",
            testTag = SettingsTestTags.EXPORT_BACKUP,
            onClick = onExportBackup,
        )
        BackupAction(
            status = importStatus,
            actionLabel = "Import local backup",
            workingLabel = "Importing backup…",
            cancelledLabel = "Backup import was cancelled.",
            testTag = SettingsTestTags.IMPORT_BACKUP,
            onClick = onImportBackup,
        )
    }
}

@Composable
private fun BackupAction(
    status: SettingsBackupStatus,
    actionLabel: String,
    workingLabel: String,
    cancelledLabel: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Button(
        enabled = status !is SettingsBackupStatus.Working,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Text(actionLabel)
    }
    when (status) {
        SettingsBackupStatus.Idle -> Unit
        SettingsBackupStatus.Working -> Text(workingLabel, style = MaterialTheme.typography.bodyLarge)
        SettingsBackupStatus.Cancelled -> Text(cancelledLabel, style = MaterialTheme.typography.bodyLarge)
        is SettingsBackupStatus.Succeeded -> Text(status.message, style = MaterialTheme.typography.bodyLarge)
        is SettingsBackupStatus.Failed -> Text(
            status.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun ReminderTime.display(): String = "%02d:%02d".format(hour, minute)
