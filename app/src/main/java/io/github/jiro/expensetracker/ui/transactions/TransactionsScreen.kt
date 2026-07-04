package io.github.jiro.expensetracker.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.home.DayHeader
import io.github.jiro.expensetracker.ui.home.HomeViewModel
import io.github.jiro.expensetracker.ui.home.SwipeableTransactionRow
import io.github.jiro.expensetracker.ui.home.groupByDay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onTransactionClick: (Long) -> Unit = {},
    reselectTrigger: Int = 0,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val filteredTransactions by viewModel.filteredTransactions.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val allCategories by viewModel.allCategories.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val undoState by viewModel.undo.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.action_undo)
    val deletedLabel = stringResource(R.string.snackbar_transaction_deleted)

    val listState = rememberLazyListState()
    LaunchedEffect(reselectTrigger) {
        if (reselectTrigger > 0) listState.animateScrollToItem(0)
    }

    LaunchedEffect(undoState) {
        val pending = undoState ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedLabel,
            actionLabel = undoLabel,
            withDismissAction = true,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoDelete()
            SnackbarResult.Dismissed -> viewModel.dismissUndo()
        }
    }

    // Debounce the search text: the local `searchInput` updates immediately for
    // UI responsiveness, the actual filter is committed 300ms after the last keystroke.
    var searchInput by remember { mutableStateOf(filters.searchQuery) }
    var minInput by remember { mutableStateOf(filters.minAmount?.toString() ?: "") }
    var maxInput by remember { mutableStateOf(filters.maxAmount?.toString() ?: "") }
    LaunchedEffect(filters.searchQuery) {
        // When the repo's filters change (e.g. after a "clear filters"), reset the local input.
        if (filters.searchQuery != searchInput) {
            searchInput = filters.searchQuery
        }
    }
    LaunchedEffect(searchInput) {
        delay(300)
        if (searchInput != filters.searchQuery) {
            viewModel.setSearchQuery(searchInput)
        }
    }
    LaunchedEffect(minInput) {
        delay(300)
        val parsed = if (minInput.isBlank()) null else MoneyFormat.parseAmountToMinor(minInput)
        if (parsed != filters.minAmount) viewModel.setMinAmount(parsed)
    }
    LaunchedEffect(maxInput) {
        delay(300)
        val parsed = if (maxInput.isBlank()) null else MoneyFormat.parseAmountToMinor(maxInput)
        if (parsed != filters.maxAmount) viewModel.setMaxAmount(parsed)
    }

    // Sync the local inputs when the repo's filters change externally.
    LaunchedEffect(filters.minAmount) {
        val expected = filters.minAmount?.toString() ?: ""
        if (expected != minInput) minInput = expected
    }
    LaunchedEffect(filters.maxAmount) {
        val expected = filters.maxAmount?.toString() ?: ""
        if (expected != maxInput) maxInput = expected
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.transactions_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            FilterControls(
                searchInput = searchInput,
                onSearchInputChange = { searchInput = it },
                filters = filters,
                categories = allCategories,
                onTypeChange = viewModel::setTypeFilter,
                onCategoryChange = viewModel::setCategoryFilter,
                onDateRangeChange = viewModel::setDateRange,
                onClear = viewModel::clearFilters,
                minInput = minInput,
                onMinInputChange = { minInput = it },
                maxInput = maxInput,
                onMaxInputChange = { maxInput = it },
                sort = sort,
                onSortFieldChange = viewModel::setSortField,
                onFlipDirection = viewModel::flipSortDirection,
            )
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (filteredTransactions.isEmpty()) {
                    EmptyState(
                        isFiltered = !filters.isEmpty,
                        onClear = viewModel::clearFilters,
                    )
                } else {
                    val grouped = remember(filteredTransactions, sort.field) {
                        if (sort.field == SortField.DATE) groupByDay(filteredTransactions) else null
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (grouped != null) {
                            grouped.forEach { group ->
                                item(key = "day_${group.dayStartMs}") {
                                    DayHeader(group.dayStartMs)
                                }
                                items(group.items, key = { it.transaction.id }) { row ->
                                    SwipeableTransactionRow(
                                        row = row,
                                        onEdit = { onTransactionClick(row.transaction.id) },
                                        onDelete = { viewModel.delete(row) },
                                        searchQuery = filters.searchQuery,
                                    )
                                }
                            }
                        } else {
                            items(filteredTransactions, key = { it.transaction.id }) { row ->
                                SwipeableTransactionRow(
                                    row = row,
                                    onEdit = { onTransactionClick(row.transaction.id) },
                                    onDelete = { viewModel.delete(row) },
                                    searchQuery = filters.searchQuery,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterControls(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    filters: TransactionFilters,
    categories: List<CategoryEntity>,
    onTypeChange: (TypeFilter) -> Unit,
    onCategoryChange: (Long?) -> Unit,
    onDateRangeChange: (DateRangePreset) -> Unit,
    onClear: () -> Unit,
    minInput: String,
    onMinInputChange: (String) -> Unit,
    maxInput: String,
    onMaxInputChange: (String) -> Unit,
    sort: TransactionSort,
    onSortFieldChange: (SortField) -> Unit,
    onFlipDirection: () -> Unit,
) {
    var showDateDialog by remember { mutableStateOf(false) }
    // Collapsible section: sort + amount range + category/date + clear.
    // Default collapsed so the filter block doesn't dominate the screen —
    // users tap "Filters" to reveal the advanced controls. The type chips
    // stay always-visible since they're the most-used quick filter.
    var filtersExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = searchInput,
            onValueChange = onSearchInputChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.filter_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (searchInput.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchInputChange("") }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.filter_clear),
                        )
                    }
                }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeChip(
                label = stringResource(R.string.filter_type_all),
                selected = filters.typeFilter == TypeFilter.ALL,
                onClick = { onTypeChange(TypeFilter.ALL) },
            )
            TypeChip(
                label = stringResource(R.string.filter_type_income),
                selected = filters.typeFilter == TypeFilter.INCOME,
                onClick = { onTypeChange(TypeFilter.INCOME) },
            )
            TypeChip(
                label = stringResource(R.string.filter_type_expense),
                selected = filters.typeFilter == TypeFilter.EXPENSE,
                onClick = { onTypeChange(TypeFilter.EXPENSE) },
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { filtersExpanded = !filtersExpanded }) {
                Text(stringResource(R.string.filter_section_label))
                Icon(
                    imageVector = if (filtersExpanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (filtersExpanded) R.string.filter_section_collapse_cd
                        else R.string.filter_section_expand_cd
                    ),
                )
            }
        }

        AnimatedVisibility(visible = filtersExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${stringResource(R.string.filter_sort_label)}:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SortFieldDropdown(
                        selected = sort.field,
                        onSelect = onSortFieldChange,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onFlipDirection) {
                        Icon(
                            imageVector = if (sort.direction == SortDirection.ASC)
                                Icons.Filled.ArrowUpward
                            else
                                Icons.Filled.ArrowDownward,
                            contentDescription = stringResource(
                                if (sort.direction == SortDirection.ASC)
                                    R.string.filter_sort_direction_asc
                                else
                                    R.string.filter_sort_direction_desc
                            ),
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = minInput,
                        onValueChange = onMinInputChange,
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.filter_amount_min)) },
                        placeholder = { Text(stringResource(R.string.filter_amount_min_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        trailingIcon = if (minInput.isNotEmpty()) {
                            {
                                IconButton(onClick = { onMinInputChange("") }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.filter_clear),
                                    )
                                }
                            }
                        } else null,
                    )
                    OutlinedTextField(
                        value = maxInput,
                        onValueChange = onMaxInputChange,
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.filter_amount_max)) },
                        placeholder = { Text(stringResource(R.string.filter_amount_max_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        trailingIcon = if (maxInput.isNotEmpty()) {
                            {
                                IconButton(onClick = { onMaxInputChange("") }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.filter_clear),
                                    )
                                }
                            }
                        } else null,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryDropdown(
                        categories = categories,
                        selectedCategoryId = filters.categoryId,
                        onSelect = onCategoryChange,
                        modifier = Modifier.weight(1f),
                    )
                    DateRangeDropdown(
                        selected = filters.dateRange,
                        onPresetSelected = onDateRangeChange,
                        onCustomRequested = { showDateDialog = true },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (!filters.isEmpty) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.filter_clear))
                    }
                }
            }
        }
    }

    if (showDateDialog) {
        DateRangePickerDialog(
            initialRange = filters.dateRange,
            onDismiss = { showDateDialog = false },
            onConfirm = { from, to ->
                showDateDialog = false
                onDateRangeChange(DateRangePreset.Custom(from, to))
            },
        )
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(
    categories: List<CategoryEntity>,
    selectedCategoryId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = selectedCategoryId?.let { id -> categories.firstOrNull { it.id == id }?.name }
        ?: stringResource(R.string.filter_category_all)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_category_all)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            categories.sortedBy { it.name.lowercase() }.forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat.name) },
                    onClick = {
                        onSelect(cat.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDropdown(
    selected: DateRangePreset,
    onPresetSelected: (DateRangePreset) -> Unit,
    onCustomRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = when (selected) {
        DateRangePreset.Any -> stringResource(R.string.filter_date_any)
        DateRangePreset.Last7Days -> stringResource(R.string.filter_date_last_7_days)
        DateRangePreset.Last30Days -> stringResource(R.string.filter_date_last_30_days)
        DateRangePreset.ThisMonth -> stringResource(R.string.filter_date_this_month)
        DateRangePreset.ThisYear -> stringResource(R.string.filter_date_this_year)
        is DateRangePreset.Custom -> formatCustomRange(selected.fromMs, selected.toMsExclusive)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_date_any)) },
                onClick = { onPresetSelected(DateRangePreset.Any); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_date_last_7_days)) },
                onClick = { onPresetSelected(DateRangePreset.Last7Days); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_date_last_30_days)) },
                onClick = { onPresetSelected(DateRangePreset.Last30Days); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_date_this_month)) },
                onClick = { onPresetSelected(DateRangePreset.ThisMonth); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_date_this_year)) },
                onClick = { onPresetSelected(DateRangePreset.ThisYear); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_date_custom)) },
                onClick = { expanded = false; onCustomRequested() },
            )
        }
    }
}

