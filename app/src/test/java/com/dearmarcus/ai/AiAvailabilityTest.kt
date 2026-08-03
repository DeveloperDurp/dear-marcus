package com.dearmarcus.ai

import com.dearmarcus.core.AiStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AiAvailabilityTest {
    @Test
    fun sdkAvailabilityStates_mapToStableReadinessAndGuidance() = runBlocking {
        val scenarios = listOf(
            AvailabilityScenario(
                NanoPromptStatus.Available,
                AiReadiness.Available,
                AiStatus.AVAILABLE,
                AiUserGuidance("On-device AI is ready.", AiUserAction.Generate, true),
            ),
            AvailabilityScenario(
                NanoPromptStatus.Downloadable,
                AiReadiness.Downloadable,
                AiStatus.DOWNLOADABLE,
                AiUserGuidance(
                    "Download the on-device model to create feedback.",
                    AiUserAction.DownloadOnDeviceModel,
                    false,
                ),
            ),
            AvailabilityScenario(
                NanoPromptStatus.Downloading,
                AiReadiness.Downloading,
                AiStatus.DOWNLOADING,
                AiUserGuidance(
                    "The on-device model is downloading.",
                    AiUserAction.None,
                    false,
                ),
            ),
            AvailabilityScenario(
                NanoPromptStatus.Unavailable,
                AiReadiness.Unavailable,
                AiStatus.UNAVAILABLE,
                AiUserGuidance(
                    "On-device AI is unavailable on this device. Your entry will still be saved.",
                    AiUserAction.None,
                    false,
                ),
            ),
            AvailabilityScenario(
                NanoPromptStatus.Unknown,
                AiReadiness.Error(AiFailure.Unexpected),
                AiStatus.UNEXPECTED_ERROR,
                AiUserGuidance(
                    "On-device AI could not create feedback. Try again from the app.",
                    AiUserAction.RetryInForeground,
                    false,
                ),
            ),
        )

        scenarios.forEach { scenario ->
            val client = MlKitJournalAiClient.forGateway(
                FakeNanoPromptGateway(statusResult = NanoPromptCall.Success(scenario.status)),
            )

            val readiness = client.checkAvailability()

            assertEquals(scenario.readiness, readiness)
            assertEquals(scenario.coreStatus, readiness.status)
            assertEquals(scenario.guidance, AiUserGuidanceMapper.forReadiness(readiness))
        }
    }

    @Test
    fun downloadableModel_startsAndObservesDownloadOnlyAfterUserAction() = runBlocking {
        val gateway = FakeNanoPromptGateway(
            statusResult = NanoPromptCall.Success(NanoPromptStatus.Downloadable),
            downloadResults = listOf(
                NanoPromptCall.Success(NanoPromptDownloadEvent.Started),
                NanoPromptCall.Success(NanoPromptDownloadEvent.Downloading),
                NanoPromptCall.Success(NanoPromptDownloadEvent.Completed),
            ),
        )
        val client = MlKitJournalAiClient.forGateway(gateway)

        assertEquals(AiReadiness.Downloadable, client.checkAvailability())
        assertEquals(0, gateway.downloadCalls)

        val states = client.startUserInitiatedDownload().toList()

        assertEquals(
            listOf(
                AiDownloadState.Started,
                AiDownloadState.Downloading,
                AiDownloadState.Completed,
            ),
            states,
        )
        assertEquals(1, gateway.downloadCalls)
    }

    @Test
    fun downloadingAndUnavailableStates_doNotStartAnotherDownload() = runBlocking {
        listOf(NanoPromptStatus.Downloading, NanoPromptStatus.Unavailable).forEach { status ->
            val gateway = FakeNanoPromptGateway(statusResult = NanoPromptCall.Success(status))
            val client = MlKitJournalAiClient.forGateway(gateway)

            val states = client.startUserInitiatedDownload().toList()
            val duplicateStates = client.startUserInitiatedDownload().toList()

            assertEquals(1, states.size)
            assertEquals(1, duplicateStates.size)
            assertEquals(0, gateway.downloadCalls)
            assertEquals(AiDownloadState.NotDownloadable(client.checkAvailability()), states.single())
        }
    }

    @Test
    fun downloadFailure_isNormalizedWithoutRetry() = runBlocking {
        listOf(
            NanoPromptFailure.TokenLimit to AiFailure.TokenLimit,
            NanoPromptFailure.Busy to AiFailure.Busy,
            NanoPromptFailure.Quota to AiFailure.Quota,
            NanoPromptFailure.BackgroundBlocked to AiFailure.BackgroundBlocked,
            NanoPromptFailure.Unexpected to AiFailure.Unexpected,
        ).forEach { (sdkFailure, appFailure) ->
            val gateway = FakeNanoPromptGateway(
                statusResult = NanoPromptCall.Success(NanoPromptStatus.Downloadable),
                downloadResults = listOf(NanoPromptCall.Failure(sdkFailure)),
            )

            val states = MlKitJournalAiClient.forGateway(gateway).startUserInitiatedDownload().toList()

            assertEquals(listOf(AiDownloadState.Failed(appFailure)), states)
            assertEquals(1, gateway.downloadCalls)
        }
    }

    @Test
    fun generateWhenDownloadable_doesNotStartDownloadOrInference() = runBlocking {
        val gateway = FakeNanoPromptGateway(
            statusResult = NanoPromptCall.Success(NanoPromptStatus.Downloadable),
        )
        val client = MlKitJournalAiClient.forGateway(gateway)

        val result = client.generate("reflection request")

        assertEquals(AiGenerationResult.NotReady(AiReadiness.Downloadable), result)
        assertEquals(0, gateway.downloadCalls)
        assertEquals(0, gateway.generateCalls)
    }

    @Test
    fun availableGeneration_usesBoundedNonStreamingOutput() = runBlocking {
        val gateway = FakeNanoPromptGateway(
            statusResult = NanoPromptCall.Success(NanoPromptStatus.Available),
            generationResult = NanoPromptCall.Success(NanoPromptResponse("feedback", false)),
        )

        val result = MlKitJournalAiClient.forGateway(gateway).generate("reflection request")

        assertEquals(AiGenerationResult.Success("feedback"), result)
        assertEquals(1_024, gateway.lastMaxOutputTokens)
    }

    @Test
    fun sdkFailures_mapToDeterministicForegroundGuidanceWithoutRetry() = runBlocking {
        val scenarios = listOf(
            FailureScenario(
                NanoPromptFailure.TokenLimit,
                AiFailure.TokenLimit,
                AiStatus.TOKEN_LIMIT,
                AiUserGuidance(
                    "This reflection is too large to process on-device.",
                    AiUserAction.EditEntry,
                    false,
                ),
            ),
            FailureScenario(
                NanoPromptFailure.Busy,
                AiFailure.Busy,
                AiStatus.BUSY,
                AiUserGuidance(
                    "On-device AI is busy. Try again from the app shortly.",
                    AiUserAction.RetryInForeground,
                    false,
                ),
            ),
            FailureScenario(
                NanoPromptFailure.Quota,
                AiFailure.Quota,
                AiStatus.QUOTA_EXCEEDED,
                AiUserGuidance(
                    "On-device AI has reached its usage limit. Try again later from the app.",
                    AiUserAction.RetryInForeground,
                    false,
                ),
            ),
            FailureScenario(
                NanoPromptFailure.BackgroundBlocked,
                AiFailure.BackgroundBlocked,
                AiStatus.BACKGROUND_BLOCKED,
                AiUserGuidance(
                    "On-device AI only runs while the app is open. Try again from the app.",
                    AiUserAction.RetryInForeground,
                    false,
                ),
            ),
            FailureScenario(
                NanoPromptFailure.Unexpected,
                AiFailure.Unexpected,
                AiStatus.UNEXPECTED_ERROR,
                AiUserGuidance(
                    "On-device AI could not create feedback. Try again from the app.",
                    AiUserAction.RetryInForeground,
                    false,
                ),
            ),
        )

        scenarios.forEach { scenario ->
            val gateway = FakeNanoPromptGateway(
                statusResult = NanoPromptCall.Success(NanoPromptStatus.Available),
                generationResult = NanoPromptCall.Failure(scenario.sdkFailure),
            )
            val client = MlKitJournalAiClient.forGateway(gateway)

            val result = client.generate("reflection request")

            assertEquals(AiGenerationResult.Failure(scenario.appFailure), result)
            assertEquals(scenario.coreStatus, scenario.appFailure.status)
            assertEquals(scenario.guidance, AiUserGuidanceMapper.forFailure(scenario.appFailure))
            assertEquals(1, gateway.generateCalls)
        }
    }

    @Test
    fun misleadingSuccessfulResponses_doNotClaimGenerationSucceeded() = runBlocking {
        val blankGateway = FakeNanoPromptGateway(
            statusResult = NanoPromptCall.Success(NanoPromptStatus.Available),
            generationResult = NanoPromptCall.Success(NanoPromptResponse("   ", false)),
        )
        val tokenLimitedGateway = FakeNanoPromptGateway(
            statusResult = NanoPromptCall.Success(NanoPromptStatus.Available),
            generationResult = NanoPromptCall.Success(NanoPromptResponse("partial", true)),
        )

        val blankResult = MlKitJournalAiClient.forGateway(blankGateway).generate("reflection request")
        val tokenLimitedResult = MlKitJournalAiClient.forGateway(tokenLimitedGateway)
            .generate("reflection request")

        assertEquals(AiGenerationResult.Failure(AiFailure.InvalidOutput), blankResult)
        assertEquals(AiGenerationResult.Failure(AiFailure.TokenLimit), tokenLimitedResult)
        assertEquals(AiStatus.INVALID_OUTPUT, AiFailure.InvalidOutput.status)
        assertEquals(
            AiUserGuidance(
                "On-device AI returned an incomplete response. Try again from the app.",
                AiUserAction.RetryInForeground,
                false,
            ),
            AiUserGuidanceMapper.forFailure(AiFailure.InvalidOutput),
        )
    }

    @Test
    fun statusFailures_disableGenerationBeforeInference() = runBlocking {
        val gateway = FakeNanoPromptGateway(
            statusResult = NanoPromptCall.Failure(NanoPromptFailure.BackgroundBlocked),
        )
        val client = MlKitJournalAiClient.forGateway(gateway)

        val result = client.generate("reflection request")

        assertEquals(
            AiGenerationResult.NotReady(AiReadiness.Error(AiFailure.BackgroundBlocked)),
            result,
        )
        assertEquals(0, gateway.generateCalls)
    }

    @Test
    fun statusQuerySetupFailure_showsSetupGuidanceWithoutDownloadOrInference() = runBlocking {
        val gateway = FakeNanoPromptGateway(
            statusResult = NanoPromptCall.Failure(NanoPromptFailure.Unexpected),
        )
        val client = MlKitJournalAiClient.forGateway(gateway)

        val readiness = client.checkAvailability()

        assertEquals(AiReadiness.Error(AiFailure.SetupRequired), readiness)
        assertEquals(
            AiUserGuidance(
                "Set up or update on-device AI, then try again from the app.",
                AiUserAction.RetryInForeground,
                false,
            ),
            AiUserGuidanceMapper.forReadiness(readiness),
        )
        assertEquals(0, gateway.downloadCalls)
        assertEquals(0, gateway.generateCalls)
    }

    @Test
    fun unexpectedGatewayExceptions_areNormalizedAndCancellationPropagates() = runBlocking {
        val statusClient = MlKitJournalAiClient.forGateway(
            ThrowingNanoPromptGateway(ThrowPoint.Status),
        )
        val downloadClient = MlKitJournalAiClient.forGateway(
            ThrowingNanoPromptGateway(ThrowPoint.Download),
        )
        val generationClient = MlKitJournalAiClient.forGateway(
            ThrowingNanoPromptGateway(ThrowPoint.Generation),
        )
        val cancellationClient = MlKitJournalAiClient.forGateway(
            ThrowingNanoPromptGateway(ThrowPoint.GenerationCancellation),
        )

        assertEquals(AiReadiness.Error(AiFailure.SetupRequired), statusClient.checkAvailability())
        assertEquals(
            listOf(AiDownloadState.Failed(AiFailure.Unexpected)),
            downloadClient.startUserInitiatedDownload().toList(),
        )
        assertEquals(
            AiGenerationResult.Failure(AiFailure.Unexpected),
            generationClient.generate("reflection request"),
        )
        try {
            cancellationClient.generate("reflection request")
            fail("Expected cancellation to propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }

    @Test
    fun statusAndRepeatedDownloadCancellations_propagateWithoutRetry() = runBlocking {
        val statusClient = MlKitJournalAiClient.forGateway(
            ThrowingNanoPromptGateway(ThrowPoint.StatusCancellation),
        )
        val downloadGateway = ThrowingNanoPromptGateway(ThrowPoint.DownloadCancellation)
        val downloadClient = MlKitJournalAiClient.forGateway(downloadGateway)

        assertCancellation { statusClient.checkAvailability() }
        repeat(2) {
            assertCancellation { downloadClient.startUserInitiatedDownload().toList() }
        }

        assertEquals(2, downloadGateway.downloadCalls)
    }
}

private data class AvailabilityScenario(
    val status: NanoPromptStatus,
    val readiness: AiReadiness,
    val coreStatus: AiStatus,
    val guidance: AiUserGuidance,
)

private data class FailureScenario(
    val sdkFailure: NanoPromptFailure,
    val appFailure: AiFailure,
    val coreStatus: AiStatus,
    val guidance: AiUserGuidance,
)

private suspend fun assertCancellation(block: suspend () -> Unit) {
    try {
        block()
        fail("Expected cancellation to propagate")
    } catch (_: kotlinx.coroutines.CancellationException) {
    }
}

private class FakeNanoPromptGateway(
    private val statusResult: NanoPromptCall<NanoPromptStatus>,
    private val downloadResults: List<NanoPromptCall<NanoPromptDownloadEvent>> = emptyList(),
    private val generationResult: NanoPromptCall<NanoPromptResponse> = NanoPromptCall.Success(
        NanoPromptResponse("generated reflection", false),
    ),
    private val countResult: NanoPromptCall<Int> = NanoPromptCall.Success(100),
) : NanoPromptGateway {
    var downloadCalls = 0
        private set
    var generateCalls = 0
        private set
    var lastMaxOutputTokens: Int? = null
        private set

    override suspend fun checkStatus(): NanoPromptCall<NanoPromptStatus> = statusResult

    override fun download(): Flow<NanoPromptCall<NanoPromptDownloadEvent>> {
        downloadCalls += 1
        return downloadResults.asFlow()
    }

    override suspend fun generate(
        prompt: String,
        maxOutputTokens: Int,
    ): NanoPromptCall<NanoPromptResponse> {
        generateCalls += 1
        lastMaxOutputTokens = maxOutputTokens
        return generationResult
    }

    override suspend fun countTokens(prompt: String, maxOutputTokens: Int): NanoPromptCall<Int> =
        countResult
}

private enum class ThrowPoint {
    Status,
    StatusCancellation,
    Download,
    DownloadCancellation,
    Generation,
    GenerationCancellation,
}

private class ThrowingNanoPromptGateway(
    private val throwPoint: ThrowPoint,
) : NanoPromptGateway {
    var downloadCalls = 0
        private set

    override suspend fun checkStatus(): NanoPromptCall<NanoPromptStatus> {
        return when (throwPoint) {
            ThrowPoint.Status -> throw IllegalStateException()
            ThrowPoint.StatusCancellation -> throw kotlinx.coroutines.CancellationException()
            ThrowPoint.Download -> NanoPromptCall.Success(NanoPromptStatus.Downloadable)
            ThrowPoint.DownloadCancellation -> NanoPromptCall.Success(NanoPromptStatus.Downloadable)
            else -> NanoPromptCall.Success(NanoPromptStatus.Available)
        }
    }

    override fun download(): Flow<NanoPromptCall<NanoPromptDownloadEvent>> = flow {
        downloadCalls += 1
        when (throwPoint) {
            ThrowPoint.Download -> throw IllegalStateException()
            ThrowPoint.DownloadCancellation -> throw kotlinx.coroutines.CancellationException()
            else -> Unit
        }
    }

    override suspend fun generate(
        prompt: String,
        maxOutputTokens: Int,
    ): NanoPromptCall<NanoPromptResponse> = when (throwPoint) {
        ThrowPoint.Generation -> throw IllegalStateException()
        ThrowPoint.GenerationCancellation -> throw kotlinx.coroutines.CancellationException()
        else -> NanoPromptCall.Success(NanoPromptResponse("feedback", false))
    }

    override suspend fun countTokens(prompt: String, maxOutputTokens: Int): NanoPromptCall<Int> =
        NanoPromptCall.Failure(NanoPromptFailure.Unexpected)
}
