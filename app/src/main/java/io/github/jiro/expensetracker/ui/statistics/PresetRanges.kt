package io.github.jiro.expensetracker.ui.statistics

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

sealed class StatisticsPreset(val label: String) {
    abstract fun resolve(nowMs: Long): LongRange

    companion object {
        val all: List<StatisticsPreset> by lazy {
            listOf(Last7Days, Last30Days, ThisMonth, LastMonth, ThisYear)
        }
    }

    object Last7Days : StatisticsPreset("Last 7 days") {
        override fun resolve(nowMs: Long): LongRange =
            (nowMs - 7L * 24L * 3600L * 1000L)..nowMs
    }

    object Last30Days : StatisticsPreset("Last 30 days") {
        override fun resolve(nowMs: Long): LongRange =
            (nowMs - 30L * 24L * 3600L * 1000L)..nowMs
    }

    object ThisMonth : StatisticsPreset("This month") {
        override fun resolve(nowMs: Long): LongRange {
            val date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
            val ym = YearMonth.of(date.year, date.monthValue)
            return ym.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()..
                ym.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }

    object LastMonth : StatisticsPreset("Last month") {
        override fun resolve(nowMs: Long): LongRange {
            val date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
            val ym = YearMonth.of(date.year, date.monthValue).minusMonths(1)
            return ym.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()..
                ym.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }

    object ThisYear : StatisticsPreset("This year") {
        override fun resolve(nowMs: Long): LongRange {
            val date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
            val start = date.withDayOfYear(1)
            val end = date.plusYears(1).withDayOfYear(1)
            return start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()..
                end.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }
}
