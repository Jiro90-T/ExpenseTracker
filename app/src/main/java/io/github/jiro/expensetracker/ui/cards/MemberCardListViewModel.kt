package io.github.jiro.expensetracker.ui.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.MemberCardEntity
import io.github.jiro.expensetracker.data.repository.MemberCardRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MemberCardListUiState(
    val cards: List<MemberCardEntity> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    /** True only when no cards exist in the DB at all (vs. filtered to zero). */
    val isTrulyEmpty: Boolean = false,
)

@HiltViewModel
class MemberCardListViewModel @Inject constructor(
    private val repository: MemberCardRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")

    // Filter strategy: in-memory filter on the full observeAll() flow.
    // The DAO already orders by name; empty query returns the full list.
    // We could use repository.search(query) here instead, but a single
    // observe + filter keeps the search responsive without a second
    // round-trip to the DB on every keystroke.
    val state: StateFlow<MemberCardListUiState> = combine(
        repository.observeAll(),
        _query,
    ) { all, q ->
        val filtered = if (q.isBlank()) {
            all
        } else {
            all.filter { it.name.contains(q, ignoreCase = true) }
        }
        MemberCardListUiState(
            cards = filtered,
            query = q,
            isLoading = false,
            isTrulyEmpty = all.isEmpty(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MemberCardListUiState(),
    )

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun clearQuery() {
        _query.value = ""
    }
}
