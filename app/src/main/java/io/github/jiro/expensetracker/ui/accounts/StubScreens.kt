package io.github.jiro.expensetracker.ui.accounts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    onEditAccount: (Long) -> Unit,
    onTransactionClick: (Long) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Account Detail (TODO)") }
}
