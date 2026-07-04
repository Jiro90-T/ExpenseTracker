package io.github.jiro.expensetracker.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.preferences.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val settingsRepository: SettingsRepository,
    private val rangeRepository: StatisticsRangeRepository,
) : ViewModel() {

    private val cats = categoryRepository.observeAll()
    private val home = settingsRepository.homeCurrency
    private val rates = settingsRepository.fxRates

    val topCategories: StateFlow<TopCategoriesResult> =
        rangeRepository.observe(StatisticsTab.TOP_CATS)
            .flatMapLatest { range ->
                val txns = transactionRepository.observeAll()
                combine(txns, cats, home, rates) { t, c, h, r ->
                    StatisticsCalculator.topCategories(t, c, h, r, range.first, range.last)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                TopCategoriesResult("", emptyList(), 0))

    val savings: StateFlow<SavingsAndAverage> =
        rangeRepository.observe(StatisticsTab.SAVINGS)
            .flatMapLatest { range ->
                val txns = transactionRepository.observeAll()
                combine(txns, home, rates) { t, h, r ->
                    StatisticsCalculator.savingsAndAverage(t, h, r, range.first, range.last)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                StatisticsCalculator.savingsAndAverage(emptyList(), "USD", emptyMap(), 0L, 1L))

    val dayOfWeek: StateFlow<List<DayOfWeekBucket>> =
        rangeRepository.observe(StatisticsTab.PATTERNS)
            .flatMapLatest { range ->
                val txns = transactionRepository.observeAll()
                combine(txns, home, rates) { t, h, r ->
                    StatisticsCalculator.dayOfWeekPattern(t, h, r, range.first, range.last)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                (1..7).map { DayOfWeekBucket(it, 0L) })

    val yoy: StateFlow<YearOverYear> =
        rangeRepository.observe(StatisticsTab.YOY)
            .flatMapLatest { range ->
                val priorStart = StatisticsCalculator.subtractOneYear(range.first)
                val priorEnd = StatisticsCalculator.subtractOneYear(range.last)
                val txns = transactionRepository.observeAll()
                combine(txns, home, rates) { t, h, r ->
                    StatisticsCalculator.yearOverYear(
                        t, h, r,
                        range.first, range.last,
                        priorStart, priorEnd,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                YearOverYear("", "", 0L, 0L, 0f, false))

    val insights: StateFlow<List<Insight>> =
        combine(transactionRepository.observeAll(), cats, home, rates) { t, c, h, r ->
            InsightsCalculator.compute(t, c, h, r, System.currentTimeMillis())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val topCatsRange: StateFlow<LongRange> =
        rangeRepository.observe(StatisticsTab.TOP_CATS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, defaultRange())

    val savingsRange: StateFlow<LongRange> =
        rangeRepository.observe(StatisticsTab.SAVINGS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, defaultRange())

    val patternsRange: StateFlow<LongRange> =
        rangeRepository.observe(StatisticsTab.PATTERNS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, defaultRange())

    val yoyRange: StateFlow<LongRange> =
        rangeRepository.observe(StatisticsTab.YOY)
            .stateIn(viewModelScope, SharingStarted.Eagerly, defaultRange())

    private fun defaultRange(): LongRange = runBlocking {
        rangeRepository.defaultFor(StatisticsTab.TOP_CATS, System.currentTimeMillis())
    }

    fun onRangeSelected(tab: StatisticsTab, range: LongRange) {
        viewModelScope.launch { rangeRepository.set(tab, range) }
    }
}
