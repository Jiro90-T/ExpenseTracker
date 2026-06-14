# Phase 2.8 — Receipt Save & Budget Alerts — Design

**Status:** Approved 2026-06-14
**Phase:** 2.8
**Predecessors:** Phase 2.4 (Receipts) ships the receipt viewer and the data layer. Phase 2.3 (Budgets) ships the budget repository and the per-category `BudgetEntity`.

## Goal

Add two independent features to round out the existing receipt and budget surfaces:

1. **Receipt "Save to Photos" + Share** — adds two affordances to the receipt viewer so users can export their receipt images out of the app. Share goes through the system share sheet (existing pattern); Save to Photos inserts directly into the device's photo library.
2. **Budget overspend on Home** — surfaces a "Budget Alerts" section on the Home dashboard listing categories where spending has crossed 100% of the monthly cap.

Out of scope (intentional, deferred): multi-receipt "save all", pinch-to-zoom, auto-save on capture, system-tray budget notifications, budget rollover, multi-currency budgets, animated alerts, snackbar "undo a save".

## Feature A: Receipt "Save to Photos" + Share

### User-visible behavior

When the user opens the receipt viewer (`ReceiptViewerScreen`):

- The top app bar gains two new action buttons in the `actions` slot, to the left of the back button:
  - **Share** — `Icons.Filled.Share`. Dispatches `Intent.ACTION_SEND` via the system share sheet, letting the user pick the destination app (Photos, Files, Drive, etc.).
  - **Save to Photos** — `Icons.Filled.PhotoLibrary`. Inserts the current receipt image directly into the device's photo library via `MediaStore`. No intermediate picker.
- Both buttons are disabled when the receipt path is null/empty.
- On success of Save to Photos, a snackbar "Saved to Photos" shows.
- On failure, a snackbar "Save failed: <message>" shows (and the same for Share).

### Data model

