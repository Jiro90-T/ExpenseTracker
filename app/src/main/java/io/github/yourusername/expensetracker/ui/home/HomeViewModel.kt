package io.github.yourusername.expensetracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.yourusername.expensetracker.data.local.TransactionEntity
import io.github.yourusername.expensetracker.data.repository.TransactionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val transactions: StateFlow<List<TransactionEntity>> = repository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun addSample() {
        viewModelScope.launch {
            repository.add(
                TransactionEntity(
                    title = "Sample expense",
                    amountMinor = 12_99,
                    currencyCode = "USD",
                    type = "EXPENSE",
                    category = "Other",
                    occurredAtEpochMillis = System.currentTimeMillis(),
                    createdAtEpochMillis = System.currentTimeMillis(),
                )
            )
        }
    }
}
