package com.dearmarcus.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dearmarcus.data.JournalEntryRecord
import com.dearmarcus.data.ReflectionRecord
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun seededEntriesAppearNewestFirstAndDetailShowsRawAnswersDatedFeedbackAndStaleLabel() {
        composeRule.setHistoryContent(HistoryUiState(entries = seededEntries(staleFromDay = 2)))

        composeRule.onNodeWithText("Aug 3, 2026 · 18:30").assertIsDisplayed()
        composeRule.onNodeWithTag("history-entry-entry-1").performClick()

        composeRule.onNodeWithText("What went well today?").assertIsDisplayed()
        composeRule.onNodeWithText("well 1").assertIsDisplayed()
        composeRule.onNodeWithText("What went poorly?").assertIsDisplayed()
        composeRule.onNodeWithText("poorly 1").assertIsDisplayed()
        composeRule.onNodeWithText("What would you do differently?").assertIsDisplayed()
        composeRule.onNodeWithText("differently 1").assertIsDisplayed()
        val feedbackTimestamp = Instant.parse("2026-08-01T20:00:00Z")
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .display()
        composeRule.onNodeWithText("Feedback · $feedbackTimestamp").assertIsDisplayed()
        composeRule.onNodeWithText("feedback 1").assertIsDisplayed()
        composeRule.onAllNodesWithText("Insights need refresh").assertCountEquals(0)
    }

    @Test
    fun staleInsightsRemainVisibleButDirectHistoryToSettingsWithoutRefreshActions() {
        composeRule.setHistoryContent(HistoryUiState(entries = seededEntries(staleFromDay = 2)))

        composeRule.onAllNodesWithText("Insights need refresh").assertCountEquals(3)
        composeRule.onNodeWithText("Go to Settings to refresh insights.").assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction().and(hasText("Refresh insights"))).assertCountEquals(0)

        composeRule.onNodeWithTag("history-entry-entry-2").performClick()

        composeRule.onNodeWithText("Insights need refresh").assertIsDisplayed()
        composeRule.onNodeWithText("Go to Settings to refresh insights.").assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction().and(hasText("Refresh insights"))).assertCountEquals(0)
    }

    @Test
    fun cancelingDeleteAndClearPreservesEntriesWhileConfirmationAppliesOnlyTheApprovedScope() {
        composeRule.setHistoryContent(
            HistoryUiState(entries = seededEntries()),
            reduce = { state, event -> event.reduce(state) },
        )

        composeRule.onNodeWithTag("history-entry-entry-2").performClick()
        composeRule.onNodeWithText("Delete entry").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("well 2").assertIsDisplayed()

        composeRule.onNodeWithText("Back to history").performClick()
        composeRule.onNodeWithText("Clear all local data").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Aug 3, 2026 · 18:30").assertIsDisplayed()

        composeRule.onNodeWithTag("history-entry-entry-2").performClick()
        composeRule.onNodeWithText("Delete entry").performClick()
        composeRule.onNodeWithText("This permanently removes this local entry and its feedback. Later insights need refresh.")
            .assertIsDisplayed()
        composeRule.onNode(
            hasText("Delete entry").and(hasAnyAncestor(isDialog())),
        ).performClick()
        composeRule.onAllNodesWithText("well 2").assertCountEquals(0)
        composeRule.onNodeWithText("well 1").assertIsDisplayed()
    }

    @Test
    fun historyRowsHaveClearButtonSemanticsAndRemainDiscoverableAtOnePointFiveFontScale() {
        composeRule.setHistoryContent(
            initialState = HistoryUiState(entries = seededEntries()),
            fontScale = 1.5f,
        )

        composeRule.onNodeWithTag("history-entry-entry-3")
            .assert(hasClickAction())
            .assert(hasContentDescription("Open entry from Aug 3, 2026 · 18:30"))
            .performClick()

        composeRule.onNodeWithText("What went well today?").assertIsDisplayed()
    }

    @Test
    fun confirmedClearAllRemovesTheSeededEntriesAndOnlyAppearsAfterConfirmation() {
        composeRule.setHistoryContent(
            HistoryUiState(entries = seededEntries()),
            reduce = { state, event -> event.reduce(state) },
        )

        composeRule.onNodeWithText("Clear all local data").performClick()
        composeRule.onNodeWithText("Clear all local data?").assertIsDisplayed()
        composeRule.onNodeWithText("Clear all").performClick()

        composeRule.onNodeWithText("No saved entries yet.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Clear all local data").assertCountEquals(0)
    }

    @Test
    fun historyDoesNotExposeSettingsBackupActions() {
        composeRule.setHistoryContent(HistoryUiState())

        composeRule.onAllNodesWithTag(SettingsTestTags.EXPORT_BACKUP).assertCountEquals(0)
        composeRule.onAllNodesWithTag(SettingsTestTags.IMPORT_BACKUP).assertCountEquals(0)
        composeRule.onAllNodesWithText("Export local backup").assertCountEquals(0)
        composeRule.onAllNodesWithText("Import local backup").assertCountEquals(0)
    }

    @Test
    fun backToHistoryIsTheOnlyFooterActionAndReturnsToTheListAfterScrolling() {
        composeRule.setHistoryContent(HistoryUiState(entries = seededEntries()))

        composeRule.onNodeWithTag("history-entry-entry-1").performClick()

        composeRule.onAllNodesWithText("Back to history").assertCountEquals(1)
        composeRule.onNodeWithText("Back to history").performScrollTo().assertIsDisplayed().performClick()

        composeRule.onNodeWithText("Aug 3, 2026 · 18:30").assertIsDisplayed()
        composeRule.onAllNodesWithText("Back to history").assertCountEquals(0)
    }

    @Test
    fun backToHistoryFromEditModeClearsEditControlsAndReturnsToTheList() {
        composeRule.setHistoryContent(HistoryUiState(entries = seededEntries()))

        composeRule.onNodeWithTag("history-entry-entry-1").performClick()
        composeRule.onNodeWithText("Edit entry").performClick()
        composeRule.onNodeWithText("Back to history").performScrollTo().performClick()

        composeRule.onNodeWithText("Aug 3, 2026 · 18:30").assertIsDisplayed()
        composeRule.onAllNodesWithText("Save changes").assertCountEquals(0)
        composeRule.onAllNodesWithText("Cancel edit").assertCountEquals(0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setHistoryContent(
        initialState: HistoryUiState,
        reduce: (HistoryUiState, HistoryEvent) -> HistoryUiState = { state, event -> event.reduce(state) },
        fontScale: Float = 1f,
    ) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                var state by remember { mutableStateOf(initialState) }
                HistoryScreen(
                    state = state,
                    onSelect = { state = state.copy(selectedEntryId = it) },
                    onCloseDetail = { state = state.copy(selectedEntryId = null, edit = null) },
                    onBeginEdit = {
                        val entry = requireNotNull(state.selectedEntry).entry
                        state = state.copy(edit = HistoryEditState(entry.id, entry.wentWell, entry.wentPoorly, entry.doDifferently))
                    },
                    onEditChanged = { _, _ -> },
                    onSaveEdit = { state = reduce(state, HistoryEvent.SaveEdit) },
                    onCancelEdit = { state = state.copy(edit = null) },
                    onRequestDelete = { state = state.copy(confirmation = HistoryConfirmation.Delete(requireNotNull(state.selectedEntryId))) },
                    onRequestClearAll = { state = state.copy(confirmation = HistoryConfirmation.ClearAll) },
                    onDismissConfirmation = { state = state.copy(confirmation = null) },
                    onConfirmDestructiveAction = { state = reduce(state, HistoryEvent.ConfirmDestructiveAction) },
                )
            }
        }
    }

    private fun seededEntries(staleFromDay: Int? = null): List<HistoryEntry> = (1..3).reversed().map { day ->
        HistoryEntry(
            entry = JournalEntryRecord(
                id = "entry-$day",
                localDateTime = LocalDateTime.of(2026, 8, day, 18, 30),
                wentWell = "well $day",
                wentPoorly = "poorly $day",
                doDifferently = "differently $day",
                updatedAt = Instant.parse("2026-08-0${day}T18:30:00Z"),
            ),
            reflection = ReflectionRecord(
                entryId = "entry-$day",
                feedback = "feedback $day",
                memoryBefore = "",
                memoryAfter = "memory $day",
                memoryRevision = day,
                generatedAt = Instant.parse("2026-08-0${day}T20:00:00Z"),
                isValid = staleFromDay?.let { day >= it } != true,
            ),
        )
    }

    private enum class HistoryEvent {
        SaveEdit,
        ConfirmDestructiveAction,
        ;

        fun reduce(state: HistoryUiState): HistoryUiState = when (this) {
            SaveEdit -> state.copy(edit = null)
            ConfirmDestructiveAction -> when (val confirmation = state.confirmation) {
                is HistoryConfirmation.Delete -> state.copy(
                    entries = state.entries.filterNot { it.entry.id == confirmation.entryId },
                    selectedEntryId = null,
                    confirmation = null,
                )
                HistoryConfirmation.ClearAll -> state.copy(entries = emptyList(), confirmation = null)
                null -> state
            }
        }
    }
}
