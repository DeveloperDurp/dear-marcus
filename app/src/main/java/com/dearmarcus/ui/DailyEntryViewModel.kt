package com.dearmarcus.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.dearmarcus.ai.AiDownloadState
import com.dearmarcus.ai.AiFailure
import com.dearmarcus.ai.AiReadiness
import com.dearmarcus.ai.OnDeviceAiClient
import com.dearmarcus.core.ReflectionFailure
import com.dearmarcus.core.SubmitJournal
import com.dearmarcus.core.SubmitJournalResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

fun interface DailyJournalSubmitter {
    suspend fun submit(
        whatWentWell: String,
        whatWentPoorly: String,
        whatWouldYouDoDifferently: String,
    ): SubmitJournalResult
}

class SubmitJournalDailyJournalSubmitter(
    private val submitJournal: SubmitJournal,
    private val localDateTime: () -> LocalDateTime = LocalDateTime::now,
) : DailyJournalSubmitter {
    override suspend fun submit(
        whatWentWell: String,
        whatWentPoorly: String,
        whatWouldYouDoDifferently: String,
    ): SubmitJournalResult = submitJournal.submit(
        localDateTime = localDateTime(),
        whatWentWell = whatWentWell,
        whatWentPoorly = whatWentPoorly,
        whatWouldYouDoDifferently = whatWouldYouDoDifferently,
    )
}

class DailyEntryViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val submitter: DailyJournalSubmitter,
    private val aiClient: OnDeviceAiClient? = null,
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    private var readinessRevision = 0
    private val mutableUiState = MutableStateFlow(
        DailyEntryUiState(
            whatWentWell = savedStateHandle.get<String>(WENT_WELL_KEY).orEmpty(),
            whatWentPoorly = savedStateHandle.get<String>(WENT_POORLY_KEY).orEmpty(),
            whatWouldYouDoDifferently = savedStateHandle.get<String>(DO_DIFFERENTLY_KEY).orEmpty(),
        ),
    )

    val uiState: StateFlow<DailyEntryUiState> = mutableUiState

    init {
        if (aiClient != null) refreshAiReadiness()
    }

    fun updateAnswer(question: DailyQuestion, answer: String) {
        if (mutableUiState.value.submission is DailySubmissionState.Saving) return
        mutableUiState.value = mutableUiState.value.withAnswer(question, answer)
        savedStateHandle[draftKey(question)] = answer
    }

    fun submit() {
        val state = mutableUiState.value
        if (state.submission is DailySubmissionState.Saving) return
        if (!state.allAnswersAreValid) {
            mutableUiState.value = state.showingAllValidation()
            return
        }

        mutableUiState.value = state.copy(submission = DailySubmissionState.Saving)
        scope.launch {
            val result = try {
                submitter.submit(
                    whatWentWell = state.whatWentWell,
                    whatWentPoorly = state.whatWentPoorly,
                    whatWouldYouDoDifferently = state.whatWouldYouDoDifferently,
                )
            } catch (error: CancellationException) {
                mutableUiState.update { current ->
                    if (current.submission is DailySubmissionState.Saving) {
                        current.copy(submission = DailySubmissionState.Idle)
                    } else {
                        current
                    }
                }
                throw error
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    submission = DailySubmissionState.SaveFailed(
                        "Something went wrong. Check History; your entry may have been saved.",
                    ),
                )
                return@launch
            }

            clearDraft()
            mutableUiState.value = DailyEntryUiState(
                submission = result.toSubmissionState(),
                aiReadiness = result.toAiReadiness(mutableUiState.value.aiReadiness),
            )
        }
    }

    fun refreshAiReadiness() {
        val client = aiClient ?: return
        val revision = ++readinessRevision
        scope.launch {
            val readiness = try {
                client.checkAvailability()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                AiReadiness.Error(AiFailure.Unexpected)
            }
            if (revision == readinessRevision) {
                mutableUiState.value = mutableUiState.value.copy(aiReadiness = readiness)
            }
        }
    }

    fun startModelDownload() {
        val client = aiClient ?: return
        if (mutableUiState.value.aiReadiness != AiReadiness.Downloadable) return

        val revision = ++readinessRevision
        mutableUiState.value = mutableUiState.value.copy(aiReadiness = AiReadiness.Downloading)
        scope.launch {
            try {
                client.startUserInitiatedDownload().collect { state ->
                    if (revision != readinessRevision) return@collect
                    when (state) {
                        AiDownloadState.Started,
                        AiDownloadState.Downloading,
                        -> mutableUiState.value = mutableUiState.value.copy(
                            aiReadiness = AiReadiness.Downloading,
                        )
                        AiDownloadState.Completed -> refreshAiReadiness()
                        is AiDownloadState.Failed -> mutableUiState.value = mutableUiState.value.copy(
                            aiReadiness = AiReadiness.Error(state.failure),
                        )
                        is AiDownloadState.NotDownloadable -> mutableUiState.value = mutableUiState.value.copy(
                            aiReadiness = state.readiness,
                        )
                    }
                }
            } catch (error: CancellationException) {
                if (revision == readinessRevision) {
                    mutableUiState.value = mutableUiState.value.copy(aiReadiness = AiReadiness.Downloadable)
                    refreshAiReadiness()
                }
                throw error
            } catch (_: Exception) {
                if (revision == readinessRevision) {
                    mutableUiState.value = mutableUiState.value.copy(
                        aiReadiness = AiReadiness.Error(AiFailure.Unexpected),
                    )
                }
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    private fun clearDraft() {
        savedStateHandle.remove<String>(WENT_WELL_KEY)
        savedStateHandle.remove<String>(WENT_POORLY_KEY)
        savedStateHandle.remove<String>(DO_DIFFERENTLY_KEY)
    }

    private fun draftKey(question: DailyQuestion): String = when (question) {
        DailyQuestion.WENT_WELL -> WENT_WELL_KEY
        DailyQuestion.WENT_POORLY -> WENT_POORLY_KEY
        DailyQuestion.DO_DIFFERENTLY -> DO_DIFFERENTLY_KEY
    }

    private fun SubmitJournalResult.toSubmissionState(): DailySubmissionState = when (this) {
        is SubmitJournalResult.Reflected -> DailySubmissionState.Reflected(reflection.feedback())
        is SubmitJournalResult.SavedWithoutReflection ->
            DailySubmissionState.SavedWithoutReflection(failure.userMessage)
    }

    private fun SubmitJournalResult.toAiReadiness(
        previousReadiness: AiReadiness?,
    ): AiReadiness? = when (this) {
        is SubmitJournalResult.Reflected -> previousReadiness
        is SubmitJournalResult.SavedWithoutReflection -> when (failure) {
            ReflectionFailure.INVALID_OUTPUT -> AiReadiness.Error(AiFailure.InvalidOutput)
            ReflectionFailure.INPUT_TOO_LARGE -> AiReadiness.Error(AiFailure.TokenLimit)
            ReflectionFailure.CLIENT_UNAVAILABLE ->
                previousReadiness.takeIf { it is AiReadiness.Error } ?: AiReadiness.Unavailable
            ReflectionFailure.ENTRY_CHANGED -> previousReadiness
        }
    }

    private companion object {
        const val WENT_WELL_KEY = "daily-entry-went-well"
        const val WENT_POORLY_KEY = "daily-entry-went-poorly"
        const val DO_DIFFERENTLY_KEY = "daily-entry-do-differently"
    }
}
