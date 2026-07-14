package io.github.jiro.expensetracker.ui.statistics

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Regression test for the "Insights tab crashes the app" bug.
 *
 * Bug: StatisticsContent's HorizontalPager content computed
 * `rangesByTab.getValue(tab)` for every page, including INSIGHTS which is
 * not in the map. That threw NoSuchElementException when the user clicked
 * the Insights tab.
 *
 * Fix: rangesByTab.getValue(tab) is now only called inside the
 * TOP_CATS / SAVINGS / PATTERNS / YOY branches that actually need it.
 *
 * This test renders StatisticsContent in isolation, clicks the Insights
 * tab, and asserts that the empty-state message is displayed. Before the
 * fix, the performClick() would propagate the NoSuchElementException
 * through Compose and fail the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = StatisticsTabCrashTestApp::class)
class StatisticsContentTabTest {

    @get:Rule(order = 1)
    val addActivityRule = object : TestWatcher() {
        override fun starting(description: Description) {
            super.starting(description)
            val app: Application = ApplicationProvider.getApplicationContext()
            Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                ComponentName(app.packageName, ComponentActivity::class.java.name),
            )
        }
    }

    @get:Rule(order = 2) val composeTestRule = createComposeRule()

    @Test
    fun clickingInsightsTab_rendersEmptyStateWithoutCrashing() {
        composeTestRule.setContent {
            StatisticsContentHarness(
                yoy = YearOverYear(
                    currentWindowLabel = "Current",
                    previousWindowLabel = "Previous",
                    currentExpenseMinor = 0L,
                    previousExpenseMinor = 0L,
                    percentChange = 0f,
                    isNewSpending = false,
                ),
            )
        }

        composeTestRule.onNodeWithText("Insights", useUnmergedTree = true).performClick()

        composeTestRule.onNodeWithText(
            "Not enough data yet — log transactions to see insights",
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    /**
     * Sanity-check the YOY branch after the Insights-crash fix: clicking YOY
     * should compose YoyTab without throwing `rangesByTab.getValue` errors.
     * If the user reports a separate YOY crash, look at the YoyCompareCard
     * rendering path instead of the pager-content lookup path.
     */
    @Test
    fun clickingYoyTab_composesYoyTabWithoutThrowing() {
        composeTestRule.setContent {
            StatisticsContentHarness(
                yoy = YearOverYear(
                    currentWindowLabel = "Current",
                    previousWindowLabel = "Previous",
                    currentExpenseMinor = 1000L,
                    previousExpenseMinor = 800L,
                    percentChange = 0.25f,
                    isNewSpending = false,
                ),
            )
        }

        composeTestRule.onNodeWithText("YoY", useUnmergedTree = true).performClick()

        composeTestRule.onNodeWithText("25% vs last year", useUnmergedTree = true).assertIsDisplayed()
    }
}

@Composable
private fun StatisticsContentHarness(yoy: YearOverYear) {
    MaterialTheme {
        StatisticsContent(
            topCategories = TopCategoriesResult("", emptyList(), 0),
            savings = SavingsAndAverage(
                monthLabel = "",
                incomeMinor = 0L,
                expenseMinor = 0L,
                netMinor = 0L,
                savingsRate = 0f,
                averageMonthlyExpenseMinor = 0L,
                topTransactionMinor = 0L,
                averageMonthlySampleMonths = 0,
            ),
            dayOfWeek = (1..7).map { DayOfWeekBucket(it, 0L) },
            yoy = yoy,
            insights = emptyList(),
            topCatsRange = 0L..1L,
            savingsRange = 0L..1L,
            patternsRange = 0L..1L,
            yoyRange = 0L..1L,
            onRangeSelected = { _, _ -> },
        )
    }
}

class StatisticsTabCrashTestApp : Application()
