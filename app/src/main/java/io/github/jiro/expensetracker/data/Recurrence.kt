package io.github.jiro.expensetracker.data

import java.util.Calendar
import java.util.TimeZone

/**
 * Cadence at which a recurring transaction materialises the next instance.
 *
 * Stored in [io.github.jiro.expensetracker.data.local.TransactionEntity.recurrenceKind]
 * as the enum's [name]; keep this in sync if a value is added.
 */
enum class RecurrenceKind {
    DAILY, WEEKLY, MONTHLY, YEARLY;

    companion object {
        fun fromStorage(value: String?): RecurrenceKind? = value?.let {
            entries.firstOrNull { e -> e.name == it }
        }
    }
}

/**
 * Pure function: returns the next occurrence time after [fromMs] (inclusive)
 * for a transaction recurring every [interval] [kind]s. The result is always
 * strictly after [fromMs] (a "next" occurrence, never the same instant).
 *
 * Times-of-day are preserved: a monthly rent set on the 1st at 09:00
 * materialises the next instance on the 1st at 09:00. Uses the system
 * default timezone.
 */
fun nextOccurrence(
    kind: RecurrenceKind,
    interval: Int,
    fromMs: Long,
    tz: TimeZone = TimeZone.getDefault(),
): Long {
    require(interval >= 1) { "interval must be >= 1, was $interval" }
    val cal = Calendar.getInstance(tz).apply { timeInMillis = fromMs }
    when (kind) {
        RecurrenceKind.DAILY -> cal.add(Calendar.DAY_OF_YEAR, interval)
        RecurrenceKind.WEEKLY -> cal.add(Calendar.WEEK_OF_YEAR, interval)
        RecurrenceKind.MONTHLY -> cal.add(Calendar.MONTH, interval)
        RecurrenceKind.YEARLY -> cal.add(Calendar.YEAR, interval)
    }
    return cal.timeInMillis
}

/**
 * Decides whether a recurring series is still active. A series is exhausted
 * when:
 *   - the next scheduled occurrence is past the end-by-date, or
 *   - the next scheduled occurrence would push the total instance count
 *     over the max-occurrences cap.
 */
fun isSeriesExhausted(
    nextMs: Long,
    endAtMs: Long?,
    maxOccurrences: Int?,
    materialisedSoFar: Int,
): Boolean {
    if (endAtMs != null && nextMs > endAtMs) return true
    if (maxOccurrences != null && materialisedSoFar >= maxOccurrences) return true
    return false
}
