package io.github.jiro.expensetracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RecurrenceTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun dailyInterval_advancesByDays() {
        val start = utcDay(2026, 6, 1, 9, 0)
        val next = nextOccurrence(RecurrenceKind.DAILY, interval = 1, fromMs = start, tz = utc)
        assertEquals(utcDay(2026, 6, 2, 9, 0), next)
    }

    @Test
    fun dailyIntervalN_advancesByNDays() {
        val start = utcDay(2026, 6, 1, 9, 0)
        val next = nextOccurrence(RecurrenceKind.DAILY, interval = 3, fromMs = start, tz = utc)
        assertEquals(utcDay(2026, 6, 4, 9, 0), next)
    }

    @Test
    fun weeklyInterval_advancesByWeeks() {
        val start = utcDay(2026, 6, 1, 9, 0)  // Monday
        val next = nextOccurrence(RecurrenceKind.WEEKLY, interval = 1, fromMs = start, tz = utc)
        assertEquals(utcDay(2026, 6, 8, 9, 0), next)
    }

    @Test
    fun monthlyInterval_preservesDayOfMonth() {
        val start = utcDay(2026, 6, 15, 9, 0)  // 15th at 09:00
        val next = nextOccurrence(RecurrenceKind.MONTHLY, interval = 1, fromMs = start, tz = utc)
        assertEquals(utcDay(2026, 7, 15, 9, 0), next)
    }

    @Test
    fun monthlyInterval_handlesEndOfMonth() {
        // Jan 31 → next is "Feb 28" (last day of Feb, not 31).
        val start = utcDay(2026, 1, 31, 9, 0)
        val next = nextOccurrence(RecurrenceKind.MONTHLY, interval = 1, fromMs = start, tz = utc)
        assertEquals(utcDay(2026, 2, 28, 9, 0), next)
    }

    @Test
    fun monthlyInterval_handlesLeapYear() {
        // Feb 29, 2024 (leap) → next is March 29, 2024. Not Feb 29 2025.
        val start = utcDay(2024, 2, 29, 9, 0)
        val next = nextOccurrence(RecurrenceKind.MONTHLY, interval = 12, fromMs = start, tz = utc)
        assertEquals(utcDay(2025, 2, 28, 9, 0), next)
    }

    @Test
    fun yearlyInterval_preservesMonthAndDay() {
        val start = utcDay(2026, 6, 15, 9, 0)
        val next = nextOccurrence(RecurrenceKind.YEARLY, interval = 1, fromMs = start, tz = utc)
        assertEquals(utcDay(2027, 6, 15, 9, 0), next)
    }

    @Test
    fun resultIsAlwaysStrictlyAfterFrom() {
        // Exhaustively: for every kind, advancing once should be strictly after the input.
        val kinds = RecurrenceKind.entries
        val start = utcDay(2026, 6, 15, 9, 0)
        for (k in kinds) {
            assertTrue("$k: next must be > from", nextOccurrence(k, 1, start, utc) > start)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun intervalZero_throws() {
        nextOccurrence(RecurrenceKind.DAILY, interval = 0, fromMs = 0L, tz = utc)
    }

    // ---- isSeriesExhausted ----

    @Test
    fun noEnd_noCap_neverExhausted() {
        val next = utcDay(2027, 1, 1, 9, 0)
        assertFalse(isSeriesExhausted(next, endAtMs = null, maxOccurrences = null, materialisedSoFar = 100))
    }

    @Test
    fun endAtInTheFuture_keepsSeries() {
        val next = utcDay(2027, 1, 1, 9, 0)
        val end = utcDay(2027, 6, 1, 9, 0)
        assertFalse(isSeriesExhausted(next, endAtMs = end, maxOccurrences = null, materialisedSoFar = 0))
    }

    @Test
    fun endAtBeforeNext_exhausts() {
        val next = utcDay(2027, 1, 1, 9, 0)
        val end = utcDay(2026, 12, 31, 9, 0)
        assertTrue(isSeriesExhausted(next, endAtMs = end, maxOccurrences = null, materialisedSoFar = 0))
    }

    @Test
    fun maxOccurrencesReached_exhausts() {
        val next = utcDay(2027, 1, 1, 9, 0)
        assertTrue(isSeriesExhausted(next, endAtMs = null, maxOccurrences = 3, materialisedSoFar = 3))
    }

    @Test
    fun maxOccurrencesNotYetReached_keepsSeries() {
        val next = utcDay(2027, 1, 1, 9, 0)
        assertFalse(isSeriesExhausted(next, endAtMs = null, maxOccurrences = 5, materialisedSoFar = 2))
    }

    // ---- helpers ----

    private fun utcDay(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(utc)
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
