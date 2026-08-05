package com.dearmarcus.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dearmarcus.ai.AiReadiness
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyEntryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displaysTheThreeApprovedQuestionsAndDisablesSaveUntilEveryAnswerIsValid() {
        composeRule.setDailyEntryContent()

        composeRule.onNodeWithText("What went well today?").assertIsDisplayed()
        composeRule.onNodeWithText("What went poorly?").assertIsDisplayed()
        composeRule.onNodeWithText("What would you do differently?").assertIsDisplayed()
        composeRule.onNodeWithText("Save entry").assertIsNotEnabled()

        composeRule.onNodeWithTag(DailyEntryTestTags.WENT_WELL).performTextInput("I listened.")
        composeRule.onNodeWithTag(DailyEntryTestTags.WENT_POORLY).performTextInput("I rushed.")
        composeRule.onNodeWithTag(DailyEntryTestTags.DO_DIFFERENTLY)
            .performTextInput("I will pause.")

        composeRule.onNodeWithText("Save entry").assertIsEnabled()

        composeRule.onNodeWithTag(DailyEntryTestTags.DO_DIFFERENTLY).performTextClearance()

        composeRule.onNodeWithText("This answer is required.").assertIsDisplayed()
        composeRule.onNodeWithText("Save entry").assertIsNotEnabled()
    }

    @Test
    fun showsRequiredValidationWhenAnEnteredAnswerIsCleared() {
        composeRule.setDailyEntryContent(
            DailyEntryUiState(whatWentWell = "A quiet walk."),
        )

        composeRule.onNodeWithTag(DailyEntryTestTags.WENT_WELL).performTextClearance()

        composeRule.onNodeWithText("This answer is required.").assertIsDisplayed()
        composeRule.onNodeWithTag(DailyEntryTestTags.WENT_WELL).assertTextContains("0 / 600")
    }

    @Test
    fun retainsOverLimitInputAndShowsItsCounterAndError() {
        composeRule.setDailyEntryContent()
        val overLimitAnswer = "a".repeat(601)

        composeRule.onNodeWithTag(DailyEntryTestTags.WENT_WELL).performTextInput(overLimitAnswer)

        composeRule.onNodeWithText("601 / 600").assertIsDisplayed()
        composeRule.onNodeWithText("Keep this answer to 600 characters or fewer.").assertIsDisplayed()
        composeRule.onNodeWithTag(DailyEntryTestTags.WENT_WELL)
            .assertIsDisplayed()
    }

    @Test
    fun bringsReflectedFeedbackIntoViewAfterAValidSavedSubmission() {
        composeRule.setDailyEntryContent(
            onSave = { state ->
                state.copy(
                    submission = DailySubmissionState.Reflected("Choose the deliberate response."),
                )
            },
        )

        composeRule.fillEveryAnswer()
        composeRule.onNodeWithText("Save entry").performScrollTo().performClick()

        composeRule.onNodeWithText("Entry saved.").assertIsDisplayed()
        composeRule.onNodeWithText("Choose the deliberate response.").assertIsDisplayed()
    }

    @Test
    fun bringsSavedWithoutReflectionFeedbackIntoViewAfterSubmissionCompletes() {
        composeRule.setDailyEntryContent(
            onSave = { state ->
                state.copy(
                    submission = DailySubmissionState.SavedWithoutReflection(
                        "On-device reflection is unavailable. Your entry remains saved; retry the saved entry from Settings while the app remains foregrounded.",
                    ),
                )
            },
        )

        composeRule.fillEveryAnswer()
        composeRule.onNodeWithText("Save entry").performScrollTo().performClick()

        composeRule.onNodeWithText("Entry saved.").assertIsDisplayed()
        composeRule.onNodeWithText(
            "On-device reflection is unavailable. Your entry remains saved; retry the saved entry from Settings while the app remains foregrounded.",
        ).assertIsDisplayed()
    }

    @Test
    fun availableAiKeepsReflectionSaveLabelWithoutRenderingSettingsControls() {
        composeRule.setDailyEntryContent(
            DailyEntryUiState(
                whatWentWell = "I listened.",
                whatWentPoorly = "I rushed.",
                whatWouldYouDoDifferently = "I will pause.",
                aiReadiness = AiReadiness.Available,
            ),
        )

        composeRule.onNodeWithText("Save entry and create reflection").assertIsEnabled()
        composeRule.onNodeWithTag("daily-ai-status").assertDoesNotExist()
        composeRule.onNodeWithText("On-device AI is ready.").assertDoesNotExist()
    }

    @Test
    fun bringsSaveFailureFeedbackIntoViewAfterSubmissionCompletes() {
        composeRule.setDailyEntryContent(
            onSave = { state ->
                state.copy(
                    submission = DailySubmissionState.SaveFailed(
                        "Could not save today. Please try again.",
                    ),
                )
            },
        )

        composeRule.fillEveryAnswer()
        composeRule.onNodeWithText("Save entry").performScrollTo().performClick()

        composeRule.onNodeWithText("Could not save today. Please try again.").assertIsDisplayed()
        composeRule.onNodeWithText("Entry saved.").assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setDailyEntryContent(
        initialState: DailyEntryUiState = DailyEntryUiState(),
        onSave: (DailyEntryUiState) -> DailyEntryUiState = { state -> state },
    ) {
        setContent {
            var state by remember { mutableStateOf(initialState) }
            DailyEntryScreen(
                state = state,
                onAnswerChanged = { question, answer ->
                    state = state.withAnswer(question, answer)
                },
                onSave = { state = onSave(state) },
            )
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.fillEveryAnswer() {
        onNodeWithTag(DailyEntryTestTags.WENT_WELL).performTextInput("I listened.")
        onNodeWithTag(DailyEntryTestTags.WENT_POORLY).performTextInput("I rushed.")
        onNodeWithTag(DailyEntryTestTags.DO_DIFFERENTLY).performTextInput("I will pause.")
    }
}
