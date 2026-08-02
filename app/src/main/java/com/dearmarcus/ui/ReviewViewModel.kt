package com.dearmarcus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dearmarcus.core.RefreshInsightsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ReviewViewModel(
    private val dataSource: ReviewDataSource,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = mutableUiState

    init {
        reload()
    }

    fun reload() = launchMutation { snapshot, _ ->
        snapshot.toUiState()
    }

    fun refreshInsights() {
        if (!mutableUiState.value.needsRefresh) return
        launchMutation { snapshot, _ ->
            val result = dataSource.refreshInsights()
            val refreshedSnapshot = dataSource.snapshot()
            val refreshedState = refreshedSnapshot.toUiState()
            refreshedState.copy(statusMessage = result.toMessage(refreshedState.needsRefresh))
        }
    }

    private fun launchMutation(
        mutation: suspend (ReviewSnapshot, ReviewUiState) -> ReviewUiState,
    ) {
        if (mutableUiState.value.isWorking) return
        mutableUiState.value = mutableUiState.value.copy(isWorking = true, statusMessage = null)
        viewModelScope.launch(coroutineDispatcher) {
            try {
                val snapshot = dataSource.snapshot()
                mutableUiState.value = mutation(snapshot, mutableUiState.value)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    isWorking = false,
                    statusMessage = "That action could not finish. Your saved entries remain available.",
                )
            } finally {
                mutableUiState.value = mutableUiState.value.copy(isWorking = false)
            }
        }
    }

    private fun ReviewSnapshot.toUiState(statusMessage: String? = null): ReviewUiState = ReviewUiState(
        latestValidReflection = latestValidReflection,
        hasInvalidDerivedData = hasInvalidDerivedData,
        statusMessage = statusMessage,
    )

    private fun RefreshInsightsResult.toMessage(refreshStillNeeded: Boolean): String = when (this) {
        is RefreshInsightsResult.Completed -> if (!refreshStillNeeded) {
            "Insights refreshed."
        } else {
            "Insights refresh stopped. Your entries remain saved."
        }
        RefreshInsightsResult.NoRefreshRequired -> if (!refreshStillNeeded) {
            "Insights are already current."
        } else {
            "Insights refresh stopped. Your entries remain saved."
        }
        is RefreshInsightsResult.Stopped -> "Insights refresh stopped. Your entries remain saved."
    }
}
