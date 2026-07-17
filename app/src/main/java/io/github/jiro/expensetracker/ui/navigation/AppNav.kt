package io.github.jiro.expensetracker.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.jiro.expensetracker.LocalPendingMemberCardNavId
import io.github.jiro.expensetracker.MainActivity
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.ui.accounts.AccountDetailScreen
import io.github.jiro.expensetracker.ui.accounts.AccountsListScreen
import io.github.jiro.expensetracker.ui.accounts.AddEditAccountScreen
import io.github.jiro.expensetracker.ui.add_edit.AddEditTransactionScreen
import io.github.jiro.expensetracker.ui.add_edit.ReceiptSectionViewModel
import io.github.jiro.expensetracker.ui.add_receipt.AddReceiptScreen
import io.github.jiro.expensetracker.ui.budget.BudgetScreen
import io.github.jiro.expensetracker.ui.cards.MemberCardCropScreen
import io.github.jiro.expensetracker.ui.cards.MemberCardDetailScreen
import io.github.jiro.expensetracker.ui.cards.MemberCardEditScreen
import io.github.jiro.expensetracker.ui.cards.MemberCardListScreen
import io.github.jiro.expensetracker.ui.categories.CategoryManagementScreen
import io.github.jiro.expensetracker.ui.conflict.ConflictScreen
import io.github.jiro.expensetracker.ui.home.HomeScreen
import io.github.jiro.expensetracker.ui.more.MoreScreen
import io.github.jiro.expensetracker.ui.receipts.ReceiptViewerScreen
import io.github.jiro.expensetracker.ui.recurring.ManageSeriesScreen
import io.github.jiro.expensetracker.ui.settings.SettingsScreen
import io.github.jiro.expensetracker.ui.settings.SettingsViewModel
import io.github.jiro.expensetracker.ui.statistics.StatisticsScreen
import io.github.jiro.expensetracker.ui.transactions.TransactionsScreen
import io.github.jiro.expensetracker.ui.trends.TrendsScreen
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val ADD_EDIT = "add_edit?id={id}"
    const val ADD_EDIT_ARG_ID = "id"
    const val ADD_EDIT_NO_ID = "add_edit"
    const val BUDGET = "budget"
    const val TRENDS = "trends"
    const val STATISTICS = "statistics"
    const val MORE = "more"
    const val CATEGORIES = "categories"
    const val SETTINGS = "settings"
    const val ADD_RECEIPT = "add_receipt"
    const val MANAGE_SERIES = "manage_series/{groupId}"
    const val MANAGE_SERIES_ARG_GROUP_ID = "groupId"
    const val RECEIPT_VIEWER = "receipts/viewer?path={path}"
    const val RECEIPT_VIEWER_ARG_PATH = "path"
    const val ACCOUNTS_LIST = "accounts_list"
    const val ACCOUNT_DETAIL = "account_detail/{accountId}"
    const val ACCOUNT_DETAIL_ARG_ID = "accountId"
    const val ACCOUNT_EDIT = "account_edit"
    const val ACCOUNT_EDIT_WITH_ID = "account_edit/{accountId}"
    const val ACCOUNT_EDIT_ARG_ID = "accountId"
    const val MEMBER_CARDS = "member_cards"
    const val MEMBER_CARDS_DETAIL = "member_cards/{cardId}"
    const val MEMBER_CARDS_DETAIL_ARG_ID = "cardId"
    const val MEMBER_CARDS_EDIT = "member_cards/edit?id={cardId}"
    const val MEMBER_CARDS_EDIT_ARG_ID = "cardId"
    const val MEMBER_CARDS_EDIT_NO_ID = "member_cards/edit"
    const val MEMBER_CARDS_CROP = "member_cards/crop?uri={uri}"
    const val MEMBER_CARDS_CROP_ARG_URI = "uri"
    const val CONFLICT = "conflict?remote={remote}&local={local}"
    const val CONFLICT_ARG_REMOTE = "remote"
    const val CONFLICT_ARG_LOCAL = "local"
    const val INVESTMENT_ACCOUNT_DETAIL = "investment_account/{accountId}"
    const val INVESTMENT_ACCOUNT_DETAIL_ARG_ID = "accountId"
    const val INVESTMENT_HOLDING_EDIT = "investment_account/{accountId}/holding?id={holdingId}"
    const val INVESTMENT_HOLDING_EDIT_ARG_ACCOUNT_ID = "accountId"
    const val INVESTMENT_HOLDING_EDIT_ARG_HOLDING_ID = "holdingId"
}