private fun formatCustomRange(fromMs: Long, toMsExclusive: Long): String {
    val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
    return "${fmt.format(Date(fromMs))} – ${fmt.format(Date(toMsExclusive - 86_400_000L))}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerDialog(
    initialRange: DateRangePreset,
    onDismiss: () -> Unit,
    onConfirm: (fromMs: Long, toMsExclusive: Long) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = (initialRange as? DateRangePreset.Custom)?.fromMs,
        initialSelectedEndDateMillis = (initialRange as? DateRangePreset.Custom)?.toMsExclusive?.minus(86_400_000L),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val from = state.selectedStartDateMillis
                    val end = state.selectedEndDateMillis
                    if (from != null && end != null) {
                        onConfirm(from, end + 86_400_000L)
                    } else {
                        onDismiss()
                    }
                },
                enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        text = {
            DateRangePicker(state = state, modifier = Modifier.heightIn(min = 200.dp, max = 600.dp))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortFieldDropdown(
    selected: SortField,
    onSelect: (SortField) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = stringResource(selected.labelRes),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SortField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(stringResource(field.labelRes)) },
                    onClick = {
                        onSelect(field)
                        expanded = false
                    },
                )
            }
        }
    }
}

private val SortField.labelRes: Int
    get() = when (this) {
        SortField.DATE -> R.string.filter_sort_field_date
        SortField.AMOUNT -> R.string.filter_sort_field_amount
        SortField.TITLE -> R.string.filter_sort_field_title
        SortField.CATEGORY -> R.string.filter_sort_field_category
    }

@Composable
private fun EmptyState(isFiltered: Boolean, onClear: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isFiltered) {
            Text(
                text = stringResource(R.string.filter_no_matches_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.filter_no_matches_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(16.dp))
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.filter_clear))
            }
        } else {
            Text(
                text = stringResource(R.string.home_empty),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
