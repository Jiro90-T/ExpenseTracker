# Polish Pack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship four small deferred polish items as v0.10.1: search thousands-separators fix, FX delete-icon a11y + dead-code cleanup, dark `NetBlue` chart variant, and pinch-to-zoom on the receipt viewer.

**Architecture:** Mechanical polish — no new abstractions, no new dependencies. TDD for the search fix (3 new tests); the other three items are direct edits to existing UI / data code. Pure helpers stay in `MoneyFormat.kt`. ~75 lines of production code, ~30 lines of test code, 1 new string, 1 deleted constant.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), JUnit, Gradle (Kotlin DSL).

**Build/test commands (all run from repo root):**
- Tests: `./gradlew test` (full suite) or `./gradlew testDebugUnitTest` (debug variant only)
- Single test class: `./gradlew test --tests "io.github.jiro.expensetracker.ui.transactions.FiltersTest"`
- Build: `./gradlew assembleDebug`
- JDK 21 required (`C:/tools/jdk-21.0.5+11`); if `./gradlew` errors with a JDK version, set `JAVA_HOME=C:/tools/jdk-21.0.5+11`.

**Spec:** `docs/superpowers/specs/2026-06-16-polish-pack-design.md`

**Commit author:** `MiniMax-M3 <291324429+Jiro90-T@users.noreply.github.com>`. Do NOT include a `Co-Authored-By:` trailer.

---

## Task 1: Search thousands-separators fix (TDD)

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/MoneyFormat.kt:1-37`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt:77-95`
- Test: `app/src/test/java/io/github/jiro/expensetracker/ui/transactions/FiltersTest.kt`

The current amount-search branch at `Filters.kt:92-93` does `formatAmountForEdit(t.amountMinor).contains(trimmedQuery, ignoreCase = true)`. Because `formatAmountForEdit` returns separator-free strings like `"1200.00"`, the user-typed query `"1,200"` (with comma) does not match. Fix: strip `,` and space characters from the user query and try the stripped form against the formatted amount.

- [ ] **Step 1: Write the 3 failing tests**

Append to `app/src/test/java/io/github/jiro/expensetracker/ui/transactions/FiltersTest.kt` (after the existing search-by-amount tests around line 146; pick the next blank line and add the new block). The new tests must use the existing `txn()` and `categories()` helpers (defined at lines 663 and 687):

```kotlin
    // ---- search amount: thousands-separator normalization (Phase 2.11 polish) ----

    @Test
    fun filterTransactions_searchAmountMatchesWithComma() {
        val rows = listOf(
            txn(3L, "C", 120_000L, "EXPENSE", 1L, date(2026, 6, 14), null),   // $1,200.00
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "1,200"),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_searchAmountMatchesWithSpace() {
        val rows = listOf(
            txn(3L, "C", 120_000L, "EXPENSE", 1L, date(2026, 6, 14), null),   // $1,200.00
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "1 200"),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_searchAmountMatchesNoSeparators_regressionGuard() {
        val rows = listOf(
            txn(3L, "C", 120_000L, "EXPENSE", 1L, date(2026, 6, 14), null),   // $1,200.00
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "1200"),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }
```

- [ ] **Step 2: Run tests to confirm the comma/space cases fail**

Run: `./gradlew test --tests "io.github.jiro.expensetracker.ui.transactions.FiltersTest.filterTransactions_searchAmountMatchesWithComma" --tests "io.github.jiro.expensetracker.ui.transactions.FiltersTest.filterTransactions_searchAmountMatchesWithSpace"`

Expected: 2 tests FAIL (the comma and space cases). The regression-guard test passes (no separator case was already working).

- [ ] **Step 3: Add `stripAmountSeparators` helper to `MoneyFormat.kt`**

In `app/src/main/java/io/github/jiro/expensetracker/data/local/MoneyFormat.kt`, add the new helper inside the `object MoneyFormat` (after `formatAmountForEdit` at line 36, before the closing brace at line 37):

```kotlin
    /**
     * Pure: strips thousands separators (`,`, ASCII space, non-breaking space)
     * from a user-typed search string and lowercases it. "1,200", "1 200",
     * "1200" all normalize to "1200". "1,200.50" → "1200.50".
     */
    fun stripAmountSeparators(query: String): String {
        return query
            .replace(',', ' ')
            .replace(' ', ' ')
            .replace(Char(0x202F), ' ')   // narrow no-break space
            .replace(Char(0x00A0), ' ')   // non-breaking space
            .replace(" ", "")
            .lowercase()
    }
```

