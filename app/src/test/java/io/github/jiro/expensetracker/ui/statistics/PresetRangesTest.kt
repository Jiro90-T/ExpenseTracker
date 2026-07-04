package io.github.jiro.expensetracker.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PresetRangesTest {

    private val zone = ZoneId.systemDefault()
    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun last7Days_resolvesTo7x24hWindowEndingAtNow() {
        val now = ms(2026, 6, 17) + 14_000_000L  // 14:00 UTC-ish
        val range = StatisticsPreset.Last7Days.resolve(now)
        // 7 days = 7 * 24 * 3600 * 1000 = 604_800_000 ms
        assertEquals(now - 7L * 24L * 3600L * 1000L, range.first)
        assertEquals(now, range.last)
    }

    @Test
    fun last30Days_resolvesTo30x24hWindowEndingAtNow() {
        val now = ms(2026, 6, 17) + 14_000_000L
        val range = StatisticsPreset.Last30Days.resolve(now)
        assertEquals(now - 30L * 24L * 3600L * 1000L, range.first)
        assertEquals(now, range.last)
    }

    @Test
    fun thisMonth_resolvesToCalendarMonthContainingNow() {
        val now = ms(2026, 6, 17)
        val range = StatisticsPreset.ThisMonth.resolve(now)
        // First instant of June 1, 2026 .. first instant of July 1, 2026
        assertEquals(ms(2026, 6, 1), range.first)
        assertEquals(ms(2026, 7, 1), range.last)
    }

    @Test
    fun lastMonth_resolvesToPreviousCalendarMonth() {
        val now = ms(2026, 6, 17)
        val range = StatisticsPreset.LastMonth.resolve(now)
        assertEquals(ms(2026, 5, 1), range.first)
        assertEquals(ms(2026, 6, 1), range.last)
    }

    @Test
    fun thisYear_resolvesToCalendarYearContainingNow() {
        val now = ms(2026, 6, 17)
        val range = StatisticsPreset.ThisYear.resolve(now)
        assertEquals(ms(2026, 1, 1), range.first)
        assertEquals(ms(2027, 1, 1), range.last)
    }

    @Test
    fun thisMonth_atMonthEnd_returnsSameMonthWindow() {
        // Boundary: nowMs = Jan 31, 23:59. Should still return Jan 1..Feb 1.
        val now = ms(2026, 1, 31) + 23L * 3600L * 1000L + 59L * 60L * 1000L
        val range = StatisticsPreset.ThisMonth.resolve(now)
        assertEquals(ms(2026, 1, 1), range.first)
        assertEquals(ms(2026, 2, 1), range.last)
    }

    @Test
    fun lastMonth_atJanuary_returnsDecemberOfPriorYear() {
        val now = ms(2026, 1, 15)
        val range = StatisticsPreset.LastMonth.resolve(now)
        assertEquals(ms(2025, 12, 1), range.first)
        assertEquals(ms(2026, 1, 1), range.last)
    }

    @Test
    fun thisYear_atYearBoundary_returnsCurrentYear() {
        // Dec 31, 23:59 → still "this year" 2026
        val now = ms(2026, 12, 31) + 23L * 3600L * 1000L + 59L * 60L * 1000L
        val range = StatisticsPreset.ThisYear.resolve(now)
        assertEquals(ms(2026, 1, 1), range.first)
        assertEquals(ms(2027, 1, 1), range.last)
    }

    @Test
    fun allPresets_haveDistinctLabels() {
        val labels = StatisticsPreset.all.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }
}
