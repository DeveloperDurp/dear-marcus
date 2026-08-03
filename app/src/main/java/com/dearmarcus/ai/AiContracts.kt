package com.dearmarcus.ai

import com.dearmarcus.core.AiStatus
import kotlinx.coroutines.flow.Flow

sealed interface AiReadiness {
    val status: AiStatus

    data object Available : AiReadiness {
        override val status = AiStatus.AVAILABLE
    }

    data object Downloadable : AiReadiness {
        override val status = AiStatus.DOWNLOADABLE
    }

    data object Downloading : AiReadiness {
        override val status = AiStatus.DOWNLOADING
    }

    data object Unavailable : AiReadiness {
        override val status = AiStatus.UNAVAILABLE
    }

    data class Error(val failure: AiFailure) : AiReadiness {
        override val status = failure.status
    }
}

sealed interface AiFailure {
    val status: AiStatus

    data object TokenLimit : AiFailure {
        override val status = AiStatus.TOKEN_LIMIT
    }

    data object Busy : AiFailure {
        override val status = AiStatus.BUSY
    }

    data object Quota : AiFailure {
        override val status = AiStatus.QUOTA_EXCEEDED
    }

    data object BackgroundBlocked : AiFailure {
        override val status = AiStatus.BACKGROUND_BLOCKED
    }

    data object InvalidOutput : AiFailure {
        override val status = AiStatus.INVALID_OUTPUT
    }

    data object SetupRequired : AiFailure {
        override val status = AiStatus.UNEXPECTED_ERROR
    }

    data object Unexpected : AiFailure {
        override val status = AiStatus.UNEXPECTED_ERROR
    }
}

sealed interface AiGenerationResult {
    data class Success(val text: String) : AiGenerationResult

    data class NotReady(val readiness: AiReadiness) : AiGenerationResult

    data class Failure(val failure: AiFailure) : AiGenerationResult
}

sealed interface AiDownloadState {
    data object Started : AiDownloadState

    data object Downloading : AiDownloadState

    data object Completed : AiDownloadState

    data class Failed(val failure: AiFailure) : AiDownloadState

    data class NotDownloadable(val readiness: AiReadiness) : AiDownloadState
}

enum class AiUserAction {
    None,
    Generate,
    DownloadOnDeviceModel,
    RetryInForeground,
    EditEntry,
}

data class AiUserGuidance(
    val message: String,
    val action: AiUserAction,
    val generationEnabled: Boolean,
)

object AiUserGuidanceMapper {
    fun forReadiness(readiness: AiReadiness): AiUserGuidance = when (readiness) {
        AiReadiness.Available -> AiUserGuidance(
            message = "On-device AI is ready.",
            action = AiUserAction.Generate,
            generationEnabled = true,
        )
        AiReadiness.Downloadable -> AiUserGuidance(
            message = "Download the on-device model to create feedback.",
            action = AiUserAction.DownloadOnDeviceModel,
            generationEnabled = false,
        )
        AiReadiness.Downloading -> AiUserGuidance(
            message = "The on-device model is downloading.",
            action = AiUserAction.None,
            generationEnabled = false,
        )
        AiReadiness.Unavailable -> AiUserGuidance(
            message = "On-device AI is unavailable on this device. Your entry will still be saved.",
            action = AiUserAction.None,
            generationEnabled = false,
        )
        is AiReadiness.Error -> forFailure(readiness.failure)
    }

    fun forFailure(failure: AiFailure): AiUserGuidance = when (failure) {
        AiFailure.TokenLimit -> AiUserGuidance(
            message = "This reflection is too large to process on-device.",
            action = AiUserAction.EditEntry,
            generationEnabled = false,
        )
        AiFailure.Busy -> AiUserGuidance(
            message = "On-device AI is busy. Try again from the app shortly.",
            action = AiUserAction.RetryInForeground,
            generationEnabled = false,
        )
        AiFailure.Quota -> AiUserGuidance(
            message = "On-device AI has reached its usage limit. Try again later from the app.",
            action = AiUserAction.RetryInForeground,
            generationEnabled = false,
        )
        AiFailure.BackgroundBlocked -> AiUserGuidance(
            message = "On-device AI only runs while the app is open. Try again from the app.",
            action = AiUserAction.RetryInForeground,
            generationEnabled = false,
        )
        AiFailure.InvalidOutput -> AiUserGuidance(
            message = "On-device AI returned an incomplete response. Try again from the app.",
            action = AiUserAction.RetryInForeground,
            generationEnabled = false,
        )
        AiFailure.SetupRequired -> AiUserGuidance(
            message = "Set up or update on-device AI, then try again from the app.",
            action = AiUserAction.RetryInForeground,
            generationEnabled = false,
        )
        AiFailure.Unexpected -> AiUserGuidance(
            message = "On-device AI could not create feedback. Try again from the app.",
            action = AiUserAction.RetryInForeground,
            generationEnabled = false,
        )
    }
}

interface OnDeviceAiClient {
    suspend fun checkAvailability(): AiReadiness

    /** Call only from a direct foreground user action. */
    fun startUserInitiatedDownload(): Flow<AiDownloadState>

    suspend fun generate(prompt: String): AiGenerationResult
}

internal fun NanoPromptCall<NanoPromptStatus>.toReadiness(): AiReadiness = when (this) {
    is NanoPromptCall.Success -> when (value) {
        NanoPromptStatus.Available -> AiReadiness.Available
        NanoPromptStatus.Downloadable -> AiReadiness.Downloadable
        NanoPromptStatus.Downloading -> AiReadiness.Downloading
        NanoPromptStatus.Unavailable -> AiReadiness.Unavailable
        NanoPromptStatus.Unknown -> AiReadiness.Error(AiFailure.Unexpected)
    }
    is NanoPromptCall.Failure -> AiReadiness.Error(
        when (failure) {
            NanoPromptFailure.Unexpected -> AiFailure.SetupRequired
            else -> failure.toAiFailure()
        },
    )
}
