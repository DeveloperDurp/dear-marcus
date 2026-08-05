package com.dearmarcus

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dearmarcus.ai.AiUserGuidanceMapper
import com.dearmarcus.core.RefreshInsightsResult
import com.dearmarcus.ui.DearMarcusRoot
import com.dearmarcus.ui.DailyEntryViewModel
import com.dearmarcus.ui.HistoryAnswers
import com.dearmarcus.ui.HistoryDataSource
import com.dearmarcus.ui.HistoryEntry
import com.dearmarcus.ui.HistoryViewModel
import com.dearmarcus.ui.ReviewDataSource
import com.dearmarcus.ui.ReviewSnapshot
import com.dearmarcus.ui.ReviewViewModel
import com.dearmarcus.ui.SettingsTestTags
import com.dearmarcus.ui.SettingsUiState
import com.dearmarcus.ui.theme.DearMarcusTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySettingsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsDestinationIsRestoredAcrossActivityRecreation() {
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithTag(SettingsTestTags.SCREEN).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNode(hasText("Settings").and(hasClickAction())).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTestTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun settingsRendersTheExistingDailyViewModelAiState() {
        lateinit var daily: DailyEntryViewModel
        composeRule.runOnIdle {
            daily = ViewModelProvider(composeRule.activity)[DailyEntryViewModel::class.java]
        }
        composeRule.waitUntil(timeoutMillis = 10_000) { daily.uiState.value.aiReadiness != null }

        composeRule.onNodeWithText("Settings").performClick()

        composeRule.onNodeWithTag(SettingsTestTags.AI_STATUS).assertIsDisplayed()
        composeRule.onNodeWithText(
            AiUserGuidanceMapper.forReadiness(daily.uiState.value.aiReadiness!!).message,
        ).assertIsDisplayed()
    }

    @Test
    fun notificationPermissionResultKeepsReminderDisabledWhenDeniedAndPersistsItWhenGranted() {
        composeRule.runOnIdle { composeRule.activity.onNotificationPermissionResult(false) }
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithTag(SettingsTestTags.REMINDER_ENABLED).assertIsOff()
        composeRule.onNodeWithText(
            "Notifications are disabled. Allow them in Android settings to receive reminders.",
        ).assertIsDisplayed()

        composeRule.runOnIdle { composeRule.activity.onNotificationPermissionResult(true) }
        composeRule.onNodeWithTag(SettingsTestTags.REMINDER_ENABLED).assertIsOn()

        composeRule.onNodeWithTag(SettingsTestTags.REMINDER_ENABLED).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.REMINDER_ENABLED).assertIsOff()
    }

    @Test
    fun reminderTimeSelectionPersistsAndSurvivesActivityRecreation() {
        // Given
        composeRule.onNodeWithText("Settings").performClick()

        // When
        composeRule.runOnIdle { composeRule.activity.onReminderTimeSelected(7, 15) }

        // Then
        composeRule.onNodeWithText("Reminder time: 07:15").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNode(hasText("Settings").and(hasClickAction())).performClick()
        composeRule.onNodeWithText("Reminder time: 07:15").assertIsDisplayed()
    }

    @Test
    fun settingsRefreshInvokesReviewOnceAndReloadsSnapshotsAfterCompletion() {
        val historyDataSource = RefreshContractHistoryDataSource()
        val reviewDataSource = RefreshContractReviewDataSource()
        lateinit var reviewViewModel: ReviewViewModel

        composeRule.activityRule.scenario.onActivity { activity ->
            val dailyViewModel = ViewModelProvider(activity)[DailyEntryViewModel::class.java]
            val historyViewModel = HistoryViewModel(historyDataSource, Dispatchers.Main.immediate)
            reviewViewModel = ReviewViewModel(reviewDataSource, Dispatchers.Main.immediate)
            activity.setContent {
                DearMarcusTheme {
                    DearMarcusRoot(
                        viewModel = dailyViewModel,
                        historyViewModel = historyViewModel,
                        reviewViewModel = reviewViewModel,
                        createBackupDocument = { error("Backup is not exercised in this test.") },
                        decodeBackup = { error("Backup is not exercised in this test.") },
                        importBackup = { error("Backup is not exercised in this test.") },
                        settingsState = SettingsUiState(),
                        onReminderEnabledChanged = {},
                        onReminderTimeClick = {},
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            reviewViewModel.uiState.value.needsRefresh && !reviewViewModel.uiState.value.isWorking
        }
        composeRule.onNode(hasText("Settings").and(hasClickAction())).performClick()
        composeRule.onNodeWithText(
            "Regenerate reflections for entries with missing or stale insights.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.REFRESH_INSIGHTS).assertIsEnabled().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            reviewDataSource.refreshCalls.get() == 1 && reviewViewModel.uiState.value.isWorking
        }
        composeRule.onNodeWithText("Refreshing insights…").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.REFRESH_INSIGHTS)
            .assertIsNotEnabled()
            .performTouchInput { click(center) }
        composeRule.runOnIdle { assertEquals(1, reviewDataSource.refreshCalls.get()) }

        composeRule.runOnIdle { reviewDataSource.completeRefresh() }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            reviewViewModel.uiState.value.statusMessage == "Insights refreshed." &&
                !reviewViewModel.uiState.value.needsRefresh &&
                !reviewViewModel.uiState.value.isWorking
        }
        composeRule.onNodeWithText("Insights refreshed.").assertIsDisplayed()
        composeRule.onAllNodesWithTag(SettingsTestTags.REFRESH_INSIGHTS).assertCountEquals(0)

        val historyCallsBeforeNavigation = historyDataSource.entriesCalls.get()
        val reviewSnapshotsBeforeNavigation = reviewDataSource.snapshotCalls.get()
        composeRule.onNode(hasText("History").and(hasClickAction())).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            historyDataSource.entriesCalls.get() > historyCallsBeforeNavigation
        }
        composeRule.onNode(hasText("Review").and(hasClickAction())).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            reviewDataSource.snapshotCalls.get() > reviewSnapshotsBeforeNavigation
        }
        assertEquals(1, reviewDataSource.refreshCalls.get())
    }
}

private class RefreshContractHistoryDataSource : HistoryDataSource {
    val entriesCalls = AtomicInteger()

    override suspend fun entriesNewestFirst(): List<HistoryEntry> {
        entriesCalls.incrementAndGet()
        return emptyList()
    }

    override suspend fun edit(entryId: String, answers: HistoryAnswers): Boolean = false

    override suspend fun delete(entryId: String): Boolean = false

    override suspend fun clearAll() = Unit
}

private class RefreshContractReviewDataSource : ReviewDataSource {
    private val refreshCompletion = CompletableDeferred<Unit>()
    private var hasInvalidDerivedData = true

    val refreshCalls = AtomicInteger()
    val snapshotCalls = AtomicInteger()

    override suspend fun snapshot(): ReviewSnapshot {
        snapshotCalls.incrementAndGet()
        return ReviewSnapshot(
            latestValidReflection = null,
            hasInvalidDerivedData = hasInvalidDerivedData,
        )
    }

    override suspend fun refreshInsights(): RefreshInsightsResult {
        refreshCalls.incrementAndGet()
        refreshCompletion.await()
        hasInvalidDerivedData = false
        return RefreshInsightsResult.Completed(listOf("entry-1"))
    }

    fun completeRefresh() {
        refreshCompletion.complete(Unit)
    }
}