fun addEditRoute(transactionId: Long? = null): String =
    if (transactionId == null) Routes.ADD_EDIT_NO_ID else "add_edit?id=$transactionId"

fun memberCardEditRoute(cardId: Long? = null): String =
    if (cardId == null) Routes.MEMBER_CARDS_EDIT_NO_ID else "member_cards/edit?id=$cardId"

/**
 * Build the crop route for a given source URI. The URI is URL-encoded so a
 * `content://` URI with query params survives the round-trip.
 */
fun memberCardCropRoute(sourceUri: String): String {
    val encoded = java.net.URLEncoder.encode(sourceUri, "UTF-8")
    return "member_cards/crop?uri=$encoded"
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    activity: MainActivity? = null,
) {
    // Per-tab reselect counters. The bottom nav increments the matching
    // counter when the user taps an already-active tab; the corresponding
    // screen observes the counter and scrolls its LazyColumn to the top.
    // This is what makes tapping "Home" while already on Home do something
    // visible (jump-to-top), instead of being a silent no-op.
    var homeReselectCount by remember { mutableIntStateOf(0) }
    var transactionsReselectCount by remember { mutableIntStateOf(0) }

    // Holds the absolute path of a freshly-cropped image, set by the crop
    // screen and consumed by the Edit screen on the next recomposition.
    // Survives recomposition; cleared once Edit has handed it to its VM.
    val pendingCroppedPath = remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val ocrSnackbarMessage = stringResource(R.string.receipt_ocr_snackbar)

    // Consume widget deep-links. MainActivity stashes the card id from
    // EXTRA_MEMBER_CARD_ID into pendingMemberCardNavId; we navigate and
    // clear the field so we don't replay it on subsequent recompositions.
    // Sentinel `0L` from the widget's empty-state "Add card" CTA means
    // "open the Add screen" rather than detail-of-card-0.
    val pendingMemberCardId = if (activity != null) {
        LocalPendingMemberCardNavId.current
    } else null
    LaunchedEffect(pendingMemberCardId) {
        if (pendingMemberCardId != null && activity != null) {
            val route = if (pendingMemberCardId == 0L) {
                Routes.MEMBER_CARDS_EDIT_NO_ID
            } else {
                "member_cards/$pendingMemberCardId"
            }
            navController.navigate(route)
            activity.pendingMemberCardNavId = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    onNavigateToBudget = { navController.navigate(Routes.BUDGET) },
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
                    onManageSeries = { groupId ->
                        navController.navigate("manage_series/$groupId")
                    },
                    onOpenReceipt = { path ->
                        navController.navigate("receipts/viewer?path=$path")
                    },
                    onOcrSnackbar = {
                        snackbarScope.launch {
                            snackbarHostState.showSnackbar(ocrSnackbarMessage)
                        }
                    },
                )
            }
            composable(
                route = Routes.MANAGE_SERIES,
                arguments = listOf(
                    navArgument(Routes.MANAGE_SERIES_ARG_GROUP_ID) { type = NavType.StringType },
                ),
            ) {
                ManageSeriesScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.RECEIPT_VIEWER,
                arguments = listOf(
                    navArgument(Routes.RECEIPT_VIEWER_ARG_PATH) { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString(Routes.RECEIPT_VIEWER_ARG_PATH).orEmpty()
                ReceiptViewerScreen(
                    receiptPath = path,
                    receiptRepository = hiltViewModel<ReceiptSectionViewModel>().receiptRepository,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.BUDGET) { BudgetScreen() }
            composable(Routes.TRENDS) { TrendsScreen() }
            composable(Routes.STATISTICS) { StatisticsScreen() }
            composable(Routes.ACCOUNTS_LIST) {
                AccountsListScreen(
                    onBack = { navController.popBackStack() },
                    onAddAccount = { navController.navigate(Routes.ACCOUNT_EDIT) },
                    onAccountClick = { id -> navController.navigate("account_detail/$id") },
                )
            }
            composable(
                route = Routes.ACCOUNT_DETAIL,
                arguments = listOf(
                    navArgument(Routes.ACCOUNT_DETAIL_ARG_ID) { type = NavType.LongType },
                ),
            ) {
                AccountDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEditAccount = { id -> navController.navigate("account_edit/$id") },
                    onTransactionClick = { txnId -> navController.navigate(addEditRoute(txnId)) },
                )
            }
            composable(Routes.ACCOUNT_EDIT) {
                AddEditAccountScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.ACCOUNT_EDIT_WITH_ID,
                arguments = listOf(
                    navArgument(Routes.ACCOUNT_EDIT_ARG_ID) { type = NavType.LongType },
                ),
            ) {
                AddEditAccountScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.MEMBER_CARDS) {
                MemberCardListScreen(
                    onBack = { navController.popBackStack() },
                    onAddCard = { navController.navigate(memberCardEditRoute()) },
                    onCardClick = { id -> navController.navigate("member_cards/$id") },
                )
            }
            composable(
                route = Routes.MEMBER_CARDS_DETAIL,
                arguments = listOf(navArgument(Routes.MEMBER_CARDS_DETAIL_ARG_ID) { type = NavType.LongType }),
            ) {
                MemberCardDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate(memberCardEditRoute(id)) },
                )
            }
            composable(
                route = Routes.MEMBER_CARDS_EDIT,
                arguments = listOf(
                    navArgument(Routes.MEMBER_CARDS_EDIT_ARG_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) {
                MemberCardEditScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                    onNavigateToCrop = { uri ->
                        navController.navigate(memberCardCropRoute(uri))
                    },
                    pendingCroppedPath = pendingCroppedPath.value,
                    onCroppedPathConsumed = { pendingCroppedPath.value = null },
                )
            }
            composable(
                route = Routes.MEMBER_CARDS_CROP,
                arguments = listOf(
                    navArgument(Routes.MEMBER_CARDS_CROP_ARG_URI) { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val encoded = backStackEntry.arguments
                    ?.getString(Routes.MEMBER_CARDS_CROP_ARG_URI).orEmpty()
                val uri = java.net.URLDecoder.decode(encoded, "UTF-8")
                MemberCardCropScreen(
                    sourceUri = uri,
                    onCancel = { navController.popBackStack() },
                    onCropped = { path ->
                        pendingCroppedPath.value = path
                        navController.popBackStack()
                    },
                )
            }
            composable(Routes.MORE) {
                MoreScreen(
                    onManageAccounts = { navController.navigate(Routes.ACCOUNTS_LIST) },
                    onManageCategories = { navController.navigate(Routes.CATEGORIES) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onAddReceipt = { navController.navigate(Routes.ADD_RECEIPT) },
                    onOpenCards = { navController.navigate(Routes.MEMBER_CARDS) },
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
                    onConflictClick = { navController.navigate(Routes.CONFLICT) },
                )
            }
            composable(
                route = Routes.CONFLICT,
                arguments = listOf(
                    navArgument(Routes.CONFLICT_ARG_REMOTE) {
                        type = NavType.StringType
                        defaultValue = ""
                        nullable = true
                    },
                    navArgument(Routes.CONFLICT_ARG_LOCAL) {
                        type = NavType.StringType
                        defaultValue = ""
                        nullable = true
                    },
                ),
            ) { backStackEntry ->
                val remoteB64 = backStackEntry.arguments?.getString(Routes.CONFLICT_ARG_REMOTE).orEmpty()
                val localB64 = backStackEntry.arguments?.getString(Routes.CONFLICT_ARG_LOCAL).orEmpty()
                val emptyBody = io.github.jiro.expensetracker.sync.BackupBody(
                    accounts = emptyList(),
                    categories = emptyList(),
                    transactions = emptyList(),
                )
                val emptySnapshot = io.github.jiro.expensetracker.sync.SyncSnapshot(
                    body = emptyBody,
                    lastModifiedEpochMillis = 0L,
                    deviceId = "",
                    checksum = "",
                )
                val remote = runCatching {
                    java.net.URLDecoder.decode(remoteB64, "UTF-8")
                        .takeIf { it.isNotEmpty() }
                        ?.let { io.github.jiro.expensetracker.sync.SyncSnapshotCodec.decode(it) }
                }.getOrNull() ?: emptySnapshot
                val local = runCatching {
                    java.net.URLDecoder.decode(localB64, "UTF-8")
                        .takeIf { it.isNotEmpty() }
                        ?.let { io.github.jiro.expensetracker.sync.SyncSnapshotCodec.decode(it) }
                }.getOrNull() ?: emptySnapshot
                val settingsVm: SettingsViewModel = hiltViewModel()
                ConflictScreen(
                    remote = remote,
                    local = local,
                    onBack = { navController.popBackStack() },
                    onResolved = {
                        settingsVm.onConflictResolved()
                        navController.popBackStack()
                    },
                )
            }
            composable(Routes.ADD_RECEIPT) {
                AddReceiptScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
