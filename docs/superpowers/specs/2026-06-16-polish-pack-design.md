# Phase 2.11 — Polish Pack — Design

**Status:** Approved 2026-06-16
**Phase:** 2.11
**Predecessors:** Phase 2.5 (trends), Phase 2.9 (transactions search), Phase 2.10 (FX/currency settings) all ship the deferred items this phase polishes. Tagged v0.10.1.

## Goal

Bundle four small, deferred polish items from prior phases into a single release. No new abstractions, no new deps, no API changes. ~75 lines of production code, ~30 lines of test code, 1 new string, 1 deleted constant.

1. **Thousands-separators search fix** — searching `"1,200"`, `"1 200"`, or `"1200"` all match a transaction of $1,200.00 (Phase 2.9 follow-up).
2. **A11y + dead-code cleanup** — the FX-rate delete `IconButton` uses `"Cancel"` as its contentDescription (wrong). The `COMMON_CURRENCY_PAIRS` constant ships but is never read (Phase 2.10 follow-up).
3. **Dark NetBlue chart variant** — the trends chart's hardcoded `NetBlue` is unreadable on a dark background. Use a brighter `DarkNetBlue` in dark mode (Phase 2.5 follow-up).
4. **Pinch-to-zoom on receipt viewer** — wrap the image / PDF pager with pinch-to-zoom + double-tap-to-reset gestures (Phase 2.4 follow-up).

Out of scope (intentional, deferred): Transactions sort options, system-tray budget notifications, FX API rate source, full app-wide dark-mode audit.

## User-visible behavior

### 1. Search

On the Transactions tab, the search field accepts digits, spaces, and `,` in the amount query and matches regardless of formatting:
- A transaction of $1,200.00 is matched by `1200`, `1,200`, `1 200`, `1200.00`, and `1,200.00`.
- The text match (title, note, category) is unchanged.
- The amount range Min/Max fields already accept any decimal input via `MoneyFormat.parseAmountToMinor`; no change there.

### 2. A11y + dead code

In Settings → FX rates card, the trailing delete-icon on each row now has a TalkBack-friendly label ("Delete rate") instead of the generic "Cancel" string. The "Cancel" string is still used for the dialog dismiss buttons (unchanged). The dead `COMMON_CURRENCY_PAIRS` constant is gone from the codebase.

### 3. Dark NetBlue

When the system is in dark mode, the trends chart's net line + legend dot use a brighter blue (`DarkNetBlue = #64B5F6`) instead of the light-mode `NetBlue = #1565C0`. Light mode is unchanged. Income (green) and expense (red) already have dark variants and are not touched.

### 4. Pinch-to-zoom

In the receipt viewer:
- Pinch with two fingers to zoom in / out (1.0× to 5.0×).
- Pan with one finger when zoomed in.
- Double-tap to reset to 1.0× and centered.
- Multi-page PDFs: the zoom state is per-screen (resets when swiping to a new page). Pinch works inside each page.

## Data model

**No schema changes.** All four items are UI or pure-helper changes; the Room schema, repository signatures, and `MoneyFormat` contracts are unchanged.

## Components

| File | Purpose |
| --- | --- |
| `data/local/MoneyFormat.kt` (modified) | 1 new pure helper: `stripAmountSeparators`. |
| `ui/transactions/Filters.kt` (modified) | Search loop uses `stripAmountSeparators` on the query and `normalizeAmountForSearch` on the amount. |
| `app/src/test/.../ui/transactions/FiltersTest.kt` (modified) | +3 tests. |
| `ui/settings/SettingsScreen.kt` (modified) | Line 239 `contentDescription` uses new string. |
| `res/values/strings.xml` (modified) | +1 string: `settings_fx_delete`. |
| `preferences/SupportedCurrencies.kt` (modified) | Delete `COMMON_CURRENCY_PAIRS` (lines 14-23). |
| `ui/theme/Color.kt` (modified) | +1 constant: `DarkNetBlue`. |
| `ui/charts/LineChart.kt` (modified) | Resolve `netBlue` once via `isSystemInDarkTheme()`; use it in 6 sites. |
| `ui/receipts/ReceiptViewerScreen.kt` (modified) | Add scale + offset state; wrap inner content with `pointerInput` + `graphicsLayer`. |

### Search helper details

In `MoneyFormat.kt`, add one pure helper:

```kotlin
/** Pure: strips thousands separators (`,`, space, non-breaking space) from
 * a user-typed search string and lowercases it. "1,200", "1 200", "1200"
 * → "1200"; "1,200.50" → "1200.50". */
fun stripAmountSeparators(query: String): String
```

Note: `formatAmountForEdit` already returns a separator-free string (`120_000L` → `"1200.00"`), so the amount side does not need a separate normalizer — we compare the user's query-stripped form against `formatAmountForEdit`'s output directly. The original 2-helper design was simplified after reading the actual `MoneyFormat` implementation (it has no thousands separator in the display format, so `normalizeAmountForSearch` would be a no-op duplicate of `formatAmountForEdit`).

