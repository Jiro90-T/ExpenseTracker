package io.github.jiro.expensetracker.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BudgetRepositoryTimeTest {

    private val originalTz: TimeZone = TimeZone.getDefault()

    @Before
    fun useUtc() {
        // Force a deterministic timezone so the test doesn't flake when CI
        // runs in a tz where DST or offset boundaries could shift the result.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTz() {
        TimeZone.setDefault(originalTz)
    }

    @Test fun currentMonthStart_midMonth_returnsFirstOfThatMonth() {
        // 2026-06-10 14:23:11 UTC
        val now = utcMs(2026, 6, 10, 14, 23, 11)
        val expected = utcMs(2026, 6, 1, 0, 0, 0)
        assertEquals(expected, BudgetRepository.currentMonthStart(now))
    }

    @Test fun currentMonthStart_firstDay_returnsStartOfThatDay() {
        val now = utcMs(2026, 6, 1, 0, 0, 0)
        val expected = utcMs(2026, 6, 1, 0, 0, 0)
        assertEquals(expected, BudgetRepository.currentMonthStart(now))
    }

    @Test fun currentMonthStart_lastSecondOfMonth_returnsFirstOfThatMonth() {
        val now = utcMs(2026, 6, 30, 23, 59, 59)
        val expected = utcMs(2026, 6, 1, 0, 0, 0)
        assertEquals(expected, BudgetRepository.currentMonthStart(now))
    }

    @Test fun nextMonthStart_december_rollsToJanuaryNextYear() {
        val decFirst = utcMs(2026, 12, 1, 0, 0, 0)
        val expected = utcMs(2027, 1, 1, 0, 0, 0)
        assertEquals(expected, BudgetRepository.nextMonthStart(decFirst))
    }

    @Test fun nextMonthStart_january_rollsToFebruary() {
        val janFirst = utcMs(2026, 1, 1, 0, 0, 0)
        val expected = utcMs(2026, 2, 1, 0, 0, 0)
        assertEquals(expected, BudgetRepository.nextMonthStart(janFirst))
    }

    @Test fun nextMonthStart_isStrictlyAfterInput() {
        val junFirst = utcMs(2026, 6, 1, 0, 0, 0)
        assertTrue(BudgetRepository.nextMonthStart(junFirst) > junFirst)
    }

    private fun utcMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis
    }
}
