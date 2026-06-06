package io.github.jiro.expensetracker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.ui.theme.ExpenseTrackerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.home_title)) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { /* TODO: open add-transaction form */ }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_transaction))
            }
        },
    ) { padding ->
        if (transactions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.home_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(transactions, key = { it.transaction.id }) { row ->
                    TransactionRow(row)
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(row: TransactionWithCategory) {
    val txn = row.transaction
    val category = row.category
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(txn.title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "${txn.type} · ${category.name} · ${txn.currencyCode} " +
                "${txn.amountMinor / 100}.${"%02d".format(txn.amountMinor % 100)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    ExpenseTrackerTheme {
        HomeScreen()
    }
}
