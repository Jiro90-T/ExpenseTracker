package io.github.jiro.expensetracker.ui.home

import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat

/** A user-selectable date range for the transaction list + dashboard. */
sealed interface Period {
    data object All : Period
    data class Month(val year: Int, val month: Int) : Period {  // month 1-12
        fun previous(): Month = if (month == 1) Month(year - 1, 12) else Month(year, month - 1)
        fun next(): Month = if (month == 12) Month(year + 1, 1) else Month(year, month + 1)
    }

    companion object {
        fun currentMonth(): Month {
            val cal = Calendar.getInstance()
            return Month(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        }
    }
}

/** Returns [startMs, endMs) in epoch millis for the month. */
fun Period.monthBounds(): Pair<Long, Long>? = when (this) {
    Period.All -> null
    is Period.Month -> {
        val cal = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis
        start to end
    }
}

fun Period.label(): String = when (this) {
    Period.All -> "All time"
    is Period.Month -> {
        val cal = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
    }
}
