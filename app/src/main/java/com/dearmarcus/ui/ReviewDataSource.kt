package com.dearmarcus.ui

import com.dearmarcus.core.RefreshInsightsResult
import com.dearmarcus.data.JournalRepository
import com.dearmarcus.data.ReflectionRecord

data class ReviewSnapshot(
    val latestValidReflection: ReflectionRecord?,
    val hasInvalidDerivedData: Boolean,
)

interface ReviewDataSource {
    suspend fun snapshot(): ReviewSnapshot

    suspend fun refreshInsights(): RefreshInsightsResult
}

class RepositoryReviewDataSource(
    private val repository: JournalRepository,
    private val refresh: suspend () -> RefreshInsightsResult,
) : ReviewDataSource {
    override suspend fun snapshot(): ReviewSnapshot = ReviewSnapshot(
        latestValidReflection = repository.activeReflection(),
        hasInvalidDerivedData = repository.hasInvalidReflections(),
    )

    override suspend fun refreshInsights(): RefreshInsightsResult = refresh()
}
