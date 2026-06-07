package io.github.jiro.expensetracker.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CategoryFormError { NAME_REQUIRED, NAME_DUPLICATE }

/** State of the add/edit dialog. Null = no dialog open. */
data class CategoryFormState(
    val id: Long? = null,                       // null = adding
    val name: String = "",
    val type: TransactionType = TransactionType.EXPENSE,
    val isBuiltIn: Boolean = false,             // shown read-only; never settable from here
    val error: CategoryFormError? = null,
)

data class CategoryManagementUiState(
    val expenseCategories: List<CategoryEntity> = emptyList(),
    val incomeCategories: List<CategoryEntity> = emptyList(),
    val form: CategoryFormState? = null,        // null = no dialog open
    val toast: String? = null,                  // one-shot message consumed by the screen
)

@HiltViewModel
class CategoryManagementViewModel @Inject constructor(
    private val repository: CategoryRepository,
) : ViewModel() {

    private val expenseFlow = repository.observeByType(TransactionType.EXPENSE)
    private val incomeFlow = repository.observeByType(TransactionType.INCOME)
    private val formFlow = MutableStateFlow<CategoryFormState?>(null)
    private val toastFlow = MutableStateFlow<String?>(null)

    val state: StateFlow<CategoryManagementUiState> = combine(
        expenseFlow, incomeFlow, formFlow, toastFlow,
    ) { expense, income, form, toast ->
        CategoryManagementUiState(
            expenseCategories = expense,
            incomeCategories = income,
            form = form,
            toast = toast,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CategoryManagementUiState(),
    )

    fun openAddDialog() {
        formFlow.value = CategoryFormState()
    }

    fun openEditDialog(category: CategoryEntity) {
        formFlow.value = CategoryFormState(
            id = category.id,
            name = category.name,
            type = TransactionType.fromStorage(category.type),
            isBuiltIn = category.isBuiltIn,
        )
    }

    fun closeDialog() {
        formFlow.value = null
    }

    fun onNameChange(value: String) = formFlow.update { it?.copy(name = value, error = null) }
    fun onTypeChange(value: TransactionType) = formFlow.update {
        it?.copy(type = value, error = null)
    }

    fun save() {
        val current = formFlow.value ?: return
        val name = current.name.trim()
        if (name.isEmpty()) {
            formFlow.update { it?.copy(error = CategoryFormError.NAME_REQUIRED) }
            return
        }
        viewModelScope.launch {
            try {
                if (current.id == null) {
                    repository.add(name, current.type)
                    toastFlow.value = "Category added"
                } else {
                    repository.update(
                        CategoryEntity(
                            id = current.id,
                            name = name,
                            type = current.type.name,
                            sortOrder = 0,        // sort order is a presentation concern, not yet wired
                            isBuiltIn = current.isBuiltIn,
                        )
                    )
                    toastFlow.value = "Category updated"
                }
                formFlow.value = null
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // Most likely the (name, type) unique index firing — i.e. duplicate.
                formFlow.update { it?.copy(error = CategoryFormError.NAME_DUPLICATE) }
            }
        }
    }

    fun delete(category: CategoryEntity) {
        viewModelScope.launch {
            try {
                if (repository.deleteById(category.id)) {
                    toastFlow.value = "Category deleted"
                    if (formFlow.value?.id == category.id) formFlow.value = null
                } else {
                    // Defensive — the UI hides delete for built-ins.
                    toastFlow.value = "Cannot delete this category"
                }
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // FK on TransactionEntity.categoryId fires RESTRICT.
                toastFlow.value = "Cannot delete: category is in use by transactions"
            }
        }
    }

    fun consumeToast() {
        toastFlow.value = null
    }
}
