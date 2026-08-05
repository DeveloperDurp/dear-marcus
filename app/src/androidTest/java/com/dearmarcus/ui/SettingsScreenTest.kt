package com.dearmarcus.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dearmarcus.ai.AiFailure
import com.dearmarcus.ai.AiReadiness
import com.dearmarcus.reminder.ReminderSettings
import com.dearmarcus.reminder.ReminderTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun presentsEveryAiReadinessStateWithTheExistingGuidance() {
        var readiness by mutableStateOf<AiReadiness>(AiReadiness.Available)
        composeRule.setSettingsContent(dailyState = { DailyEntryUiState(aiReadiness = readiness) })

        listOf(
            AiReadiness.Available to "On-device AI is ready.",
            AiReadiness.Downloadable to "Download the on-device model to create feedback.",
            AiReadiness.Downloading to "The on-device model is downloading.",
            AiReadiness.Unavailable to "On-device AI is unavailable on this device. Your entry will still be saved.",
            AiReadiness.Error(AiFailure.Busy) to "On-device AI is busy. Try again from the app shortly.",
        ).forEach { (nextReadiness, message) ->
            composeRule.runOnIdle { readiness = nextReadiness }
            composeRule.onNodeWithTag(SettingsTestTags.AI_STATUS).assertIsDisplayed()
            composeRule.onNodeWithText(message).assertIsDisplayed()
        }

        composeRule.runOnIdle { readiness = AiReadiness.Downloading }
        composeRule.onNodeWithContentDescription("On-device model download in progress").assertIsDisplayed()
    }

    @Test
    fun delegatesAiDownloadAndRetryOnlyThroughItsExplicitCallbacks() {
        var downloads = 0
        var retries = 0
        var readiness by mutableStateOf<AiReadiness>(AiReadiness.Downloadable)
        composeRule.setSettingsContent(
            dailyState = { DailyEntryUiState(aiReadiness = readiness) },
            onDownloadModel = { downloads += 1 },
            onRetryAi = { retries += 1 },
        )

        composeRule.onNodeWithTag(SettingsTestTags.DOWNLOAD_MODEL).performClick()
        assertEquals(1, downloads)

        composeRule.runOnIdle { readiness = AiReadiness.Error(AiFailure.Busy) }
        composeRule.onNodeWithTag(SettingsTestTags.RETRY_AI).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun delegatesReminderToggleAndTimeAndExplainsDeniedPermission() {
        var enabled: Boolean? = null
        var timeRequests = 0
        composeRule.setSettingsContent(
            state = {
                SettingsUiState(
                    reminderSettings = ReminderSettings(false, ReminderTime(20, 0)),
                    isReminderPermissionDenied = true,
                    reminderStatusMessage = "Reminder could not be scheduled.",
                )
            },
            onReminderEnabledChanged = { enabled = it },
            onReminderTimeClick = { timeRequests += 1 },
        )

        composeRule.onNodeWithTag(SettingsTestTags.REMINDER_ENABLED).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.REMINDER_TIME).performClick()

        assertEquals(true, enabled)
        assertEquals(1, timeRequests)
        composeRule.onNodeWithText("Notifications are disabled. Allow them in Android settings to receive reminders.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Reminder could not be scheduled.").assertIsDisplayed()
    }

    @Test
    fun delegatesBackupActionsAndPresentsWorkingAndFeedbackStates() {
        var exportCalls = 0
        var importCalls = 0
        var state by mutableStateOf(SettingsUiState())
        composeRule.setSettingsContent(
            state = { state },
            onExportBackup = { exportCalls += 1 },
            onImportBackup = { importCalls += 1 },
        )

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_BACKUP).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.IMPORT_BACKUP).performScrollTo().performClick()
        assertEquals(1, exportCalls)
        assertEquals(1, importCalls)

        composeRule.runOnIdle { state = state.copy(exportStatus = SettingsBackupStatus.Working) }
        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_BACKUP).assertIsNotEnabled()
        composeRule.onNodeWithText("Preparing backup…").assertIsDisplayed()

        listOf(
            SettingsBackupStatus.Cancelled to "Backup export was cancelled.",
            SettingsBackupStatus.Failed("Backup export could not be saved.") to "Backup export could not be saved.",
            SettingsBackupStatus.Succeeded("Backup export saved.") to "Backup export saved.",
        ).forEach { (status, message) ->
            composeRule.runOnIdle { state = state.copy(exportStatus = status) }
            composeRule.onNodeWithText(message).assertIsDisplayed()
        }

        composeRule.runOnIdle {
            state = state.copy(importStatus = SettingsBackupStatus.Working)
        }
        composeRule.onNodeWithTag(SettingsTestTags.IMPORT_BACKUP).assertIsNotEnabled()
        composeRule.onNodeWithText("Importing backup…").assertIsDisplayed()

        composeRule.runOnIdle { state = state.copy(importStatus = SettingsBackupStatus.Cancelled) }
        composeRule.onNodeWithText("Backup import was cancelled.").assertIsDisplayed()

        composeRule.runOnIdle {
            state = state.copy(importStatus = SettingsBackupStatus.Failed("Backup import could not be completed."))
        }
        composeRule.onNodeWithText("Backup import could not be completed.").assertIsDisplayed()

        composeRule.runOnIdle { state = state.copy(importStatus = SettingsBackupStatus.Succeeded("Backup import completed.")) }
        composeRule.onNodeWithText("Backup import completed.").assertIsDisplayed()
    }

    @Test
    fun hidesInsightsRefreshWithoutStaleDerivedData() {
        composeRule.setSettingsContent()

        composeRule.onAllNodesWithTag(SettingsTestTags.REFRESH_INSIGHTS).assertCountEquals(0)
    }

    @Test
    fun delegatesInsightsRefreshOnceWhenDerivedDataIsStale() {
        var refreshCalls = 0
        composeRule.setSettingsContent(
            state = { SettingsUiState(needsInsightsRefresh = true) },
            onRefreshInsights = { refreshCalls += 1 },
        )

        composeRule.onNodeWithTag(SettingsTestTags.REFRESH_INSIGHTS).performScrollTo().performClick()

        assertEquals(1, refreshCalls)
    }

    @Test
    fun disablesInsightsRefreshWhileWorkingAndPresentsTerminalFeedback() {
        var state by mutableStateOf(
            SettingsUiState(needsInsightsRefresh = true, isRefreshingInsights = true),
        )
        composeRule.setSettingsContent(state = { state })

        composeRule.onNodeWithTag(SettingsTestTags.REFRESH_INSIGHTS).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Refreshing insights…").assertIsDisplayed()

        listOf(
            "Insights refresh stopped. Your entries remain saved.",
            "That action could not finish. Your saved entries remain available.",
        ).forEach { statusMessage ->
            composeRule.runOnIdle {
                state = state.copy(isRefreshingInsights = false, insightsRefreshStatusMessage = statusMessage)
            }
            composeRule.onNodeWithText(statusMessage).assertIsDisplayed()
        }

        composeRule.runOnIdle {
            state = state.copy(
                needsInsightsRefresh = false,
                isRefreshingInsights = false,
                insightsRefreshStatusMessage = "Insights refreshed.",
            )
        }
        composeRule.onNodeWithText("Insights refreshed.").assertIsDisplayed()
        composeRule.onAllNodesWithTag(SettingsTestTags.REFRESH_INSIGHTS).assertCountEquals(0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setSettingsContent(
        state: () -> SettingsUiState = { SettingsUiState() },
        dailyState: () -> DailyEntryUiState = { DailyEntryUiState() },
        onDownloadModel: () -> Unit = {},
        onRetryAi: () -> Unit = {},
        onRefreshInsights: () -> Unit = {},
        onReminderEnabledChanged: (Boolean) -> Unit = {},
        onReminderTimeClick: () -> Unit = {},
        onExportBackup: () -> Unit = {},
        onImportBackup: () -> Unit = {},
    ) {
        setContent {
            SettingsScreen(
                state = state(),
                dailyState = dailyState(),
                onDownloadModel = onDownloadModel,
                onRetryAi = onRetryAi,
                onRefreshInsights = onRefreshInsights,
                onReminderEnabledChanged = onReminderEnabledChanged,
                onReminderTimeClick = onReminderTimeClick,
                onExportBackup = onExportBackup,
                onImportBackup = onImportBackup,
            )
        }
    }
}
