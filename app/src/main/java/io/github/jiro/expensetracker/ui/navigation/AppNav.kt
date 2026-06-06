package io.github.jiro.expensetracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.jiro.expensetracker.ui.add_edit.AddEditTransactionScreen
import io.github.jiro.expensetracker.ui.home.HomeScreen

object Routes {
    const val HOME = "home"
    const val ADD_EDIT = "add_edit?id={id}"
    const val ADD_EDIT_ARG_ID = "id"
    const val ADD_EDIT_NO_ID = "add_edit" // convenience for the "new" case
}

/** Builds the "add_edit" route for a given (optional) transaction id. */
fun addEditRoute(transactionId: Long? = null): String =
    if (transactionId == null) Routes.ADD_EDIT_NO_ID else "add_edit?id=$transactionId"

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onAddClick = { navController.navigate(addEditRoute()) },
                onEditClick = { id -> navController.navigate(addEditRoute(id)) },
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
    }
}
