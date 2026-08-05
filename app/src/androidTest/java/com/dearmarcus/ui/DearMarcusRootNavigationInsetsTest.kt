package com.dearmarcus.ui

import android.view.WindowInsets
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dearmarcus.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DearMarcusRootNavigationInsetsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomTabRow_staysAboveNavigationBar_andNavigatesAllDestinations() {
        val navigationInset = composeRule.activity.window.decorView.rootWindowInsets
            .getInsets(WindowInsets.Type.navigationBars())
            .bottom
        val tabRow = composeRule.onNodeWithTag("bottom-tab-row")

        assertTrue("Expected a nonzero navigation-bar inset.", navigationInset > 0)
        assertTrue(
            "Expected the bottom tab row to stay above the navigation-bar safe boundary.",
            tabRow.fetchSemanticsNode().boundsInRoot.bottom <=
                composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom - navigationInset,
        )

        assertDestinationSelected("Daily")
        selectDestination("History")
        selectDestination("Review")
        selectDestination("Settings")
        selectDestination("Daily")
    }

    private fun selectDestination(label: String) {
        composeRule.onNode(hasText(label).and(hasClickAction())).performClick()
        assertDestinationSelected(label)
    }

    private fun assertDestinationSelected(label: String) {
        composeRule.onNode(hasText(label).and(hasClickAction())).assertIsSelected()
    }
}
