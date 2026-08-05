package com.dearmarcus.ui

import com.dearmarcus.ai.AiReadiness
import com.dearmarcus.core.JournalAnswers

enum class DailyQuestion {
    WENT_WELL,
    WENT_POORLY,
    DO_DIFFERENTLY,
}

sealed interface DailySubmissionState {
    data object Idle : DailySubmissionState

    data object Saving : DailySubmissionState

    data class Reflected(val feedback: String) : DailySubmissionState

    data class SavedWithoutReflection(val message: String) : DailySubmissionState

    data class SaveFailed(val message: String) : DailySubmissionState
}

data class DailyEntryUiState(
    val whatWentWell: String = "",
    val whatWentPoorly: String = "",
    val whatWouldYouDoDifferently: String = "",
    val editedQuestions: Set<DailyQuestion> = emptySet(),
    val submission: DailySubmissionState = DailySubmissionState.Idle,
    val aiReadiness: AiReadiness? = null,
) {
    val canSave: Boolean
        get() = submission !is DailySubmissionState.Saving && allAnswersAreValid

    fun answerFor(question: DailyQuestion): String = when (question) {
        DailyQuestion.WENT_WELL -> whatWentWell
        DailyQuestion.WENT_POORLY -> whatWentPoorly
        DailyQuestion.DO_DIFFERENTLY -> whatWouldYouDoDifferently
    }

    fun validationMessage(question: DailyQuestion): String? {
        if (question !in editedQuestions) return null
        val answer = answerFor(question)
        return when {
            answer.isBlank() -> "This answer is required."
            answer.codePointCount(0, answer.length) > JournalAnswers.MAXIMUM_CODE_POINTS ->
                "Keep this answer to ${JournalAnswers.MAXIMUM_CODE_POINTS} characters or fewer."
            else -> null
        }
    }

    fun counterFor(question: DailyQuestion): String =
        "${answerFor(question).codePointCount(0, answerFor(question).length)} / ${JournalAnswers.MAXIMUM_CODE_POINTS}"

    fun withAnswer(question: DailyQuestion, answer: String): DailyEntryUiState {
        if (answer == answerFor(question)) return this
        return when (question) {
            DailyQuestion.WENT_WELL -> copy(
                whatWentWell = answer,
                editedQuestions = editedQuestions + question,
                submission = DailySubmissionState.Idle,
            )
            DailyQuestion.WENT_POORLY -> copy(
                whatWentPoorly = answer,
                editedQuestions = editedQuestions + question,
                submission = DailySubmissionState.Idle,
            )
            DailyQuestion.DO_DIFFERENTLY -> copy(
                whatWouldYouDoDifferently = answer,
                editedQuestions = editedQuestions + question,
                submission = DailySubmissionState.Idle,
            )
        }
    }

    fun showingAllValidation(): DailyEntryUiState = copy(
        editedQuestions = DailyQuestion.entries.toSet(),
    )

    val allAnswersAreValid: Boolean
        get() = DailyQuestion.entries.all { question ->
            answerFor(question).isNotBlank() &&
                answerFor(question).codePointCount(0, answerFor(question).length) <=
                JournalAnswers.MAXIMUM_CODE_POINTS
        }
}

object DailyEntryTestTags {
    const val WENT_WELL = "daily-answer-went-well"
    const val WENT_POORLY = "daily-answer-went-poorly"
    const val DO_DIFFERENTLY = "daily-answer-do-differently"
}