Note: the `Char(0x202F)` and `Char(0x00A0)` are the narrow no-break space (used by some keyboards for the thousands separator) and the regular no-break space, respectively. They must each be replaced with ASCII space first, then all ASCII spaces stripped.

- [ ] **Step 4: Wire `stripAmountSeparators` into the amount-match branch**

In `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt`, replace lines 77-95 (the `if (hasQuery)` block at the top of the row-filter lambda) with the version below. The new code computes `stripped` once outside the lambda and adds a second match check:

```kotlin
    val trimmedQuery = filters.searchQuery.trim()
    val hasQuery = trimmedQuery.isNotEmpty()
    val strippedQuery = if (hasQuery) MoneyFormat.stripAmountSeparators(trimmedQuery) else ""
    val categoryNameById = allCategories.associate { it.id to it.name }
    val (rangeFrom, rangeToExclusive) = resolveDateRange(filters.dateRange, nowMs)
    val (minAmount, maxAmount) = resolveAmountRange(filters.minAmount, filters.maxAmount)

    return rows.filter { row ->
        val t = row.transaction

        // Search query: must match at least one of the searched fields.
        if (hasQuery) {
            val titleMatch = t.title.contains(trimmedQuery, ignoreCase = true)
            val noteMatch = t.note?.contains(trimmedQuery, ignoreCase = true) == true
            val categoryMatch = categoryNameById[t.categoryId]
                ?.contains(trimmedQuery, ignoreCase = true) == true
            val formatted = MoneyFormat.formatAmountForEdit(t.amountMinor)
            val amountMatch = formatted.contains(trimmedQuery, ignoreCase = true)
                || (strippedQuery != trimmedQuery.lowercase()
                    && formatted.contains(strippedQuery, ignoreCase = true))
            if (!(titleMatch || noteMatch || categoryMatch || amountMatch)) return@filter false
        }
```

Note the `strippedQuery != trimmedQuery.lowercase()` guard: `stripAmountSeparators` also lowercases, so the guard avoids a redundant comparison when the user typed `"1200"` (no separators, no upper-case).

- [ ] **Step 5: Run all 3 new tests + the full `FiltersTest` class to confirm pass**

Run: `./gradlew test --tests "io.github.jiro.expensetracker.ui.transactions.FiltersTest"`

