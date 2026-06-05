package io.github.jiro.expensetracker

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jiro.expensetracker.ui.home.HomeScreen
import io.github.jiro.expensetracker.ui.theme.ExpenseTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreen_renders() {
        composeRule.setContent {
            ExpenseTrackerTheme {
                HomeScreen()
            }
        }
        composeRule.waitForIdle()
    }
}
