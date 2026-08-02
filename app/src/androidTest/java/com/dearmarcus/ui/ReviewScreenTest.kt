package com.dearmarcus.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dearmarcus.data.ReflectionRecord
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ReviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun seededV3DisplaysOnlyTheCurrentV3MemoryFeedbackAndTimestamp() {
        composeRule.setReviewContent(
            ReviewUiState(latestValidReflection = reflection(revision = 3)),
        )

        composeRule.onNodeWithText("memory v3").assertIsDisplayed()
        composeRule.onNodeWithText("feedback v3").assertIsDisplayed()
        composeRule.onNodeWithText("Latest feedback ·", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("memory v2").assertCountEquals(0)
        composeRule.onAllNodesWithText("Refresh Insights").assertCountEquals(0)
    }

    @Test
    fun allInvalidDerivedDataHasNoCurrentMemoryAndOffersForegroundRefresh() {
        var refreshCalls = 0
        composeRule.setReviewContent(
            ReviewUiState(hasInvalidDerivedData = true),
            onRefreshInsights = { refreshCalls++ },
        )

        composeRule.onNodeWithText("No valid review yet").assertIsDisplayed()
        composeRule.onNodeWithText("No valid local review is available yet.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Current condensed memory").assertCountEquals(0)
        composeRule.onAllNodesWithText("Latest feedback ·", substring = true).assertCountEquals(0)
        composeRule.onNodeWithTag(ReviewTestTags.REFRESH).performClick()

        check(refreshCalls == 1)
    }

    @Test
    fun invalidatedV3ShowsOnlyTheLastValidV2ReviewAndRefreshAffordance() {
        composeRule.setReviewContent(
            ReviewUiState(
                latestValidReflection = reflection(revision = 2),
                hasInvalidDerivedData = true,
            ),
        )

        composeRule.onNodeWithText("memory v2").assertIsDisplayed()
        composeRule.onNodeWithText("feedback v2").assertIsDisplayed()
        composeRule.onAllNodesWithText("memory v3").assertCountEquals(0)
        composeRule.onNodeWithText("Showing the last valid local review.").assertIsDisplayed()
        composeRule.onNodeWithTag(ReviewTestTags.REFRESH).assertIsDisplayed()
    }

    @Test
    fun malformedDerivedDataIsNotPresentedAsCurrentOrEligibleForRefresh() {
        composeRule.setReviewContent(
            ReviewUiState(
                latestValidReflection = reflection(revision = 3).copy(memoryAfter = ""),
            ),
        )

        composeRule.onAllNodesWithText("Current condensed memory").assertCountEquals(0)
        composeRule.onAllNodesWithText("Latest feedback ·", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("No valid review yet").assertIsDisplayed()
        composeRule.onAllNodesWithText("Refresh Insights").assertCountEquals(0)
    }

    @Test
    fun staleReviewRemainsDiscoverableAtOnePointFiveFontScale() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                ReviewScreen(
                    state = ReviewUiState(
                        latestValidReflection = reflection(revision = 2),
                        hasInvalidDerivedData = true,
                    ),
                    onRefreshInsights = {},
                )
            }
        }

        composeRule.onNodeWithText("Current condensed memory").assertIsDisplayed()
        composeRule.onNodeWithText("memory v2").assertIsDisplayed()
        composeRule.onNodeWithTag(ReviewTestTags.REFRESH).assertIsDisplayed()
    }

    @Test
    fun refreshIsDisabledWhileForegroundRefreshIsWorking() {
        composeRule.setReviewContent(
            ReviewUiState(hasInvalidDerivedData = true, isWorking = true),
        )

        composeRule.onNodeWithTag(ReviewTestTags.REFRESH).assertIsNotEnabled()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setReviewContent(
        state: ReviewUiState,
        onRefreshInsights: () -> Unit = {},
    ) {
        setContent {
            ReviewScreen(state = state, onRefreshInsights = onRefreshInsights)
        }
    }

    private fun reflection(revision: Int): ReflectionRecord = ReflectionRecord(
        entryId = "entry-$revision",
        feedback = "feedback v$revision",
        memoryBefore = "memory v${revision - 1}",
        memoryAfter = "memory v$revision",
        memoryRevision = revision,
        generatedAt = Instant.parse("2026-08-0${revision}T20:00:00Z"),
    )
}
