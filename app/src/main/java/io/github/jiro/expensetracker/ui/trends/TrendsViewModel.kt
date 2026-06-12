package io.github.jiro.expensetracker.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.ui.charts.MonthlyTrend
import io.github.jiro.expensetracker.ui.charts.computeMonthlyTrends
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class TrendsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val monthlyTrends: StateFlow<List<MonthlyTrend>> =
        // Touch homeCurrency + fxRates so this flow re-emits if the user
        // changes settings (for future FX normalization). We don't actually
        // convert here — the line chart shows minor units as-is, matching
        // the bar chart.
        combine(
            repository.observeAll(),
            settingsRepository.homeCurrency,
            settingsRepository.fxRates,
        ) { rows, _, _ -> rows }
            .map { computeMonthlyTrends(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<MonthlyTrend?>(null)
    val selected: StateFlow<MonthlyTrend?> = _selected.asStateFlow()

    fun select(month: MonthlyTrend?) {
        _selected.value = month
    }
}
