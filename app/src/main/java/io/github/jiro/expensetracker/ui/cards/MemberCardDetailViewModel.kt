package io.github.jiro.expensetracker.ui.cards

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.MemberCardEntity
import io.github.jiro.expensetracker.data.repository.MemberCardRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for the Detail screen.
 *
 * - [card] is null while loading or when the route's `cardId` did not resolve
 *   to a row ([notFound] tells those two cases apart).
 * - [deleted] is a one-shot signal the screen reads in a LaunchedEffect to
 *   pop the back stack once the row + image file have been removed.
 * - [errorMessage] is a one-shot snackbar payload; the screen calls
 *   [onErrorShown] after displaying it.
 */
data class MemberCardDetailUiState(
    val card: MemberCardEntity? = null,
    val isLoading: Boolean = true,
    val showDeleteConfirm: Boolean = false,
    val deleted: Boolean = false,
    val notFound: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Detail VM for the member-cards feature.
 *
 * Reads the card id from the nav route arg `cardId` via [SavedStateHandle].
 * The route definition (and the `cardId` arg key) is wired in Task 13's
 * AppNav changes — keep this in sync.
 *
 * The repository is exposed as `val` (not `private val`) so the screen can
 * hand it to [MemberCardImage] for decoding the hero photo, mirroring the
 * pattern Task 10 set on [MemberCardListViewModel].
 */
@HiltViewModel
class MemberCardDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val repository: MemberCardRepository,
) : ViewModel() {

    private val cardId: Long = savedStateHandle.get<Long>("cardId") ?: -1L

    private val _state = MutableStateFlow(MemberCardDetailUiState())
    val state: StateFlow<MemberCardDetailUiState> = _state.asStateFlow()

    init {
        if (cardId <= 0L) {
            _state.update { it.copy(isLoading = false, notFound = true) }
        } else {
            load()
        }
    }

    /** Re-fetch the row; called when returning from Edit. */
    fun refresh() {
        if (cardId > 0L) load()
    }

    private fun load() {
        viewModelScope.launch {
            val card = withContext(Dispatchers.IO) { repository.getById(cardId) }
            _state.update {
                it.copy(
                    card = card,
                    isLoading = false,
                    notFound = card == null,
                )
            }
        }
    }

    fun onDeleteClick() {
        _state.update { it.copy(showDeleteConfirm = true) }
    }

    fun onDeleteConfirm() {
        viewModelScope.launch {
            runCatching { repository.delete(cardId) }
                .onSuccess {
                    _state.update { it.copy(showDeleteConfirm = false, deleted = true) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            showDeleteConfirm = false,
                            errorMessage = e.message ?: "Delete failed",
                        )
                    }
                }
        }
    }

    fun onDeleteDismiss() {
        _state.update { it.copy(showDeleteConfirm = false) }
    }

    fun onErrorShown() {
        _state.update { it.copy(errorMessage = null) }
    }
}