In `Filters.kt:filterTransactions`, the amount-match branch becomes:

```kotlin
val stripped = MoneyFormat.stripAmountSeparators(trimmedQuery)
val formatted = MoneyFormat.formatAmountForEdit(t.amountMinor)
val amountMatch = formatted.contains(trimmedQuery, ignoreCase = true)
    || (stripped != trimmedQuery && formatted.contains(stripped, ignoreCase = true))
```

The first check preserves the existing match (e.g. `"1200"` against `"1200.00"`). The second check adds support for separator-bearing queries (`"1,200"` or `"1 200"` against `"1200.00"`). `stripped` is computed once outside the row loop. The `stripped != trimmedQuery` guard avoids a redundant comparison when the query had no separators.

### Pinch-to-zoom details

In `ReceiptViewerScreen.kt`, add at the screen-composable level:

```kotlin
var scale by remember { mutableFloatStateOf(1f) }
var offset by remember { mutableStateOf(Offset.Zero) }
```

Apply to the inner `Image` / `HorizontalPager`:

```kotlin
val transformModifier = Modifier
    .pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            if (scale > 1f) offset += pan
        }
    }
    .pointerInput(Unit) {
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

The pager keeps its own swipe gesture (separate from `detectTransformGestures`); zoom is reset by the pager's `key` whenever the page changes (using `key(pagerState.currentPage) { ... }` around the inner content).

### Dark NetBlue details

In `Color.kt`, add to the Dark scheme block:

```kotlin
val DarkNetBlue = Color(0xFF64B5F6)
```

In `LineChart.kt` (line 35: import `androidx.compose.foundation.isSystemInDarkTheme` and `io.github.jiro.expensetracker.ui.theme.DarkNetBlue`):

```kotlin
val netBlue = if (isSystemInDarkTheme()) DarkNetBlue else NetBlue
```

Replace the 6 `NetBlue` references (lines 104, 106, 118, 178, 215) with `netBlue`. Line 35 (import) keeps `NetBlue` and adds `DarkNetBlue`.

## Strings to add

```
settings_fx_delete  "Delete rate"
```

(1 new string.)

## Tests

In `app/src/test/.../ui/transactions/FiltersTest.kt`, add 3 tests:

1. `filterTransactions_searchAmountMatchesWithComma` — query `"1,200"` matches a 120_000L-minor transaction (currently fails; the user types a comma, the existing code only matches `"1200"`).
2. `filterTransactions_searchAmountMatchesWithSpace` — query `"1 200"` matches the same transaction (currently fails).
3. `filterTransactions_searchAmountMatchesNoSeparators_regressionGuard` — query `"1200"` matches (was already passing via the formattedMatch path; this guards against future regression).

(`stripAmountSeparators` is tested transitively via the `filterTransactions` tests. A direct unit test on `stripAmountSeparators` is redundant.)

(3 new tests. 196 prior → 199 total.)

## Edge cases

| Case | Behavior |
| --- | --- |
| Search query is `"abc"` (non-numeric) | Text-match only; amount-match is false but not a crash. |
| Search query is `"1,200,500"` | `stripAmountSeparators` → `"1200500"`; compared against `"1200500.00"`. Matches if exact, no match otherwise. |
| Search query is `"1.2"` | Treated as a text fragment; no decimal normalization. Matches `"1,200.00"` (contains) but not `"1200.00"`. (Consistent with prior behavior.) |
| Receipt viewer with 1-page PDF and pinch | Works identically to images. |
| Receipt viewer with multi-page PDF | Zoom resets when swiping to a new page (`key(pagerState.currentPage)` around the inner content). |
| Dark mode toggled at runtime | Trends chart updates on next composition (no state held). |
| TalkBack user navigates to delete-icon | Reads "Delete rate" (the new contentDescription) instead of "Cancel". |
| `COMMON_CURRENCY_PAIRS` is removed and someone re-adds a reference | Compile error — caught at build time. |

## Out of scope (intentional, deferred)

- **Transactions sort options** (Phase 2.7) — a real feature, not a polish nit. Separate phase.
- **System-tray budget notifications** (Phase 2.8) — needs `NotificationManager` wiring and a daily-trigger scheduler. Not a polish nit.
- **FX API rate source** (Phase 2.10 follow-up) — needs a network layer + new dep. Real feature.
- **Full app-wide dark-mode audit** — out of scope. Income / expense colors already have dark variants. Other UI surfaces (cards, chips) use `MaterialTheme.colorScheme.*` and are already dark-aware. The `NetBlue` chart was the only remaining hardcoded color.
- **Receipt pinch + multi-page state preservation** — zoom resets on page swipe. True per-page state preservation is more code; defer.
- **"Save to Photos" chart share** (Phase 2.5) — separate feature.

## Open questions

None. Decisions taken one at a time during brainstorming and recorded in the User-visible behavior, Components, and Edge cases sections above.