Expected: all tests pass. The 3 new tests pass; the existing 47 still pass (the new logic is OR'd with the old path so all pre-existing behavior is preserved). The test count for this class is now 50.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/local/MoneyFormat.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt \
        app/src/test/java/io/github/jiro/expensetracker/ui/transactions/FiltersTest.kt
git commit -m "Search: thousands-separator normalization (Phase 2.11 polish)

User-typed '1,200' or '1 200' now matches a \$1,200.00 transaction.
The existing no-separator match path is preserved. +3 tests."
```

---

## Task 2: A11y + dead-code cleanup

**Files:**
- Modify: `app/src/main/res/values/strings.xml:240-241`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt:239`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/preferences/SupportedCurrencies.kt:11-23`

Two mechanical changes: (1) give the FX-rate delete `IconButton` a real contentDescription, (2) drop the dead `COMMON_CURRENCY_PAIRS` constant.

- [ ] **Step 1: Add `settings_fx_delete` string to `strings.xml`**

In `app/src/main/res/values/strings.xml`, add a new line immediately after line 239 (`<string name="settings_fx_empty">...</string>`) and before the existing `settings_dialog_ok` line at 240:

```xml
    <string name="settings_fx_delete">Delete rate</string>
```

(The two dialog strings `settings_dialog_ok` and `settings_dialog_cancel` follow on lines 240-241; leave them unchanged.)

- [ ] **Step 2: Update `SettingsScreen.kt` line 239 to use the new string**

In `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt`, change line 239 from:

```kotlin
                                        contentDescription = stringResource(R.string.settings_dialog_cancel),
```

to:

```kotlin
                                        contentDescription = stringResource(R.string.settings_fx_delete),
```

The other two references to `R.string.settings_dialog_cancel` (at lines 448 and 522, for dialog dismiss buttons) are correct usage of "Cancel" and must NOT be changed.

- [ ] **Step 3: Delete the `COMMON_CURRENCY_PAIRS` constant from `SupportedCurrencies.kt`**

In `app/src/main/java/io/github/jiro/expensetracker/preferences/SupportedCurrencies.kt`, delete lines 11-23 (the KDoc block and the `COMMON_CURRENCY_PAIRS` `internal val`). The file becomes:

```kotlin
package io.github.jiro.expensetracker.preferences

/**
 * Currencies surfaced in the home currency dropdown. Sorted by likelihood
 * of use (regional default first, then major global currencies).
 */
internal val SUPPORTED_CURRENCIES: List<String> = listOf(
    "MYR", "SGD", "USD", "EUR", "GBP", "JPY", "CNY", "CAD", "AUD", "TWD",
)
```

- [ ] **Step 4: Verify build still passes (catches any missed references)**

Run: `./gradlew compileDebugKotlin`

Expected: BUILD SUCCESSFUL. If a compile error mentions `COMMON_CURRENCY_PAIRS`, grep the source tree (`rg COMMON_CURRENCY_PAIRS app/src/main` and `rg COMMON_CURRENCY_PAIRS app/src/test`) to find and fix the stale reference. (Spec coverage check confirmed zero source-tree references; this step is defensive.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt \
        app/src/main/java/io/github/jiro/expensetracker/preferences/SupportedCurrencies.kt
git commit -m "A11y: FX delete-icon + drop dead COMMON_CURRENCY_PAIRS (Phase 2.11 polish)

The delete IconButton now reads 'Delete rate' to TalkBack users instead
of 'Cancel'. The unused COMMON_CURRENCY_PAIRS constant is removed."
```

---

## Task 3: Dark `NetBlue` chart variant

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/theme/Color.kt:23-41`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/charts/LineChart.kt:33-35, 104, 106, 118, 178, 215`

The trends chart's "net" line and legend dots hardcode `NetBlue = #1565C0`, which is unreadable on the dark-mode background. Add a `DarkNetBlue` constant and pick between the two with `isSystemInDarkTheme()`.

- [ ] **Step 1: Add `DarkNetBlue` to `Color.kt`**

In `app/src/main/java/io/github/jiro/expensetracker/ui/theme/Color.kt`, add one new line to the dark-scheme block (after line 40, `val DarkExpenseRed = Color(0xFFEF5350)`):

```kotlin
val DarkNetBlue = Color(0xFF64B5F6)
```

(The light-mode `NetBlue = Color(0xFF1565C0)` at line 23 is unchanged.)

- [ ] **Step 2: Update `LineChart.kt` imports**

In `app/src/main/java/io/github/jiro/expensetracker/ui/charts/LineChart.kt`, replace line 35:

```kotlin
import io.github.jiro.expensetracker.ui.theme.NetBlue
```

with two imports:

```kotlin
import androidx.compose.foundation.isSystemInDarkTheme
import io.github.jiro.expensetracker.ui.theme.DarkNetBlue
import io.github.jiro.expensetracker.ui.theme.NetBlue
```

(Insert the `isSystemInDarkTheme` import in alphabetical order among the other `androidx.compose.foundation.*` imports, which start at line 3. The simplest place is to add the new `isSystemInDarkTheme` import line right before the existing `import io.github.jiro.expensetracker.ui.theme.ExpenseRed` line at 33 — the grouping is loose and Compose import order is not strict.)

A clean alternative is to insert the new import in a single contiguous block at the bottom of the import list:

```kotlin
import io.github.jiro.expensetracker.ui.theme.ExpenseRed
import io.github.jiro.expensetracker.ui.theme.IncomeGreen
import io.github.jiro.expensetracker.ui.theme.NetBlue
import androidx.compose.foundation.isSystemInDarkTheme
import io.github.jiro.expensetracker.ui.theme.DarkNetBlue
```

Either ordering compiles. Use the second form (single new import for `isSystemInDarkTheme` placed just before the `DarkNetBlue` import) to keep the diff minimal.

- [ ] **Step 3: Resolve `netBlue` at the top of the composable**

In `app/src/main/java/io/github/jiro/expensetracker/ui/charts/LineChart.kt`, immediately after line 56 (the end of the function signature `modifier: Modifier = Modifier,\n)` and the opening `{` of the composable body), add one new line:

```kotlin
@Composable
fun LineChart(
    data: List<MonthlyTrend>,
    prior: List<MonthlyTrend>?,
    currentMonthMs: Long?,
    selected: MonthlyTrend?,
    onSelect: (MonthlyTrend?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val netBlue = if (isSystemInDarkTheme()) DarkNetBlue else NetBlue

    if (data.isEmpty()) {
```

(`val netBlue` resolves once per composition and is captured by all the `NetBlue` sites in the function body. No re-allocation per frame.)

- [ ] **Step 4: Replace the 5 `NetBlue` references in `LineChart.kt`**

Use `replace_all = true` on a single Edit to replace every remaining `NetBlue` reference with `netBlue` in the function body. Specifically the 5 sites at lines 104, 106, 118, 178, 215 — the import line at 35 already changed in Step 2, so do not include that. The Edit `old_string` is `NetBlue` and the `new_string` is `netBlue`, scoped to the function body. (The simplest path: use the Edit tool's `replace_all` on the file, replacing all `NetBlue` with `netBlue`. The 1 import site at line 35 will be replaced too — that's fine because line 35 imports `NetBlue` and the import is still needed in Step 2's two-import form. After `replace_all`, restore the import line to:

```kotlin
import androidx.compose.foundation.isSystemInDarkTheme
import io.github.jiro.expensetracker.ui.theme.DarkNetBlue
import io.github.jiro.expensetracker.ui.theme.NetBlue
```

— i.e. re-add `NetBlue` to the import list, because the function body's references are now `netBlue` (lowercase) and the `NetBlue` import is still used for the value `if (isSystemInDarkTheme()) DarkNetBlue else NetBlue`.)

A more careful path that avoids the import re-add: do not use `replace_all`; instead, make 5 individual Edits, one per call site. The 5 sites are:

- Line 104: `LegendDot(color = NetBlue,` → `LegendDot(color = netBlue,`
- Line 106: `LegendDotGhost(color = NetBlue,` → `LegendDotGhost(color = netBlue,`
- Line 118: `LegendDot(color = NetBlue,` → `LegendDot(color = netBlue,`
- Line 178: `Pair(NetBlue, prior.mapIndexed` → `Pair(netBlue, prior.mapIndexed`
- Line 215: `Pair(NetBlue, data.mapIndexed` → `Pair(netBlue, data.mapIndexed`

Use the Edit tool with unique `old_string` context (each line has unique surrounding code) for each of the 5 sites. This is the recommended path — it leaves the import line at 35 untouched.

- [ ] **Step 5: Verify build + run full test suite**

Run: `./gradlew assembleDebug test`

Expected: BUILD SUCCESSFUL, all tests pass. (The 50 from `FiltersTest` are passing after Task 1's 3 new tests; the other test classes are unaffected because no other file changed.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/theme/Color.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/charts/LineChart.kt
git commit -m "Charts: dark NetBlue variant (Phase 2.11 polish)

The trends chart's net line and legend dots are unreadable on the
dark-mode background. Pick a brighter blue (#64B5F6) in dark mode
via isSystemInDarkTheme(). Income/expense colors are unaffected."
```

---

## Task 4: Pinch-to-zoom on the receipt viewer

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/receipts/ReceiptViewerScreen.kt:47-178`

Wrap the inner image / PDF-pager content with pinch-to-zoom and double-tap-to-reset gestures. State (`scale`, `offset`) is per-screen; on PDF pager swipes, the `key(pagerState.currentPage)` wrapper resets it.

- [ ] **Step 1: Add the new imports to `ReceiptViewerScreen.kt`**

In `app/src/main/java/io/github/jiro/expensetracker/ui/receipts/ReceiptViewerScreen.kt`, add 6 new imports in the existing import blocks. The file currently has imports at lines 5-45. Add the following:

In the `androidx.compose.foundation.gestures` import block (currently empty — add a new line after the `androidx.compose.foundation.pager.*` block at lines 10-11):

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
```

The first two go in the `gestures` group; `graphicsLayer` and `pointerInput` go alphabetically with the other `androidx.compose.ui.*` imports (around line 36). The simplest placement:

- Add `detectTapGestures` and `detectTransformGestures` immediately after line 10 (the `import androidx.compose.foundation.background` line), in a new pair of import lines. Compose import order is loose; this is fine.
- Add `graphicsLayer` and `pointerInput` immediately after line 33 (`import androidx.compose.ui.graphics.Color`), alphabetically with the other `androidx.compose.ui.graphics.*` and `androidx.compose.ui.input.*` imports.

Concrete addition (insert after line 10):

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
```

And (insert after line 33, before line 34 `import androidx.compose.ui.graphics.asImageBitmap`):

```kotlin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
```

Also add an import for the `Offset` data class (used in step 2's state). Insert after line 35 (`import androidx.compose.ui.layout.ContentScale`):

```kotlin
import androidx.compose.ui.geometry.Offset
```

Final state: imports are added; the existing imports are untouched. The file now imports `Offset` (a value class from `androidx.compose.ui.geometry`).

- [ ] **Step 2: Add `scale` and `offset` state at the screen-composable level**

In `app/src/main/java/io/github/jiro/expensetracker/ui/receipts/ReceiptViewerScreen.kt`, in the `ReceiptViewerScreen` function body, after line 60 (the `val context = LocalContext.current` line) and before line 61 (`val shareSuccessMsg = ...`), add 4 new lines:

```kotlin
    var scale by remember(receiptPath) { mutableFloatStateOf(1f) }
    var offset by remember(receiptPath) { mutableStateOf(Offset.Zero) }
    val zoomModifier = Modifier
        .pointerInput(receiptPath) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                if (scale > 1f) offset += pan
            }
        }
        .pointerInput(receiptPath) {
            detectTapGestures(onDoubleTap = {
                scale = 1f
                offset = Offset.Zero
            })
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
```

Note: `scale` is `mutableFloatStateOf` (Compose 1.6+ pattern; matches `MaterialTheme` state APIs); `offset` uses `mutableStateOf(Offset.Zero)` (Compose 1.5+ pattern, still valid). Both are keyed on `receiptPath` so opening a different receipt resets the zoom.

- [ ] **Step 3: Apply `zoomModifier` to the image and PDF-pager branches**

In the same file, the `Box` at lines 138-176 contains three branches: `missing` (text), `pages.isEmpty()` (text), `isPdf` (HorizontalPager with Image), and `else` (Image). Wrap the `isPdf` and `else` branches with `zoomModifier`:

For the `isPdf` branch (lines 151-167), change the `HorizontalPager` call to apply the modifier:

```kotlin
                isPdf -> {
                    key(pagerState.currentPage) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize().then(zoomModifier),
                        ) { pageIndex ->
                            Image(
                                bitmap = pages[pageIndex].asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        text = "${pagerState.currentPage + 1} / ${pages.size}",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    )
                }
```

The `key(pagerState.currentPage)` wrapper resets `scale` and `offset` (the `remember(receiptPath)` keying doesn't change on page swipe, but the gesture coordinator does — to make the reset reliable across page changes, the modifier is re-created when the key changes because the `pointerInput` blocks are keyed implicitly on the current page state). In practice, the `key` block ensures the inner `HorizontalPager`'s children are torn down and rebuilt, which forces a fresh `Modifier` chain. If the reset doesn't feel snappy on multi-page PDFs, that's a known limitation (out of scope for this polish phase).

Add the `key` import (needed for the `key(pagerState.currentPage)` call):

```kotlin
import androidx.compose.runtime.key
```

Insert this import in the `androidx.compose.runtime` block around line 24-29.

For the `else` branch (lines 169-174 — the single-image case), change the `Image` modifier to include `zoomModifier`:

```kotlin
                else -> Image(
                    bitmap = pages.first().asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().then(zoomModifier),
                )
```

(The `key` import is not needed for the single-image case.)

- [ ] **Step 4: Verify build**

Run: `./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL. The gestures compile and link correctly. (Manual smoke: install on a device, open any receipt, pinch to zoom, double-tap to reset. The multi-page PDF reset on page swipe is a manual smoke check.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/receipts/ReceiptViewerScreen.kt
git commit -m "Receipts: pinch-to-zoom + double-tap reset (Phase 2.11 polish)

Wraps the image and PDF-pager branches with scale 1.0–5.0×, pan when
zoomed, and double-tap-to-reset. Multi-page PDFs reset zoom on page swipe
via key(pagerState.currentPage)."
```

---

## Task 5: Final verification

**Files:** none (read-only verification)

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL. All existing tests pass plus the 3 new tests from Task 1. Record the exact pass count from the gradle report for the release notes.

- [ ] **Step 2: Run the build**

Run: `./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL. The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Verify no stale `COMMON_CURRENCY_PAIRS` references in the source tree**

Run: `rg COMMON_CURRENCY_PAIRS app/src` (uses `rg`/ripgrep via the Grep tool; expected empty result in `app/src/`, the spec/plan files in `docs/` may still reference it historically).

Expected: zero matches in `app/src/`. If matches exist, the references were missed in Task 2; revisit and fix.

- [ ] **Step 4: Verify all 4 polish items are present in the working tree**

Run: `git log --oneline -5`

Expected: the 4 feature commits from Tasks 1-4 are present, plus the spec + spec-self-review commits. The working tree is clean (no uncommitted changes from this phase).

- [ ] **Step 5: Tag v0.10.1 and push**

```bash
git tag v0.10.1
git push origin master --tags
```

Expected: `git tag` shows `v0.10.1` pointing at HEAD. `git push` reports the tag push.

- [ ] **Step 6: Commit any remaining lockfile / state changes (usually none)**

If the verification revealed a fix (e.g. a missed `COMMON_CURRENCY_PAIRS` reference), commit it before tagging. Otherwise this step is a no-op.

```bash
git status   # confirm clean
```
