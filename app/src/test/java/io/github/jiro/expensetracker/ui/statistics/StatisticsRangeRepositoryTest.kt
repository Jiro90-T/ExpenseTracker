package io.github.jiro.expensetracker.ui.statistics

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jiro.expensetracker.ui.statistics.StatisticsTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatisticsRangeRepositoryTest {

    private fun repo(): DataStoreStatisticsRangeRepository =
        DataStoreStatisticsRangeRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun setThenObserve_returnsSameRange() = runTest {
        val r = repo()
        val range = 1_700_000_000_000L..1_730_000_000_000L
        r.set(StatisticsTab.TOP_CATS, range)
        assertEquals(range, r.observe(StatisticsTab.TOP_CATS).first())
    }

    @Test
    fun observeBeforeAnySet_returnsDefaultForTab() = runTest {
        val r = repo()
        val range = r.observe(StatisticsTab.SAVINGS).first()
        // Default = current calendar month. Verify it's at least 28 days long.
        assertEquals(true, range.last > range.first)
        assertEquals(true, (range.last - range.first) >= 28L * 24L * 3600L * 1000L)
    }

    @Test
    fun perTabIndependence_setOneTabDoesNotAffectOthers() = runTest {
        val r = repo()
        val topCatsRange = 1_700_000_000_000L..1_730_000_000_000L
        r.set(StatisticsTab.TOP_CATS, topCatsRange)
        assertNotEquals(topCatsRange, r.observe(StatisticsTab.SAVINGS).first())
        assertNotEquals(topCatsRange, r.observe(StatisticsTab.PATTERNS).first())
        assertNotEquals(topCatsRange, r.observe(StatisticsTab.YOY).first())
    }

    @Test
    fun defaultForYoy_returnsCurrentMonth() = runTest {
        val r = repo()
        val range = r.defaultFor(StatisticsTab.YOY, System.currentTimeMillis())
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = range.first }
        assertEquals(1, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(java.util.Calendar.MINUTE))
        assertEquals(0, cal.get(java.util.Calendar.SECOND))
    }

    @Test
    fun persistedAcrossInstances() = runTest {
        val r1 = repo()
        val range = 1_700_000_000_000L..1_730_000_000_000L
        r1.set(StatisticsTab.PATTERNS, range)
        val r2 = repo()
        assertEquals(range, r2.observe(StatisticsTab.PATTERNS).first())
    }
}
