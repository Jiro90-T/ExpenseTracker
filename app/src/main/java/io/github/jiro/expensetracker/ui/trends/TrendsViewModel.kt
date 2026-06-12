package io.github.jiro.expensetracker.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.ui.charts.MonthlyTrend
import io.github.jiro.expensetracker.ui.charts.PeriodTrends
import io.github.jiro.expensetracker.ui.charts.TrendsPeriod
import io.github.jiro.expensetracker.ui.charts.computePeriodTrends
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

    private val _period = MutableStateFlow(TrendsPeriod.SixMonths)
    val period: StateFlow<TrendsPeriod> = _period.asStateFlow()

    val periodTrends: StateFlow<PeriodTrends> =
        // Touch homeCurrency + fxRates so this flow re-emits if the user
        // changes settings (for future FX normalization). The actual
        // computation only uses rows and period.
        combine(
            repository.observeAll(),
            _period,
            settingsRepository.homeCurrency,
            settingsRepository.fxRates,
        ) { rows, period, _, _ -> rows to period }
            .map { (rows, period) ->
                computePeriodTrends(
                    rows = rows,
                    period = period,
                    nowMs = System.currentTimeMillis(),
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                PeriodTrends(emptyList(), null, null, null),
            )

    private val _selected = MutableStateFlow<MonthlyTrend?>(null)
    val selected: StateFlow<MonthlyTrend?> = _selected.asStateFlow()

    fun setPeriod(period: TrendsPeriod) {
        if (_period.value != period) {
            _period.value = period
            _selected.value = null
        }
    }

    fun select(month: MonthlyTrend?) {
        _selected.value = month
    }
}
