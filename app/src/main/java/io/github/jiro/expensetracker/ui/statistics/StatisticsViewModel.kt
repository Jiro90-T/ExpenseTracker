package io.github.jiro.expensetracker.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.preferences.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    repository: TransactionRepository,
    categoryRepository: CategoryRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val txns = repository.observeAll()
    private val cats = categoryRepository.observeAll()
    private val home = settingsRepository.homeCurrency
    private val rates = settingsRepository.fxRates

    val topCategories: StateFlow<TopCategoriesResult> =
        combine(txns, cats, home, rates) { t, c, h, r ->
            StatisticsCalculator.topCategories(t, c, h, r, System.currentTimeMillis())
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TopCategoriesResult("", emptyList(), 0),
        )

    val savings: StateFlow<SavingsAndAverage> =
        combine(txns, home, rates) { t, h, r ->
            StatisticsCalculator.savingsAndAverage(t, h, r, System.currentTimeMillis())
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatisticsCalculator.savingsAndAverage(emptyList(), "USD", emptyMap(), System.currentTimeMillis()),
        )

    val dayOfWeek: StateFlow<List<DayOfWeekBucket>> =
        combine(txns, home, rates) { t, h, r ->
            StatisticsCalculator.dayOfWeekPattern(t, h, r, System.currentTimeMillis())
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = (1..7).map { DayOfWeekBucket(it, 0L) },
        )

    val yoy: StateFlow<YearOverYear> =
        combine(txns, home, rates) { t, h, r ->
            StatisticsCalculator.yearOverYear(t, h, r, System.currentTimeMillis())
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatisticsCalculator.yearOverYear(emptyList(), "USD", emptyMap(), System.currentTimeMillis()),
        )
}