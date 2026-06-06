package io.github.jiro.expensetracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One-shot state describing the most recently deleted row so the UI can show
 * a snackbar with an Undo affordance. Cleared on undo, dismiss, or replace.
 */
data class UndoState(val row: TransactionWithCategory)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val transactions: StateFlow<List<TransactionWithCategory>> = repository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _undo = MutableStateFlow<UndoState?>(null)
    val undo: StateFlow<UndoState?> = _undo.asStateFlow()

    fun delete(row: TransactionWithCategory) {
        viewModelScope.launch {
            repository.delete(row.transaction)
            _undo.value = UndoState(row)
        }
    }

    fun undoDelete() {
        val pending = _undo.value ?: return
        viewModelScope.launch {
            repository.restore(pending.row.transaction)
            _undo.value = null
        }
    }

    fun dismissUndo() {
        _undo.value = null
    }
}
