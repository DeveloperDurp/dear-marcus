package com.dearmarcus.ai

import com.dearmarcus.core.JournalAiClient
import com.dearmarcus.core.JournalAiRequest
import com.dearmarcus.core.JournalAiResponse

class OnDeviceJournalAiClient(
    private val onDeviceAiClient: OnDeviceAiClient,
) : JournalAiClient {
    override suspend fun generate(request: JournalAiRequest): JournalAiResponse = when (
        val result = onDeviceAiClient.generate(request.prompt)
    ) {
        is AiGenerationResult.Success -> JournalAiResponse.Success(result.text)
        is AiGenerationResult.NotReady -> JournalAiResponse.Failure
        is AiGenerationResult.Failure -> JournalAiResponse.Failure
    }
}
