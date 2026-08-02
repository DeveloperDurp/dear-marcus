package com.dearmarcus.ai

import com.dearmarcus.core.JournalAnswers
import com.dearmarcus.core.ReflectionFailure
import com.dearmarcus.core.ReflectionGenerationResult
import com.dearmarcus.core.ReflectionGenerator
import com.dearmarcus.core.ReflectionInput
import com.dearmarcus.core.TokenCounter
import com.dearmarcus.core.TokenCounterUnavailableException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitTokenBudgetTest {
    @Test
    fun exactModelCount_compactsBeforeInferenceAndNeverSendsOverBudgetPrompt() = runBlocking {
        val fullAnswer = "a".repeat(600)
        val gateway = TokenBudgetGateway { prompt ->
            if (prompt.contains(fullAnswer)) NanoPromptCall.Success(4_000) else NanoPromptCall.Success(120)
        }
        val aiClient = MlKitJournalAiClient.forGateway(gateway)
        val result = ReflectionGenerator(
            OnDeviceJournalAiClient(aiClient),
            tokenCounter(aiClient),
        ).generate(
            ReflectionInput(
                memoryBefore = "m".repeat(600),
                answers = JournalAnswers.of(fullAnswer, fullAnswer, fullAnswer),
            ),
        )

        assertTrue(result is ReflectionGenerationResult.Success)
        assertEquals(2, gateway.countedPrompts.size)
        assertEquals(1, gateway.generatedPrompts.size)
        assertFalse(gateway.generatedPrompts.single().contains(fullAnswer))
    }

    @Test
    fun exactModelCount_refusesWhenCompactedPromptIsStillAtCap() = runBlocking {
        val gateway = TokenBudgetGateway { NanoPromptCall.Success(4_000) }
        val aiClient = MlKitJournalAiClient.forGateway(gateway)
        val result = ReflectionGenerator(
            OnDeviceJournalAiClient(aiClient),
            tokenCounter(aiClient),
        ).generate(input())

        assertEquals(
            ReflectionGenerationResult.NoReflection(ReflectionFailure.INPUT_TOO_LARGE, "memory-before"),
            result,
        )
        assertEquals(2, gateway.countedPrompts.size)
        assertTrue(gateway.generatedPrompts.isEmpty())
    }

    @Test
    fun unavailableModelCount_refusesBeforeInference() = runBlocking {
        val gateway = TokenBudgetGateway { NanoPromptCall.Failure(NanoPromptFailure.Unexpected) }
        val aiClient = MlKitJournalAiClient.forGateway(gateway)
        val result = ReflectionGenerator(
            OnDeviceJournalAiClient(aiClient),
            tokenCounter(aiClient),
        ).generate(input())

        assertEquals(
            ReflectionGenerationResult.NoReflection(ReflectionFailure.CLIENT_UNAVAILABLE, "memory-before"),
            result,
        )
        assertEquals(1, gateway.countedPrompts.size)
        assertTrue(gateway.generatedPrompts.isEmpty())
    }

    private fun tokenCounter(client: MlKitJournalAiClient) = TokenCounter { prompt ->
        client.countInputTokens(prompt) ?: throw TokenCounterUnavailableException()
    }

    private fun input() = ReflectionInput(
        memoryBefore = "memory-before",
        answers = JournalAnswers.of("went well", "went poorly", "do differently"),
    )
}

private class TokenBudgetGateway(
    private val tokenResult: (String) -> NanoPromptCall<Int>,
) : NanoPromptGateway {
    val countedPrompts = mutableListOf<String>()
    val generatedPrompts = mutableListOf<String>()

    override suspend fun checkStatus(): NanoPromptCall<NanoPromptStatus> =
        NanoPromptCall.Success(NanoPromptStatus.Available)

    override fun download(): Flow<NanoPromptCall<NanoPromptDownloadEvent>> = emptyFlow()

    override suspend fun generate(
        prompt: String,
        maxOutputTokens: Int,
    ): NanoPromptCall<NanoPromptResponse> {
        generatedPrompts += prompt
        return NanoPromptCall.Success(
            NanoPromptResponse("{\"feedback\":\"feedback\",\"memory\":\"memory\"}", false),
        )
    }

    override suspend fun countTokens(prompt: String, maxOutputTokens: Int): NanoPromptCall<Int> {
        countedPrompts += prompt
        return tokenResult(prompt)
    }
}
