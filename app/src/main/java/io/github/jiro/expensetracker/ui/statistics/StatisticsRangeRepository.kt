package io.github.jiro.expensetracker.ui.statistics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The four statistics tabs. Exists in this file (rather than StatisticsScreen.kt)
 * because the repository and ViewModel need to reference it. The screen-side
 * `StatTab` enum will be merged into this one in Task 11.
 */
enum class StatisticsTab {
    TOP_CATS, SAVINGS, PATTERNS, YOY, INSIGHTS,
}

interface StatisticsRangeRepository {
    fun observe(tab: StatisticsTab): Flow<LongRange>
    suspend fun set(tab: StatisticsTab, range: LongRange)
    suspend fun defaultFor(tab: StatisticsTab, nowMs: Long): LongRange
}

private val Context.statisticsRangeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "statistics_range",
)

@Singleton
class DataStoreStatisticsRangeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : StatisticsRangeRepository {

    private val dataStore get() = context.statisticsRangeDataStore

    override fun observe(tab: StatisticsTab): Flow<LongRange> =
        dataStore.data.map { prefs ->
            val (startKey, endKey) = keysFor(tab)
            val start = prefs[startKey]
            val end = prefs[endKey]
            if (start != null && end != null) start..end
            else defaultFor(tab, System.currentTimeMillis())
        }

    override suspend fun set(tab: StatisticsTab, range: LongRange) {
        val (startKey, endKey) = keysFor(tab)
        dataStore.edit { prefs ->
            prefs[startKey] = range.first
            prefs[endKey] = range.last
        }
    }

    override suspend fun defaultFor(tab: StatisticsTab, nowMs: Long): LongRange {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val ym = YearMonth.of(date.year, date.monthValue)
        val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start..end
    }

    private fun keysFor(tab: StatisticsTab): Pair<Preferences.Key<Long>, Preferences.Key<Long>> =
        when (tab) {
            StatisticsTab.TOP_CATS -> KEY_TOP_CATS_START to KEY_TOP_CATS_END
            StatisticsTab.SAVINGS  -> KEY_SAVINGS_START  to KEY_SAVINGS_END
            StatisticsTab.PATTERNS -> KEY_PATTERNS_START to KEY_PATTERNS_END
            StatisticsTab.YOY      -> KEY_YOY_START      to KEY_YOY_END
            StatisticsTab.INSIGHTS -> error("INSIGHTS tab does not use a persisted range override")
        }

    companion object {
        private val KEY_TOP_CATS_START = longPreferencesKey("stats_range_top_cats_start")
        private val KEY_TOP_CATS_END   = longPreferencesKey("stats_range_top_cats_end")
        private val KEY_SAVINGS_START  = longPreferencesKey("stats_range_savings_start")
        private val KEY_SAVINGS_END    = longPreferencesKey("stats_range_savings_end")
        private val KEY_PATTERNS_START = longPreferencesKey("stats_range_patterns_start")
        private val KEY_PATTERNS_END   = longPreferencesKey("stats_range_patterns_end")
        private val KEY_YOY_START      = longPreferencesKey("stats_range_yoy_start")
        private val KEY_YOY_END        = longPreferencesKey("stats_range_yoy_end")
    }
}
