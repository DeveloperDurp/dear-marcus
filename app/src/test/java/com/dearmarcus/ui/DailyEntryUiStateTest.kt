package com.dearmarcus.ui

import androidx.lifecycle.SavedStateHandle
import com.dearmarcus.ai.AiDownloadState
import com.dearmarcus.ai.AiReadiness
import com.dearmarcus.ai.OnDeviceAiClient
import com.dearmarcus.core.JournalAnswers
import com.dearmarcus.core.JournalClock
import com.dearmarcus.core.JournalEntry
import com.dearmarcus.core.JournalIdGenerator
import com.dearmarcus.core.ReflectionFailure
import com.dearmarcus.core.SubmitJournalResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class DailyEntryUiStateTest {
    @Test
    fun codePointLimitAccepts600EmojiAndRejects601WithoutChangingTheInput() {
        val withinLimit = "\uD83D\uDE42".repeat(600)
        val overLimit = "\uD83D\uDE42".repeat(601)

        val baseState = DailyEntryUiState(
            whatWentPoorly = "I rushed a reply.",
            whatWouldYouDoDifferently = "I will pause.",
        )
        val validState = baseState.withAnswer(DailyQuestion.WENT_WELL, withinLimit)
        val invalidState = baseState.withAnswer(DailyQuestion.WENT_WELL, overLimit)

        assertEquals("600 / 600", validState.counterFor(DailyQuestion.WENT_WELL))
        assertTrue(validState.allAnswersAreValid)
        assertEquals("601 / 600", invalidState.counterFor(DailyQuestion.WENT_WELL))
        assertFalse(invalidState.allAnswersAreValid)
        assertEquals(overLimit, invalidState.whatWentWell)
    }

    @Test
    fun draftRestoresFromSavedStateHandleWithoutSubmittingAnEntry() {
        val savedStateHandle = SavedStateHandle()
        val submitter = DailyJournalSubmitter { _, _, _ ->
            error("Draft restoration must not submit an entry.")
        }
        val original = DailyEntryViewModel(savedStateHandle, submitter)

        original.updateAnswer(DailyQuestion.WENT_WELL, "I listened carefully.")
        original.updateAnswer(DailyQuestion.WENT_POORLY, "I interrupted.")
        original.updateAnswer(DailyQuestion.DO_DIFFERENTLY, "I will pause first.")

        val restored = DailyEntryViewModel(savedStateHandle, submitter)

        assertEquals("I listened carefully.", restored.uiState.value.whatWentWell)
        assertEquals("I interrupted.", restored.uiState.value.whatWentPoorly)
        assertEquals("I will pause first.", restored.uiState.value.whatWouldYouDoDifferently)
        assertTrue(restored.uiState.value.allAnswersAreValid)
    }

    @Test
    fun cancellationRestoresDraftAndAllowsASuccessfulResubmit() {
        var attempts = 0
        val viewModel = DailyEntryViewModel(
            savedStateHandle = SavedStateHandle(),
            submitter = DailyJournalSubmitter { _, _, _ ->
                attempts += 1
                if (attempts == 1) throw CancellationException("interrupted")
                SubmitJournalResult.SavedWithoutReflection(
                    JournalEntry.create(
                        JournalIdGenerator { "entry-1" },
                        JournalClock { Instant.parse("2026-08-01T10:00:00Z") },
                        LocalDateTime.of(2026, 8, 1, 18, 30),
                        JournalAnswers.of("I listened.", "I rushed.", "I will pause."),
                    ),
                    ReflectionFailure.CLIENT_UNAVAILABLE,
                )
            },
            coroutineDispatcher = Dispatchers.Unconfined,
        )
        viewModel.updateAnswer(DailyQuestion.WENT_WELL, "I listened.")
        viewModel.updateAnswer(DailyQuestion.WENT_POORLY, "I rushed.")
        viewModel.updateAnswer(DailyQuestion.DO_DIFFERENTLY, "I will pause.")

        viewModel.submit()

        assertEquals(DailySubmissionState.Idle, viewModel.uiState.value.submission)
        assertTrue(viewModel.uiState.value.allAnswersAreValid)

        viewModel.submit()

        assertEquals(2, attempts)
        assertTrue(viewModel.uiState.value.submission is DailySubmissionState.SavedWithoutReflection)
    }

    @Test
    fun cancelledModelDownloadRestoresDownloadableAndAllowsAnExplicitRetry() {
        var attempts = 0
        val aiClient = object : OnDeviceAiClient {
            override suspend fun checkAvailability(): AiReadiness = AiReadiness.Downloadable

            override fun startUserInitiatedDownload() = flow {
                attempts += 1
                if (attempts == 1) throw CancellationException("interrupted")
                emit(AiDownloadState.Completed)
            }

            override suspend fun generate(prompt: String) = error("Download test must not generate feedback.")
        }
        val viewModel = DailyEntryViewModel(
            savedStateHandle = SavedStateHandle(),
            submitter = DailyJournalSubmitter { _, _, _ -> error("Download test must not submit.") },
            aiClient = aiClient,
            coroutineDispatcher = Dispatchers.Unconfined,
        )

        viewModel.startModelDownload()

        assertEquals(AiReadiness.Downloadable, viewModel.uiState.value.aiReadiness)

        viewModel.startModelDownload()

        assertEquals(2, attempts)
        assertEquals(AiReadiness.Downloadable, viewModel.uiState.value.aiReadiness)
    }

    @Test
    fun cancelledDownloadAfterViewModelCancellationStillRestoresDownloadableState() {
        val downloadStarted = CompletableDeferred<Unit>()
        val aiClient = object : OnDeviceAiClient {
            override suspend fun checkAvailability(): AiReadiness = AiReadiness.Downloadable

            override fun startUserInitiatedDownload() = flow {
                emit(AiDownloadState.Started)
                downloadStarted.await()
            }

            override suspend fun generate(prompt: String) = error("Download test must not generate feedback.")
        }
        val viewModel = DailyEntryViewModel(
            savedStateHandle = SavedStateHandle(),
            submitter = DailyJournalSubmitter { _, _, _ -> error("Download test must not submit.") },
            aiClient = aiClient,
            coroutineDispatcher = Dispatchers.Unconfined,
        )

        viewModel.startModelDownload()
        assertEquals(AiReadiness.Downloading, viewModel.uiState.value.aiReadiness)

        viewModel.cancelForTest()
        downloadStarted.complete(Unit)

        assertEquals(AiReadiness.Downloadable, viewModel.uiState.value.aiReadiness)
    }

    private fun DailyEntryViewModel.cancelForTest() {
        val onCleared = this::class.java.getDeclaredMethod("onCleared").apply {
            isAccessible = true
        }
        onCleared(this)
    }

    @Test
    fun editDuringSubmitIsRejectedAndCompletionClearsOnlySubmittedDraft() {
        val submissionRelease = CompletableDeferred<SubmitJournalResult>()
        val savedStateHandle = SavedStateHandle()
        val viewModel = DailyEntryViewModel(
            savedStateHandle = savedStateHandle,
            submitter = DailyJournalSubmitter { _, _, _ -> submissionRelease.await() },
            coroutineDispatcher = Dispatchers.Unconfined,
        )
        viewModel.updateAnswer(DailyQuestion.WENT_WELL, "I listened.")
        viewModel.updateAnswer(DailyQuestion.WENT_POORLY, "I rushed.")
        viewModel.updateAnswer(DailyQuestion.DO_DIFFERENTLY, "I will pause.")

        viewModel.submit()
        viewModel.updateAnswer(DailyQuestion.WENT_WELL, "A newer draft.")

        assertEquals(DailySubmissionState.Saving, viewModel.uiState.value.submission)
        assertEquals("I listened.", viewModel.uiState.value.whatWentWell)
        assertEquals("I listened.", savedStateHandle.get<String>("daily-entry-went-well"))

        submissionRelease.complete(
            SubmitJournalResult.SavedWithoutReflection(
                JournalEntry.create(
                    JournalIdGenerator { "entry-1" },
                    JournalClock { Instant.parse("2026-08-01T10:00:00Z") },
                    LocalDateTime.of(2026, 8, 1, 18, 30),
                    JournalAnswers.of("I listened.", "I rushed.", "I will pause."),
                ),
                ReflectionFailure.CLIENT_UNAVAILABLE,
            ),
        )

        assertTrue(viewModel.uiState.value.submission is DailySubmissionState.SavedWithoutReflection)
        assertEquals("", viewModel.uiState.value.whatWentWell)
        assertEquals(null, savedStateHandle.get<String>("daily-entry-went-well"))
    }
}