No schema changes. The receipt images already live in `<filesDir>/receipts/`. The new feature reads them and writes to the photo library (which is the OS's concern, not our DB).

### New types

**`ReceiptSaver` (helper, ~80 lines)** — `app/src/main/java/io/github/jiro/expensetracker/ui/receipts/ReceiptSaver.kt`:

A small class with one method:
```kotlin
class ReceiptSaver(private val context: Context) {
    /**
     * Save a receipt image to the device's photo library. Returns the inserted
     * content URI on success, or null on failure.
     *
     * Strategy:
     * - Android 10+ (API 29+): MediaStore.Images.Media with IS_PENDING flag.
     *   No permission needed.
     * - Android 9 and below: legacy MediaStore insert. Requires
     *   WRITE_EXTERNAL_STORAGE (declared in manifest with maxSdkVersion="28").
     */
    suspend fun saveToPhotos(sourceFile: File, displayName: String): Uri?
}
```

**`ContentValuesRecipe` + `buildContentValues` (pure helper, in the same file, ~20 lines):**

```kotlin
data class ContentValuesRecipe(
    val collection: ContentUri,
    val isPending: Boolean,
    val mimeType: String,
    val displayName: String,
)

enum class ContentUri { ExternalPrimary, ExternalLegacy }

/**
 * Pure: picks the right MediaStore collection URI and the right
 * ContentValues flags for a given SDK + MIME type + display name. JVM-testable
 * (no Android imports — uses the [ContentUri] enum).
 */
internal fun buildContentValues(
    sdkInt: Int,
    mimeType: String,
    displayName: String,
): ContentValuesRecipe
```

`buildContentValues` picks `ContentUri.ExternalPrimary` + `isPending = true` for SDK 29+, else `ContentUri.ExternalLegacy` + `isPending = false`. The function returns a recipe, not a real `ContentValues`, so it's JVM-testable without Robolectric. The caller (`saveToPhotos`) converts the recipe to a real `ContentValues` on the device.

**`SaveResult` (sealed, in `ReceiptViewerViewModel.kt`):**

```kotlin
sealed interface SaveResult {
    data class Success(val uri: Uri) : SaveResult
    data class Failure(val message: String) : SaveResult
}
```

### New VM

**`ReceiptViewerViewModel` (new, ~70 lines)** — `app/src/main/java/io/github/jiro/expensetracker/ui/receipts/ReceiptViewerViewModel.kt`:

A Hilt VM with:
- `@Inject constructor(@ApplicationContext context: Context, receiptRepository: ReceiptRepository)`.
- `suspend fun shareReceipt(receiptPath: String): Intent?` — uses `FileProvider.getUriForFile` (authority from `BackupManager.AUTHORITY` or a sibling constant; see Components), wraps the URI in an `Intent.ACTION_SEND` with `type = "image/*"`, `EXTRA_STREAM = uri`, `FLAG_GRANT_READ_URI_PERMISSION`, `addFlags(FLAG_ACTIVITY_NEW_TASK)`. Returns the intent for the screen to wrap in `Intent.createChooser` and start.
- `suspend fun saveReceiptToPhotos(receiptPath: String): SaveResult` — decodes the receipt file, instantiates `ReceiptSaver`, calls `saveToPhotos`, returns the result.

The VM is the only place that touches `Context`. The screen stays declarative.

### Components

| File | Purpose |
| --- | --- |
| `ui/receipts/ReceiptSaver.kt` (new) | `ReceiptSaver` class + `buildContentValues` pure helper + `ContentValuesRecipe` + `ContentUri` enum. |
| `ui/receipts/ReceiptViewerViewModel.kt` (new) | Hilt VM with `shareReceipt` and `saveReceiptToPhotos` methods. |
| `ui/receipts/ReceiptViewerScreen.kt` (modified) | Adds 2 `IconButton`s in the TopAppBar's `actions` slot. Takes 2 new callbacks from the VM. Shows snackbar on result. |
| `app/src/main/AndroidManifest.xml` (modified) | Adds `<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />`. |
| `app/src/test/.../ui/receipts/ReceiptSaverTest.kt` (new) | JUnit tests for `buildContentValues`. |
| `res/values/strings.xml` (modified) | 4 new strings. |

### `ReceiptViewerScreen` changes

The TopAppBar's `actions` slot gets 2 `IconButton`s. They're disabled when `receiptPath` is null/empty. The screen adds:
- A `SnackbarHost` (already present? — confirm in the file).
- A `LaunchedEffect(latestEvent)` block that shows a snackbar on the latest VM event.

The current `ReceiptViewerScreen.kt` has a TopAppBar with only a back button. The new code adds 2 action buttons + a snackbar host (if not present).

The screen needs to gain `viewModel: ReceiptViewerViewModel = hiltViewModel()` and a `LocalContext.current` for the `startActivity` call.

### Manifest change

```xml
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

The `maxSdkVersion="28"` ensures the permission is only requested on Android 9 and below. Android 10+ uses the MediaStore API which doesn't require this permission. No runtime permission request is needed (the manifest declaration is enough for the legacy path).

### Tests

| Test | File | What it asserts |
| --- | --- | --- |
| `buildContentValues_sdk29Plus_usesExternalPrimaryAndIsPending` | `ReceiptSaverTest.kt` | SDK 29, 30, 33 → `ContentUri.ExternalPrimary` + `isPending = true`. |
| `buildContentValues_sdk28_usesExternalLegacyNoPending` | same | SDK 28 → `ContentUri.ExternalLegacy` + `isPending = false`. |
| `buildContentValues_sdk24_usesExternalLegacyNoPending` | same | SDK 24 (minSdk) → `ContentUri.ExternalLegacy` + `isPending = false`. |
| `buildContentValues_propagatesMimeType` | same | The recipe's `mimeType` matches the input. |
| `buildContentValues_propagatesDisplayName` | same | The recipe's `displayName` matches the input. |

(~5 tests, pure JVM, no Robolectric.)

## Feature B: Budget Overspend on Home

### User-visible behavior

When the user opens the Home tab:

- If any category has spent more than its monthly budget cap, a new "Budget Alerts" section appears at the top of the dashboard (above the existing `DashboardSummaryCard`).
- Each alert is a red-tinted card showing the category name, "Over by $X.XX", and a right-chevron icon. Tap to navigate to the Budgets screen.
- The list is sorted by overage descending (worst first).
- If no budgets are set OR no category is overspent, the section doesn't render.

### Data model

No schema changes. The `BudgetEntity` already has `categoryId`, `amountMinor`, `currencyCode`, and a `periodAnchor` (or similar — confirm in code). The new feature reads the existing budgets and the existing transactions to compute overspend.

### New types

**`BudgetAlert` (data class, in `BudgetAlerts.kt`):**

```kotlin
data class BudgetAlert(
    val categoryId: Long,
    val categoryName: String,
    val budgetMinor: Long,         // budget cap in home currency
    val spentMinor: Long,          // actual spend in home currency
    val overageMinor: Long,        // = spentMinor - budgetMinor (always > 0)
    val overageFormatted: String,  // precomputed "X.XX" string
    val homeCurrency: String,
)
```

**`computeBudgetAlerts` (pure function):**

```kotlin
/**
 * Pure: returns the list of budget alerts (categories where spentMinor >
 * budgetMinor for the current month). Sorted by overage descending (worst
 * first). All amounts are normalized to [homeCurrency] via [fxRates].
 *
 * Only considers budgets whose [BudgetEntity.monthStartEpochMs] matches the
 * start of [nowMs]'s month. Budgets from other months are out of scope for v1.
 */
fun computeBudgetAlerts(
    budgets: List<BudgetEntity>,
    spentByCategory: Map<Long, Long>,
    homeCurrency: String,
    fxRates: Map<String, Double>,
    nowMs: Long,
): List<BudgetAlert>
```

The function:
1. Filters `budgets` to those whose `monthStartEpochMs` matches the start of `nowMs`'s month.
2. For each, looks up `spentByCategory[budget.categoryId]`.
3. If `spentMinor > budget.amountMinor`, creates a `BudgetAlert` with the overage.
4. Sorts by `overageMinor` descending.
5. Pre-computes the formatted overage string using `MoneyFormat.formatAmountForEdit`.

The function is pure (with `nowMs` as a parameter for determinism), JVM-testable, and uses no Android imports.

**`computeSpentByCategory` (internal helper, also in `BudgetAlerts.kt`):**

```kotlin
/**
 * Aggregates a list of (already-currency-normalized) expense transactions
 * into a per-category total. Pure, JVM-testable.
 */
internal fun computeSpentByCategory(
    rows: List<TransactionWithCategory>,
    homeCurrency: String,
    fxRates: Map<String, Double>,
): Map<Long, Long>
```

Filters to `EXPENSE` type, groups by `categoryId`, sums after `FxConverter.convertMinor` (with 1:1 fallback if rate missing).

### `HomeViewModel` changes

Add a new flow:
```kotlin
val budgetAlerts: StateFlow<List<BudgetAlert>> = combine(
    budgetRepository.observeAll(),
    settingsRepository.homeCurrency,
    settingsRepository.fxRates,
) { budgets, home, rates -> Triple(budgets, home, rates) }
    .map { (budgets, home, rates) ->
        val thisMonthRows = periodTransactions.value  // already loaded
        val spentByCategory = computeSpentByCategory(thisMonthRows, home, rates)
        computeBudgetAlerts(budgets, spentByCategory, home, rates, nowMs = System.currentTimeMillis())
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

**Note:** the VM needs a new constructor parameter `private val budgetRepository: BudgetRepository`. This is a small injection addition.

### `HomeScreen` changes

Add a new `BudgetAlertsSection` composable rendered above the `DashboardSummaryCard` (only when `alerts.isNotEmpty()`):
```kotlin
if (alerts.isNotEmpty()) {
    item(key = "budget_alerts") {
        BudgetAlertsSection(alerts = alerts, onClick = onNavigateToBudget)
    }
}
```

Each alert is a `Card` with `colorScheme.errorContainer` background, showing:
- The category name (titleMedium)
- "Over by $X.XX" (bodyMedium, colorScheme.onErrorContainer)
- A right-chevron icon (`Icons.AutoMirrored.Filled.KeyboardArrowRight`)

Tap to navigate via the `onNavigateToBudget` callback.

`HomeScreen` gains a new parameter `onNavigateToBudget: () -> Unit`. Default value `{}` for previews.

### Navigation

`AppNav.kt` updates:
```kotlin
composable(Routes.HOME) {
    HomeScreen(
        onSeeAllTransactions = { navController.navigate(Routes.TRANSACTIONS) },
        onNavigateToBudget = { navController.navigate(Routes.BUDGET) },
        reselectTrigger = homeReselectCount,
    )
}
```

### Tests

| Test | File | What it asserts |
| --- | --- | --- |
| `computeBudgetAlerts_emptyBudgets_returnsEmpty` | `BudgetAlertsTest.kt` | `budgets = []` → `emptyList()`. |
| `computeBudgetAlerts_spentUnderBudget_noAlert` | same | Spent 80, budget 100 → no alert. |
| `computeBudgetAlerts_spentEqualToBudget_noAlert` | same | Spent 100, budget 100 → no alert (boundary). |
| `computeBudgetAlerts_spentOverBudget_oneAlert` | same | Spent 150, budget 100 → one alert with overage 50. |
| `computeBudgetAlerts_multipleOverspent_sortedByOverageDesc` | same | Two over-budget categories; the one with the larger overage is first. |
| `computeBudgetAlerts_overageAmountIsSpentMinusBudget` | same | Hand-built inputs, verify `overageMinor = spentMinor - budgetMinor`. |
| `computeBudgetAlerts_mixedSomeSomeNot_filtersCorrectly` | same | Three categories: under, over, no-budget. Only the over-budget one alerts. |
| `computeBudgetAlerts_overageFormattedIsCorrectCurrencyString` | same | `overageFormatted` matches `MoneyFormat.formatAmountForEdit(overageMinor)`. |
| `computeBudgetAlerts_noBudgetForCategoryInSpentMap_noAlert` | same | A category in the spent map with no budget → no alert. |
| `computeBudgetAlerts_purityRepeatedCalls` | same | Two calls with the same args return equal lists. |

(~10 tests, pure JVM, no Robolectric.)

## Files touched (summary)

**New:**
- `ui/receipts/ReceiptSaver.kt` — helper + pure `buildContentValues` + `ContentUri` enum.
- `ui/receipts/ReceiptViewerViewModel.kt` — Hilt VM for share + save.
- `ui/receipts/ReceiptSaverTest.kt` — 5 JVM tests.
- `ui/home/BudgetAlerts.kt` — pure `computeBudgetAlerts` + `computeSpentByCategory` + `BudgetAlert` data class.
- `ui/home/BudgetAlertsTest.kt` — 10 JVM tests.

**Modified:**
- `ui/receipts/ReceiptViewerScreen.kt` — adds 2 action buttons + snackbar handling.
- `ui/home/HomeViewModel.kt` — adds `budgetAlerts: StateFlow<List<BudgetAlert>>` + `BudgetRepository` constructor param.
- `ui/home/HomeScreen.kt` — adds `BudgetAlertsSection` + `onNavigateToBudget` parameter.
- `ui/navigation/AppNav.kt` — wires `onNavigateToBudget` callback.
- `app/src/main/AndroidManifest.xml` — adds legacy `WRITE_EXTERNAL_STORAGE` permission.
- `res/values/strings.xml` — 4 new strings (Share, Save to Photos, Snackbar success, Snackbar failure).

## Strings to add

```
receipt_action_share          "Share"
receipt_action_save_to_photos "Save to Photos"
receipt_save_success          "Saved to Photos"
receipt_save_failed           "Save failed: %1$s"
home_budget_alerts_header    "Budget alerts"
home_budget_alert_over_by    "Over by %1$s"
home_budget_navigate         "Open Budgets"
```

(7 new strings total — 4 for receipts, 3 for budget alerts.)

## Edge cases

| Case | Behavior |
| --- | --- |
| Receipt with no file | Both Share and Save buttons are disabled. |
| Multi-page PDF, user on page 2 | Save/Share operate on the currently-rendered page (page index from the existing `pagerState`). |
| Storage permission denied on Android 9 | Save fails, snackbar "Save failed: permission denied". User can grant in system settings and retry. |
| MediaStore insert fails (disk full, etc.) | Same snackbar treatment with the actual exception message. |
| Share intent with no app that handles image/* | `startActivity` throws `ActivityNotFoundException`; caught, snackbar "Save failed: no app available". (Or use `Intent.createChooser` which always shows a chooser, avoiding this case entirely.) |
| No budgets set | `budgetAlerts` is empty, section doesn't render. |
| Budgets exist but no overspend | `budgetAlerts` is empty, section doesn't render. |
| Budgets set in a non-home currency, no FX rate | Spent is 1:1 with home (per the existing `FxConverter.convertMinor` fallback). The alert may be inaccurate, but no crash. |
| User taps a budget alert | Navigates to `Routes.BUDGET`. (No deep link to a specific category for v1.) |
| 10+ overspent categories | The list shows all of them. No pagination in v1; a future polish pass could add "Show all" with collapse. |
| `nowMs` shifts across a month boundary | Like Phase 2.6/2.7: the `budgetAlerts` doesn't re-tick. The user re-navigating or reopening Home re-evaluates. |
| Period in `BudgetEntity` is not the current month | Out of scope for v1 — these budgets (with a different `monthStartEpochMs`) are filtered out. |

## Out of scope (intentional, deferred)

- **Multi-receipt "save all"**: only the current page is saved.
- **Pinch-to-zoom on the image viewer**: deferred from Phase 2.4.
- **Auto-save on receipt capture**: still requires the user to tap Save.
- **System-tray budget notifications**: still deferred. The "inline warning card" is the v1 surface.
- **Budget rollover** (carryover of unused budget to next month).
- **Multi-currency budgets** (e.g. EUR budget for travel). The current `BudgetEntity` has a single `currencyCode`.
- **Animated alerts** (fade-in on appearance).
- **Snackbar action to undo a save**: deleting from the photo library is the OS's concern.
- **Deep-link from a budget alert to a specific category's edit screen** in the Budget screen.
- **Empty `BudgetAlerts` placeholder card** ("All good — no overspend this month"). Defer; absence of the section IS the all-good signal.

## Open questions

None. Decisions were taken one at a time during brainstorming and recorded in the User-visible behavior, Data model, Components, and Edge cases sections above.
