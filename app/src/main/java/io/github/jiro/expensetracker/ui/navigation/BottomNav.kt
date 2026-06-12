package io.github.jiro.expensetracker.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.jiro.expensetracker.R

internal enum class BottomTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Home(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    Transactions(Routes.TRANSACTIONS, R.string.nav_transactions, Icons.AutoMirrored.Filled.List),
    // Add is a button, not a destination. Tapping it navigates to the
    // add-edit route via the onAddClick callback wired from AppNavHost.
    Budget(Routes.BUDGET, R.string.nav_budget, Icons.Filled.AccountBalanceWallet),
    Reports(Routes.TRENDS, R.string.nav_trends, Icons.Filled.Assessment),
    More(Routes.MORE, R.string.nav_more, Icons.Filled.MoreHoriz),
}

/** Short label overrides for tabs whose full name doesn't fit in the bar. */
private val BottomTab.shortLabel: String?
    @Composable get() = when (this) {
        BottomTab.Transactions -> stringResource(R.string.nav_transactions_short)
        else -> null
    }

@Composable
internal fun AppBottomBar(
    navController: NavHostController,
    onAddClick: () -> Unit,
    onTabReselected: (route: String) -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    NavigationBar {
        // Left side: Home, Transactions
        BottomTab.entries.take(2).forEach { tab ->
            val isCurrent = currentDestination?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = isCurrent,
                onClick = {
                    if (isCurrent) {
                        onTabReselected(tab.route)
                    } else {
                        navigateToTab(navController, tab.route)
                    }
                },
                icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                label = {
                    NavBarLabel(
                        fullRes = tab.labelRes,
                        shortOverride = tab.shortLabel,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }

        // Center: a prominent Add button (FAB-style inside the bar).
        NavigationBarItem(
            selected = false,
            onClick = onAddClick,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.action_add_transaction),
                    modifier = Modifier
                        .size(28.dp)
                        .padding(2.dp),
                )
            },
            label = { NavBarLabel(fullRes = R.string.nav_add, shortOverride = null) },
            alwaysShowLabel = true,
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                unselectedIconColor = MaterialTheme.colorScheme.onPrimary,
                selectedTextColor = MaterialTheme.colorScheme.onPrimary,
                unselectedTextColor = MaterialTheme.colorScheme.onPrimary,
                indicatorColor = MaterialTheme.colorScheme.primary,
            ),
        )

        // Right side: Budget, Reports, More
        BottomTab.entries.drop(2).forEach { tab ->
            val isCurrent = currentDestination?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = isCurrent,
                onClick = {
                    if (isCurrent) {
                        onTabReselected(tab.route)
                    } else {
                        navigateToTab(navController, tab.route)
                    }
                },
                icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                label = {
                    NavBarLabel(
                        fullRes = tab.labelRes,
                        shortOverride = tab.shortLabel,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

/**
 * Nav-bar label that uses a shortened override when one is provided (the
 * full name is kept in the icon's contentDescription for accessibility), and
 * constrains to a single line so a long label can't wrap and break the
 * icon alignment above it.
 */
@Composable
private fun NavBarLabel(fullRes: Int, shortOverride: String?) {
    Text(
        text = shortOverride ?: stringResource(fullRes),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun navigateToTab(navController: NavHostController, route: String) {
    // Simplest reliable bottom-nav pattern: pop everything to the start
    // destination, then push the new tab. No saveState / restoreState —
    // each tab is a fresh instance on each visit. Scroll position is not
    // preserved across tab switches; if you want that, the saved-state
    // pattern can come back as a follow-up.
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            inclusive = true
        }
        launchSingleTop = true
    }
}
