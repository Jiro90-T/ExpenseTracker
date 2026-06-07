package io.github.jiro.expensetracker.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.jiro.expensetracker.ui.add_edit.AddEditTransactionScreen
import io.github.jiro.expensetracker.ui.budget.BudgetScreen
import io.github.jiro.expensetracker.ui.categories.CategoryManagementScreen
import io.github.jiro.expensetracker.ui.home.HomeScreen
import io.github.jiro.expensetracker.ui.more.MoreScreen
import io.github.jiro.expensetracker.ui.reports.ReportsScreen
import io.github.jiro.expensetracker.ui.settings.SettingsScreen
import io.github.jiro.expensetracker.ui.transactions.TransactionsScreen

object Routes {
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val ADD_EDIT = "add_edit?id={id}"
    const val ADD_EDIT_ARG_ID = "id"
    const val ADD_EDIT_NO_ID = "add_edit"
    const val BUDGET = "budget"
    const val REPORTS = "reports"
    const val MORE = "more"
    const val CATEGORIES = "categories"
    const val SETTINGS = "settings"
}

fun addEditRoute(transactionId: Long? = null): String =
    if (transactionId == null) Routes.ADD_EDIT_NO_ID else "add_edit?id=$transactionId"

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
) {
    // Per-tab reselect counters. The bottom nav increments the matching
    // counter when the user taps an already-active tab; the corresponding
    // screen observes the counter and scrolls its LazyColumn to the top.
    // This is what makes tapping "Home" while already on Home do something
    // visible (jump-to-top), instead of being a silent no-op.
    var homeReselectCount by remember { mutableIntStateOf(0) }
    var transactionsReselectCount by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            AppBottomBar(
                navController = navController,
                onAddClick = { navController.navigate(addEditRoute()) },
                onTabReselected = { route ->
                    when (route) {
                        Routes.HOME -> homeReselectCount++
                        Routes.TRANSACTIONS -> transactionsReselectCount++
                        // Other tabs don't have lists to scroll, so the
                        // reselect is a silent no-op for now.
                    }
                },
            )
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = androidx.compose.ui.Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onSeeAllTransactions = { navController.navigate(Routes.TRANSACTIONS) },
                    reselectTrigger = homeReselectCount,
                )
            }
            composable(Routes.TRANSACTIONS) {
                TransactionsScreen(
                    onTransactionClick = { id -> navController.navigate(addEditRoute(id)) },
                    reselectTrigger = transactionsReselectCount,
                )
            }
            composable(
                route = Routes.ADD_EDIT,
                arguments = listOf(
                    navArgument(Routes.ADD_EDIT_ARG_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) {
                AddEditTransactionScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.BUDGET) { BudgetScreen() }
            composable(Routes.REPORTS) {
                ReportsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.MORE) {
                MoreScreen(
                    onManageCategories = { navController.navigate(Routes.CATEGORIES) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.CATEGORIES) {
                CategoryManagementScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
