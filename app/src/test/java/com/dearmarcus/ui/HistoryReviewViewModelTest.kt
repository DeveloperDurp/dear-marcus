package com.dearmarcus.ui

import com.dearmarcus.core.RefreshInsightsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Test

class HistoryReviewViewModelTest {
    @Test
    fun historyCancellationClearsBusyStateSoTheUserCanRetry() {
        val viewModel = HistoryViewModel(
            dataSource = object : HistoryDataSource {
                override suspend fun entriesNewestFirst(): List<HistoryEntry> =
                    throw CancellationException("foreground work cancelled")

                override suspend fun edit(entryId: String, answers: HistoryAnswers): Boolean = false

                override suspend fun delete(entryId: String): Boolean = false

                override suspend fun clearAll() = Unit
            },
            coroutineDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(viewModel.uiState.value.isWorking)
    }

    @Test
    fun reviewCancellationClearsBusyStateSoTheUserCanRetry() {
        val viewModel = ReviewViewModel(
            dataSource = object : ReviewDataSource {
                override suspend fun snapshot(): ReviewSnapshot =
                    throw CancellationException("foreground work cancelled")

                override suspend fun refreshInsights(): RefreshInsightsResult =
                    RefreshInsightsResult.NoRefreshRequired
            },
            coroutineDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(viewModel.uiState.value.isWorking)
    }
}
