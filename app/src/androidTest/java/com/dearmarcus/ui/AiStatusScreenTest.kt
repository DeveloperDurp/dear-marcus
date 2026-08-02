package com.dearmarcus.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dearmarcus.ai.AiDownloadState
import com.dearmarcus.ai.AiFailure
import com.dearmarcus.ai.AiGenerationResult
import com.dearmarcus.ai.AiReadiness
import com.dearmarcus.ai.OnDeviceAiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiStatusScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displaysEveryNormalizedNanoStatusWithoutJournalContent() {
        val cases = listOf(
            AiReadiness.Available to "On-device AI is ready.",
            AiReadiness.Downloadable to "Download the on-device model to create feedback.",
            AiReadiness.Downloading to "The on-device model is downloading.",
            AiReadiness.Unavailable to "On-device AI is unavailable on this device. Your entry will still be saved.",
            AiReadiness.Error(AiFailure.Busy) to "On-device AI is busy. Try again from the app shortly.",
            AiReadiness.Error(AiFailure.Quota) to "On-device AI has reached its usage limit. Try again later from the app.",
            AiReadiness.Error(AiFailure.BackgroundBlocked) to "On-device AI only runs while the app is open. Try again from the app.",
            AiReadiness.Error(AiFailure.TokenLimit) to "This reflection is too large to process on-device.",
            AiReadiness.Error(AiFailure.InvalidOutput) to "On-device AI returned an incomplete response. Try again from the app.",
            AiReadiness.Error(AiFailure.Unexpected) to "On-device AI could not create feedback. Try again from the app.",
        )
        var readiness by mutableStateOf<AiReadiness>(AiReadiness.Available)

        composeRule.setContent {
            DailyEntryScreen(
                state = DailyEntryUiState(aiReadiness = readiness),
                onAnswerChanged = { _, _ -> },
                onSave = {},
            )
        }

        cases.forEach { (nextReadiness, message) ->
            composeRule.runOnIdle { readiness = nextReadiness }
            composeRule.onNodeWithTag(DailyEntryTestTags.AI_STATUS).assertIsDisplayed()
            composeRule.onNodeWithText(message).assertIsDisplayed()
            when (nextReadiness) {
                is AiReadiness.Error -> if (nextReadiness.failure == AiFailure.TokenLimit) {
                    composeRule.onNodeWithText(
                        "Shorten an answer, then save again to retry reflection on this device.",
                    ).assertIsDisplayed()
                } else {
                    composeRule.onNodeWithText("Check on-device AI again").assertIsDisplayed()
                }
                else -> Unit
            }
        }
    }

    @Test
    fun availableKeepsTheValidEntrySaveActionReflectionCapable() {
        composeRule.setContent {
            DailyEntryScreen(
                state = DailyEntryUiState(
                    whatWentWell = "I listened.",
                    whatWentPoorly = "I rushed.",
                    whatWouldYouDoDifferently = "I will pause.",
                    aiReadiness = AiReadiness.Available,
                ),
                onAnswerChanged = { _, _ -> },
                onSave = {},
            )
        }

        composeRule.onNodeWithText("Save entry and create reflection").assertIsEnabled()
    }

    @Test
    fun downloadableStatusRemainsDiscoverableAtOnePointFiveFontScale() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                DailyEntryScreen(
                    state = DailyEntryUiState(aiReadiness = AiReadiness.Downloadable),
                    onAnswerChanged = { _, _ -> },
                    onSave = {},
                )
            }
        }

        composeRule.onNodeWithText("Download the on-device model to create feedback.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Download on-device model").assertIsDisplayed()
    }

    @Test
    fun downloadableOffersTheOnlyDownloadActionAndRapidTapsStartOneRequest() {
        val aiClient = BlockingDownloadAiClient()
        val viewModel = DailyEntryViewModel(
            savedStateHandle = SavedStateHandle(),
            submitter = DailyJournalSubmitter { _, _, _ -> error("Download does not submit an entry.") },
            aiClient = aiClient,
        )

        composeRule.setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            DailyEntryScreen(
                state = state,
                onAnswerChanged = viewModel::updateAnswer,
                onSave = viewModel::submit,
                onDownloadModel = viewModel::startModelDownload,
                onRetryAi = viewModel::refreshAiReadiness,
            )
        }

        composeRule.waitUntil { aiClient.availabilityChecks > 0 }
        composeRule.onNodeWithText("Download on-device model").performClick()
        composeRule.runOnIdle { viewModel.startModelDownload() }

        composeRule.onNodeWithText("The on-device model is downloading.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("On-device model download in progress").assertIsDisplayed()
        composeRule.onAllNodesWithText("Download on-device model").assertCountEquals(0)
        assertEquals(1, aiClient.downloadRequests)
        aiClient.finishDownload()
    }

    @Test
    fun staleAvailabilityResultDoesNotReplaceDownloadingUiState() {
        val aiClient = BlockingDownloadAiClient()
        val viewModel = DailyEntryViewModel(
            savedStateHandle = SavedStateHandle(),
            submitter = DailyJournalSubmitter { _, _, _ -> error("Download does not submit an entry.") },
            aiClient = aiClient,
        )

        composeRule.setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            DailyEntryScreen(
                state = state,
                onAnswerChanged = viewModel::updateAnswer,
                onSave = viewModel::submit,
                onDownloadModel = viewModel::startModelDownload,
                onRetryAi = viewModel::refreshAiReadiness,
            )
        }

        composeRule.waitUntil { aiClient.availabilityChecks > 0 }
        val delayedAvailability = CompletableDeferred<AiReadiness>()
        aiClient.delayedAvailability = delayedAvailability
        composeRule.runOnIdle { viewModel.refreshAiReadiness() }
        composeRule.waitUntil { aiClient.availabilityChecks > 1 }
        composeRule.onNodeWithText("Download on-device model").performClick()
        delayedAvailability.complete(AiReadiness.Available)

        composeRule.onNodeWithText("The on-device model is downloading.").assertIsDisplayed()
        aiClient.finishDownload()
    }

    @Test
    fun unavailableAndEveryFailureKeepSavedStateAndNeverRenderFeedback() {
        var readiness by mutableStateOf<AiReadiness>(AiReadiness.Unavailable)
        composeRule.setContent {
            DailyEntryScreen(
                state = DailyEntryUiState(
                    aiReadiness = readiness,
                    submission = DailySubmissionState.SavedWithoutReflection(
                        "Your entry remains saved; feedback was not created on this device.",
                    ),
                ),
                onAnswerChanged = { _, _ -> },
                onSave = {},
            )
        }

        listOf(
            AiReadiness.Unavailable,
            AiReadiness.Error(AiFailure.Busy),
            AiReadiness.Error(AiFailure.Quota),
            AiReadiness.Error(AiFailure.BackgroundBlocked),
            AiReadiness.Error(AiFailure.TokenLimit),
            AiReadiness.Error(AiFailure.InvalidOutput),
            AiReadiness.Error(AiFailure.Unexpected),
        ).forEach { nextReadiness ->
            composeRule.runOnIdle { readiness = nextReadiness }
            composeRule.onNodeWithText("Entry saved.").assertIsDisplayed()
            composeRule.onNodeWithText("Your entry remains saved; feedback was not created on this device.")
                .assertIsDisplayed()
            composeRule.onAllNodesWithText("Reflection").assertCountEquals(0)
        }
    }

    private class BlockingDownloadAiClient : OnDeviceAiClient {
        var availabilityChecks = 0
        var downloadRequests = 0
        var delayedAvailability: CompletableDeferred<AiReadiness>? = null
        private val downloadFinished = CompletableDeferred<Unit>()

        override suspend fun checkAvailability(): AiReadiness {
            availabilityChecks += 1
            return delayedAvailability?.await() ?: AiReadiness.Downloadable
        }

        override fun startUserInitiatedDownload(): Flow<AiDownloadState> = flow {
            downloadRequests += 1
            emit(AiDownloadState.Started)
            downloadFinished.await()
        }

        override suspend fun generate(prompt: String): AiGenerationResult =
            error("Download status tests never generate feedback.")

        fun finishDownload() {
            downloadFinished.complete(Unit)
        }
    }
}
