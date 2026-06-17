package io.github.jiro.expensetracker.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatisticsCalculatorTest {

    @Test
    fun monthBounds_january() {
        val (start, end) = StatisticsCalculator.monthBounds(2026, 1)
        val zone = ZoneId.systemDefault()
        assertEquals(LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(), start)
        assertEquals(LocalDate.of(2026, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli(), end)
    }

    @Test
    fun monthBounds_decemberYearRollover() {
        val (start, end) = StatisticsCalculator.monthBounds(2026, 12)
        val zone = ZoneId.systemDefault()
        assertEquals(LocalDate.of(2026, 12, 1).atStartOfDay(zone).toInstant().toEpochMilli(), start)
        assertEquals(LocalDate.of(2027, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(), end)
    }

    @Test(expected = IllegalArgumentException::class)
    fun monthBounds_invalidMonth_throws() {
        StatisticsCalculator.monthBounds(2026, 13)
    }

    @Test
    fun monthLabel_june() {
        val ms = LocalDate.of(2026, 6, 17).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("June 2026", StatisticsCalculator.monthLabel(ms))
    }

    @Test
    fun monthLabel_january() {
        val ms = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("January 2026", StatisticsCalculator.monthLabel(ms))
    }
}
