package com.dearmarcus.ui

import com.dearmarcus.core.AiStatus
import com.dearmarcus.core.Reflection
import com.dearmarcus.data.ReflectionRecord

data class ReviewUiState(
    val latestValidReflection: ReflectionRecord? = null,
    val hasInvalidDerivedData: Boolean = false,
    val isWorking: Boolean = false,
    val statusMessage: String? = null,
) {
    val currentReflection: ReflectionRecord?
        get() = latestValidReflection?.takeIf { it.isDisplayable() }

    val needsRefresh: Boolean
        get() = hasInvalidDerivedData
}

private fun ReflectionRecord.isDisplayable(): Boolean =
    isValid &&
        aiStatus == AiStatus.AVAILABLE.name &&
        feedback.isNotBlank() &&
        memoryAfter.isNotBlank() &&
        feedback.codePointCount(0, feedback.length) <= Reflection.MAXIMUM_FEEDBACK_CODE_POINTS &&
        memoryAfter.codePointCount(0, memoryAfter.length) <= Reflection.MAXIMUM_MEMORY_CODE_POINTS

object ReviewTestTags {
    const val SCREEN = "review-screen"
    const val CURRENT_MEMORY = "review-current-memory"
    const val LATEST_FEEDBACK = "review-latest-feedback"
    const val STALE_NOTICE = "review-stale-notice"
    const val REFRESH = "review-refresh"
}
