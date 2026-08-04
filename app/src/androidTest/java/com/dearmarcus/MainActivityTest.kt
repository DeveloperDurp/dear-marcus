package com.dearmarcus

import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dearmarcus.data.JournalDatabase
import com.dearmarcus.data.JournalRepository
import com.dearmarcus.ui.DailyEntryTestTags
import com.dearmarcus.ui.DailyEntryViewModel
import com.dearmarcus.ui.DailySubmissionState
import com.dearmarcus.ui.HistoryTestTags
import com.dearmarcus.ui.HistoryViewModel
import com.dearmarcus.ui.ReviewViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule(order = 0)
    val softImeRule = TestRule { base, description ->
        if (description.methodName != REAL_IME_TEST) {
            base
        } else {
            object : Statement() {
                override fun evaluate() {
                    val originalShowImeWithHardwareKeyboard = shell(
                        "settings get secure show_ime_with_hard_keyboard",
                    )
                    try {
                        shell("settings put secure show_ime_with_hard_keyboard 1")
                        base.evaluate()
                    } finally {
                        restoreShowImeWithHardwareKeyboard(originalShowImeWithHardwareKeyboard)
                    }
                }
            }
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dailyEntryRoot_isDisplayedOnLaunch() {
        composeRule.onNodeWithText("What went well today?").assertIsDisplayed()
    }

    @Test
    fun lightTheme_usesDarkStatusBarIcons() {
        composeRule.runOnIdle {
            assertTrue(
                WindowCompat.getInsetsController(
                    composeRule.activity.window,
                    composeRule.activity.window.decorView,
                ).isAppearanceLightStatusBars,
            )
        }
    }

    @Test
    fun historyAndReviewViewModelsAreRetainedAcrossActivityRecreation() {
        lateinit var history: HistoryViewModel
        lateinit var review: ReviewViewModel
        composeRule.runOnIdle {
            history = ViewModelProvider(composeRule.activity)[HistoryViewModel::class.java]
            review = ViewModelProvider(composeRule.activity)[ReviewViewModel::class.java]
        }

        composeRule.activityRule.scenario.recreate()

        composeRule.runOnIdle {
            assertSame(history, ViewModelProvider(composeRule.activity)[HistoryViewModel::class.java])
            assertSame(review, ViewModelProvider(composeRule.activity)[ReviewViewModel::class.java])
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun focusedThirdAnswerRemainsAboveTheImeInTheShippedActivity() {
        val imeInset = AtomicInteger()
        assertTrue(
            "Expected the test device to enable its soft IME.",
            shell("settings get secure show_ime_with_hard_keyboard") == "1",
        )
        assertTrue(
            "Expected MainActivity to resize for the soft IME.",
            composeRule.activity.window.attributes.softInputMode and
                WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST ==
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )

        val thirdAnswer = composeRule.onNodeWithTag(DailyEntryTestTags.DO_DIFFERENTLY)
        thirdAnswer.performScrollTo()
        thirdAnswer.performClick()
        composeRule.waitUntil(IME_TIMEOUT_MILLIS) {
            runCatching {
                thirdAnswer.assertIsFocused()
                true
            }.getOrDefault(false)
        }
        thirdAnswer.assertIsFocused()

        composeRule.waitUntil(IME_TIMEOUT_MILLIS) {
            currentImeBottom().also(imeInset::set) > 0
        }
        composeRule.waitUntil(IME_TIMEOUT_MILLIS) {
            thirdAnswer.fetchSemanticsNode().boundsInRoot.bottom <=
                composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom - imeInset.get()
        }

        assertTrue(
            "Expected the focused third answer above the IME.",
            thirdAnswer.fetchSemanticsNode().boundsInRoot.bottom <=
                composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom - imeInset.get(),
        )
    }

    @Test
    fun systemBackFromHistoryListWithStaleSelectionFinishesTheActivity() {
        val activity = composeRule.activity
        composeRule.runOnIdle {
            ViewModelProvider(activity)[HistoryViewModel::class.java].select("missing-entry")
        }
        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithTag(HistoryTestTags.LIST).assertIsDisplayed()

        pressBackUnconditionally()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertTrue("Expected Back from the rendered History list to finish the activity.", activity.isFinishing)
    }

    @Test
    fun systemBackFromHistoryDetail_restoresThePersistedHistoryRow() {
        val marker = "History Back entry ${System.nanoTime()}"

        try {
            composeRule.onNodeWithTag(DailyEntryTestTags.WENT_WELL).performTextClearance()
            composeRule.onNodeWithTag(DailyEntryTestTags.WENT_WELL).performTextInput(marker)
            composeRule.onNodeWithTag(DailyEntryTestTags.WENT_POORLY).performTextClearance()
            composeRule.onNodeWithTag(DailyEntryTestTags.WENT_POORLY).performTextInput("I rushed.")
            composeRule.onNodeWithTag(DailyEntryTestTags.DO_DIFFERENTLY).performTextClearance()
            composeRule.onNodeWithTag(DailyEntryTestTags.DO_DIFFERENTLY).performTextInput("I will pause.")
            composeRule.onNodeWithText("Save entry").performScrollTo().performClick()

            lateinit var daily: DailyEntryViewModel
            composeRule.runOnIdle {
                daily = ViewModelProvider(composeRule.activity)[DailyEntryViewModel::class.java]
            }
            composeRule.waitUntil(timeoutMillis = 10_000) {
                daily.uiState.value.submission.let { submission ->
                    submission is DailySubmissionState.Reflected ||
                        submission is DailySubmissionState.SavedWithoutReflection
                }
            }

            composeRule.onNodeWithText("History").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText(marker).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(HistoryTestTags.LIST).assertIsDisplayed()
            composeRule.onNodeWithText(marker).performClick()
            composeRule.onNodeWithTag(HistoryTestTags.DETAIL).assertIsDisplayed()

            pressBack()

            composeRule.onNodeWithTag(HistoryTestTags.LIST).assertIsDisplayed()
            composeRule.onNodeWithText(marker).assertIsDisplayed()
            composeRule.onAllNodesWithText("Back to history").assertCountEquals(0)
        } finally {
            deleteTestEntry(marker)
        }
    }

    private fun currentImeBottom(): Int = ViewCompat.getRootWindowInsets(
        composeRule.activity.window.decorView,
    )?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText().trim() }

    private fun restoreShowImeWithHardwareKeyboard(value: String) {
        shell(
            if (value == "null" || value.isEmpty()) {
                "settings delete secure show_ime_with_hard_keyboard"
            } else {
                "settings put secure show_ime_with_hard_keyboard $value"
            },
        )
    }

    private companion object {
        const val IME_TIMEOUT_MILLIS = 5_000L
        const val REAL_IME_TEST = "focusedThirdAnswerRemainsAboveTheImeInTheShippedActivity"
    }

    private fun deleteTestEntry(marker: String) = runBlocking {
        val database = JournalDatabase.create(composeRule.activity.applicationContext)
        try {
            val repository = JournalRepository(database)
            repository.entries()
                .singleOrNull { it.wentWell == marker }
                ?.let { repository.deleteEntry(it.id) }
        } finally {
            database.close()
        }
    }
}
