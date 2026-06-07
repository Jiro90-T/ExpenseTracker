package io.github.jiro.expensetracker.ui.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.domain.model.TransactionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagementScreen(
    onBack: () -> Unit,
    viewModel: CategoryManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface one-shot VM toasts via the screen's snackbar host.
    LaunchedEffect(state.toast) {
        val message = state.toast ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeToast()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.categories_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::openAddDialog) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_category))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "header_expense") {
                SectionHeader(stringResource(R.string.section_expense))
            }
            items(state.expenseCategories, key = { it.id }) { category ->
                CategoryRow(
                    category = category,
                    onEdit = { viewModel.openEditDialog(category) },
                )
            }
            item(key = "header_income") {
                SectionHeader(stringResource(R.string.section_income))
            }
            items(state.incomeCategories, key = { it.id }) { category ->
                CategoryRow(
                    category = category,
                    onEdit = { viewModel.openEditDialog(category) },
                )
            }
        }
    }

    state.form?.let { form ->
        // Find the actual CategoryEntity being edited so the dialog's delete
        // button can call viewModel.delete() with the right row.
        val editing: CategoryEntity? = form.id?.let { id ->
            state.expenseCategories.firstOrNull { it.id == id }
                ?: state.incomeCategories.firstOrNull { it.id == id }
        }
        CategoryFormDialog(
            form = form,
            editing = editing,
            onNameChange = viewModel::onNameChange,
            onTypeChange = viewModel::onTypeChange,
            onSave = viewModel::save,
            onDelete = editing?.let { e -> { viewModel.delete(e) } },
            onDismiss = viewModel::closeDialog,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun CategoryRow(
    category: CategoryEntity,
    onEdit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(category.name, style = MaterialTheme.typography.bodyLarge)
            if (category.isBuiltIn) {
                Text(
                    text = stringResource(R.string.label_built_in),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
        }
        if (!category.isBuiltIn) {
            IconButton(onClick = { onEdit() }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            // Reserve the same horizontal space so rows don't shift between built-in and user rows.
            Box(modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFormDialog(
    form: CategoryFormState,
    editing: CategoryEntity?,
    onNameChange: (String) -> Unit,
    onTypeChange: (TransactionType) -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val titleRes = if (form.id == null) R.string.category_add_title else R.string.category_edit_title
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    enabled = !form.isBuiltIn,
                    isError = form.error == CategoryFormError.NAME_REQUIRED ||
                        form.error == CategoryFormError.NAME_DUPLICATE,
                    supportingText = {
                        when (form.error) {
                            CategoryFormError.NAME_REQUIRED -> Text(stringResource(R.string.error_name_required))
                            CategoryFormError.NAME_DUPLICATE -> Text(stringResource(R.string.error_name_duplicate))
                            else -> {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = form.type == TransactionType.EXPENSE,
                        onClick = { onTypeChange(TransactionType.EXPENSE) },
                        enabled = !form.isBuiltIn,
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.type_expense)) }
                    SegmentedButton(
                        selected = form.type == TransactionType.INCOME,
                        onClick = { onTypeChange(TransactionType.INCOME) },
                        enabled = !form.isBuiltIn,
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.type_income)) }
                }
                if (form.isBuiltIn) {
                    Text(
                        text = stringResource(R.string.help_built_in_read_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = !form.isBuiltIn,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}
