package com.dearmarcus.core

/** A fakeable boundary around the on-device prompt API. */
interface JournalAiClient {
    suspend fun generate(request: JournalAiRequest): JournalAiResponse
}

/**
 * The prompt contains static instructions plus only the current memory and three new answers.
 * It is transient and must never be persisted.
 */
data class JournalAiRequest(
    val prompt: String,
    val temperature: Float,
)

sealed interface JournalAiResponse {
    data class Success(val text: String) : JournalAiResponse

    data object Failure : JournalAiResponse
}

/** The only dynamic journal context included in a reflection request. */
data class ReflectionInput(
    val memoryBefore: String,
    val answers: JournalAnswers,
)

enum class ReflectionFailure(val userMessage: String) {
    CLIENT_UNAVAILABLE(
        "On-device reflection is unavailable. Your entry remains saved; try again from the foreground.",
    ),
    INVALID_OUTPUT(
        "On-device reflection returned unusable output. Your entry remains saved; try again later.",
    ),
    INPUT_TOO_LARGE(
        "Reflection was not generated because the on-device input is too large. Shorten the entry and try again.",
    ),
    ENTRY_CHANGED(
        "The entry changed before feedback could be saved. Your latest answers remain saved; refresh from the foreground.",
    ),
}

sealed interface ReflectionGenerationResult {
    data class Success(
        val feedback: String,
        val memoryAfter: String,
    ) : ReflectionGenerationResult

    /** [retainedMemory] is always the caller's original memory, never untrusted model output. */
    data class NoReflection(
        val failure: ReflectionFailure,
        val retainedMemory: String,
    ) : ReflectionGenerationResult
}
