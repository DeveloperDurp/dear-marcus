package com.dearmarcus.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MlKitJournalAiClient private constructor(
    private val gateway: NanoPromptGateway,
    private val maxOutputTokens: Int,
) : OnDeviceAiClient {
    constructor() : this(
        gateway = MlKitPromptGateway(),
        maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
    )

    init {
        require(maxOutputTokens in 1..MAX_SDK_OUTPUT_TOKENS)
    }

    override suspend fun checkAvailability(): AiReadiness = try {
        gateway.checkStatus().toReadiness()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        AiReadiness.Error(AiFailure.Unexpected)
    }

    override fun startUserInitiatedDownload(): Flow<AiDownloadState> = flow {
        when (val readiness = checkAvailability()) {
            AiReadiness.Downloadable -> {
                try {
                    gateway.download().collect { event ->
                        emit(event.toDownloadState())
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emit(AiDownloadState.Failed(AiFailure.Unexpected))
                }
            }
            else -> emit(AiDownloadState.NotDownloadable(readiness))
        }
    }

    override suspend fun generate(prompt: String): AiGenerationResult = when (
        val readiness = checkAvailability()
    ) {
        AiReadiness.Available -> {
            try {
                gateway.generate(prompt, maxOutputTokens).toGenerationResult()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                AiGenerationResult.Failure(AiFailure.Unexpected)
            }
        }
        else -> AiGenerationResult.NotReady(readiness)
    }

    /** Returns ML Kit's exact count for the same request sent to [generate], or null if unavailable. */
    suspend fun countInputTokens(prompt: String): Int? = when (checkAvailability()) {
        AiReadiness.Available -> {
            try {
                when (val result = gateway.countTokens(prompt, maxOutputTokens)) {
                    is NanoPromptCall.Success -> result.value.takeIf { it >= 0 }
                    is NanoPromptCall.Failure -> null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        else -> null
    }

    companion object {
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 1_024
        private const val MAX_SDK_OUTPUT_TOKENS = 4_096

        internal fun forGateway(
            gateway: NanoPromptGateway,
            maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
        ): MlKitJournalAiClient = MlKitJournalAiClient(gateway, maxOutputTokens)
    }
}

internal interface NanoPromptGateway {
    suspend fun checkStatus(): NanoPromptCall<NanoPromptStatus>

    fun download(): Flow<NanoPromptCall<NanoPromptDownloadEvent>>

    suspend fun generate(
        prompt: String,
        maxOutputTokens: Int,
    ): NanoPromptCall<NanoPromptResponse>

    suspend fun countTokens(
        prompt: String,
        maxOutputTokens: Int,
    ): NanoPromptCall<Int> = NanoPromptCall.Failure(NanoPromptFailure.Unexpected)
}

internal sealed interface NanoPromptCall<out T> {
    data class Success<T>(val value: T) : NanoPromptCall<T>

    data class Failure(val failure: NanoPromptFailure) : NanoPromptCall<Nothing>
}

internal enum class NanoPromptStatus {
    Available,
    Downloadable,
    Downloading,
    Unavailable,
    Unknown,
}

internal enum class NanoPromptFailure {
    TokenLimit,
    Busy,
    Quota,
    BackgroundBlocked,
    Unexpected,
}

internal sealed interface NanoPromptDownloadEvent {
    data object Started : NanoPromptDownloadEvent

    data object Downloading : NanoPromptDownloadEvent

    data object Completed : NanoPromptDownloadEvent
}

internal data class NanoPromptResponse(
    val text: String,
    val reachedTokenLimit: Boolean,
)

private class MlKitPromptGateway(
    private val modelFactory: () -> GenerativeModel = { Generation.getClient() },
) : NanoPromptGateway {
    private val generativeModel: GenerativeModel by lazy(modelFactory)

    override suspend fun checkStatus(): NanoPromptCall<NanoPromptStatus> = try {
        NanoPromptCall.Success(generativeModel.checkStatus().toNanoPromptStatus())
    } catch (error: CancellationException) {
        throw error
    } catch (error: GenAiException) {
        error.rethrowIfSdkCancellation()
        NanoPromptCall.Failure(error.toNanoPromptFailure())
    } catch (_: Exception) {
        NanoPromptCall.Failure(NanoPromptFailure.Unexpected)
    }

    override fun download(): Flow<NanoPromptCall<NanoPromptDownloadEvent>> = flow {
        try {
            generativeModel.download().collect { status ->
                emit(status.toNanoPromptDownloadCall())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: GenAiException) {
            error.rethrowIfSdkCancellation()
            emit(NanoPromptCall.Failure(error.toNanoPromptFailure()))
        } catch (_: Exception) {
            emit(NanoPromptCall.Failure(NanoPromptFailure.Unexpected))
        }
    }

    override suspend fun generate(
        prompt: String,
        maxOutputTokens: Int,
    ): NanoPromptCall<NanoPromptResponse> = try {
        val response = generativeModel.generateContent(request(prompt, maxOutputTokens))
        val candidate = response.candidates.firstOrNull()
        NanoPromptCall.Success(
            NanoPromptResponse(
                text = candidate?.text.orEmpty(),
                reachedTokenLimit = candidate?.finishReason == Candidate.FinishReason.MAX_TOKENS,
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: GenAiException) {
        error.rethrowIfSdkCancellation()
        NanoPromptCall.Failure(error.toNanoPromptFailure())
    } catch (_: Exception) {
        NanoPromptCall.Failure(NanoPromptFailure.Unexpected)
    }

    override suspend fun countTokens(
        prompt: String,
        maxOutputTokens: Int,
    ): NanoPromptCall<Int> = try {
        NanoPromptCall.Success(
            generativeModel.countTokens(request(prompt, maxOutputTokens)).totalTokens,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: GenAiException) {
        error.rethrowIfSdkCancellation()
        NanoPromptCall.Failure(error.toNanoPromptFailure())
    } catch (_: Exception) {
        NanoPromptCall.Failure(NanoPromptFailure.Unexpected)
    }

    private fun request(prompt: String, maxOutputTokens: Int) =
        generateContentRequest(TextPart(prompt)) {
            temperature = 0.2f
            candidateCount = 1
            this.maxOutputTokens = maxOutputTokens
        }
}

private fun NanoPromptCall<NanoPromptStatus>.toReadiness(): AiReadiness = when (this) {
    is NanoPromptCall.Success -> when (value) {
        NanoPromptStatus.Available -> AiReadiness.Available
        NanoPromptStatus.Downloadable -> AiReadiness.Downloadable
        NanoPromptStatus.Downloading -> AiReadiness.Downloading
        NanoPromptStatus.Unavailable -> AiReadiness.Unavailable
        NanoPromptStatus.Unknown -> AiReadiness.Error(AiFailure.Unexpected)
    }
    is NanoPromptCall.Failure -> AiReadiness.Error(failure.toAiFailure())
}

private fun NanoPromptCall<NanoPromptResponse>.toGenerationResult(): AiGenerationResult = when (this) {
    is NanoPromptCall.Success -> when {
        value.reachedTokenLimit -> AiGenerationResult.Failure(AiFailure.TokenLimit)
        value.text.isBlank() -> AiGenerationResult.Failure(AiFailure.InvalidOutput)
        else -> AiGenerationResult.Success(value.text)
    }
    is NanoPromptCall.Failure -> AiGenerationResult.Failure(failure.toAiFailure())
}

private fun NanoPromptCall<NanoPromptDownloadEvent>.toDownloadState(): AiDownloadState = when (this) {
    is NanoPromptCall.Success -> when (value) {
        NanoPromptDownloadEvent.Started -> AiDownloadState.Started
        NanoPromptDownloadEvent.Downloading -> AiDownloadState.Downloading
        NanoPromptDownloadEvent.Completed -> AiDownloadState.Completed
    }
    is NanoPromptCall.Failure -> AiDownloadState.Failed(failure.toAiFailure())
}

private fun NanoPromptFailure.toAiFailure(): AiFailure = when (this) {
    NanoPromptFailure.TokenLimit -> AiFailure.TokenLimit
    NanoPromptFailure.Busy -> AiFailure.Busy
    NanoPromptFailure.Quota -> AiFailure.Quota
    NanoPromptFailure.BackgroundBlocked -> AiFailure.BackgroundBlocked
    NanoPromptFailure.Unexpected -> AiFailure.Unexpected
}

private fun Int.toNanoPromptStatus(): NanoPromptStatus = when (this) {
    FeatureStatus.AVAILABLE -> NanoPromptStatus.Available
    FeatureStatus.DOWNLOADABLE -> NanoPromptStatus.Downloadable
    FeatureStatus.DOWNLOADING -> NanoPromptStatus.Downloading
    FeatureStatus.UNAVAILABLE -> NanoPromptStatus.Unavailable
    else -> NanoPromptStatus.Unknown
}

private fun DownloadStatus.toNanoPromptDownloadCall(): NanoPromptCall<NanoPromptDownloadEvent> = when (this) {
    is DownloadStatus.DownloadStarted -> NanoPromptCall.Success(NanoPromptDownloadEvent.Started)
    is DownloadStatus.DownloadProgress -> NanoPromptCall.Success(NanoPromptDownloadEvent.Downloading)
    is DownloadStatus.DownloadCompleted -> NanoPromptCall.Success(NanoPromptDownloadEvent.Completed)
    is DownloadStatus.DownloadFailed -> NanoPromptCall.Failure(e.toNanoPromptFailure())
}

private fun GenAiException.toNanoPromptFailure(): NanoPromptFailure = when (errorCode) {
    GenAiException.ErrorCode.REQUEST_TOO_LARGE -> NanoPromptFailure.TokenLimit
    GenAiException.ErrorCode.BUSY -> NanoPromptFailure.Busy
    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> NanoPromptFailure.Quota
    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> NanoPromptFailure.BackgroundBlocked
    else -> NanoPromptFailure.Unexpected
}

private fun GenAiException.rethrowIfSdkCancellation() {
    if (errorCode == GenAiException.ErrorCode.CANCELLED) {
        throw CancellationException("ML Kit generation cancelled").also { it.initCause(this) }
    }
}
