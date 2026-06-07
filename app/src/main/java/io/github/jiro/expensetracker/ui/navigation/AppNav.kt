package io.github.jiro.expensetracker.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.jiro.expensetracker.ui.add_edit.AddEditTransactionScreen
import io.github.jiro.expensetracker.ui.categories.CategoryManagementScreen
import io.github.jiro.expensetracker.ui.home.HomeScreen
import io.github.jiro.expensetracker.ui.reports.ReportsScreen
import io.github.jiro.expensetracker.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val ADD_EDIT = "add_edit?id={id}"
    const val ADD_EDIT_ARG_ID = "id"
    const val ADD_EDIT_NO_ID = "add_edit"
    const val CATEGORIES = "categories"
    const val REPORTS = "reports"
    const val SETTINGS = "settings"
}

/** Builds the "add_edit" route for a given (optional) transaction id. */
fun addEditRoute(transactionId: Long? = null): String =
    if (transactionId == null) Routes.ADD_EDIT_NO_ID else "add_edit?id=$transactionId"

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
) {
    Scaffold(
        bottomBar = {
            AppBottomBar(
                navController = navController,
                onAddClick = { navController.navigate(addEditRoute()) },
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
                    onEditClick = { id -> navController.navigate(addEditRoute(id)) },
                    onManageCategories = { navController.navigate(Routes.CATEGORIES) },
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
            composable(Routes.CATEGORIES) {
                CategoryManagementScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.REPORTS) {
                ReportsScreen(
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
