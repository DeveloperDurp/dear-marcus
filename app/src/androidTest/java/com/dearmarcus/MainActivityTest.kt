package com.dearmarcus

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dearmarcus.ui.HistoryViewModel
import com.dearmarcus.ui.ReviewViewModel
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
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
}
