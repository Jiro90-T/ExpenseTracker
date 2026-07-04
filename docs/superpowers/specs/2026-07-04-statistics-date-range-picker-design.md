# Phase 2.13b — Custom Date-Range Picker on Statistics

**Date:** 2026-07-04
**Status:** Approved (brainstorming complete)
**Owner:** Phase 2.13b

## Goal

Let users pick arbitrary date windows for each of the four Statistics tabs (Top Cats, Savings, Patterns, YoY). Currently every tab is fixed-window: Top Cats / Savings / Patterns default to the current calendar month, YoY compares this month to the same month last year. With this feature, every tab can be moved to any user-chosen range; the choice persists per tab across app launches.

Deferred from the original Phase 2.13 spec to keep the initial Statistics ship small.

## Non-Goals

- "Compare to last year" toggle on non-YoY tabs. (YoY is dedicated.)
- Named saved ranges ("My Q1 view").
- Sharing ranges via deep link.
- Additional presets beyond the six listed below. Can extend later if usage justifies.
- iOS-style range shortcuts ("This week", "Last week"). Easy follow-up.

## Resolved Design Decisions

1. **Per-tab independence.** Each tab owns its own range; switching tabs does not move other tabs.
2. **DataStore persistence.** Each tab's range survives app restarts. Defaults are *derived* (never stored) so "This month" doesn't silently drift.
3. **UI: chip + bottom sheet.** Each tab shows a `RangeChip` in its header; tap opens a `ModalBottomSheet` with a row of preset chips above a Material 3 `DateRangePicker`. The sheet always shows the calendar — there is no separate "Custom" preset.
4. **Calculator refactor.** Every `StatisticsCalculator` function changes signature from `(transactions, nowMs)` to `(transactions, startMs, endMs)`. YoY additionally takes `(currentStartMs, currentEndMs, priorStartMs, priorEndMs)` and exposes an internal `subtractOneYear(ms: Long)` helper.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│ StatisticsScreen                                        │
│  ├─ TabRow (Top Cats / Savings / Patterns / YoY)        │
│  ├─ RangeChip (per tab) ──── tap ──┐                    │
│  └─ TabContent (per tab)            │                    │
└─────────────────────────────────────┼─────────────────────┘
                                      ▼
┌─────────────────────────────────────────────────────────┐
│ RangePickerSheet (ModalBottomSheet)                     │
│  ├─ Preset row: [Last 7d] [Last 30d] [This mo] ...      │
│  └─ DateRangePicker (always visible)                    │
└─────────────────────────────────────────────────────────┘
                                      ▼
┌─────────────────────────────────────────────────────────┐
│ StatisticsViewModel                                     │
│  ├─ selectedTab: MutableStateFlow<StatisticsTab>        │
│  ├─ topCatsState / savingsState / patternsState /       │
│  │   yoyState: StateFlow<…State>  (flatMapLatest)       │
│  └─ onRangeSelected(tab, range) → rangeRepo.set()       │
└─────────────────────────────────────────────────────────┘
                                      ▼
┌─────────────────────────────────────────────────────────┐
│ StatisticsRangeRepository (DataStore-backed)            │
│  ├─ observe(tab): Flow<LongRange>                       │
│  ├─ set(tab, range)                                     │
│  └─ defaultFor(tab, nowMs): LongRange                   │
└─────────────────────────────────────────────────────────┘
                                      ▼
┌─────────────────────────────────────────────────────────┐
│ StatisticsCalculator (pure functions)                   │
│  ├─ topCategories(tx, start, end)                       │
│  ├─ savingsRate(tx, start, end)                         │
│  ├─ spendingPatterns(tx, start, end)                    │
│  ├─ yearOverYear(tx, currentStart, currentEnd,          │
│  │               priorStart, priorEnd)                  │
│  └─ internal subtractOneYear(ms)                        │
└─────────────────────────────────────────────────────────┘
```

**Per-tab data flow on range change:**

```
User taps chip → sheet opens → user picks preset or dates → tap Apply
  → VM.onRangeSelected(tab, range)
  → rangeRepo.set(tab, range) [DataStore edit]
  → rangeRepo.observe(tab) emits new value
  → VM.flatMapLatest re-collects txRepo.observeInRange(start, end)
  → calculator(windowedTx, start, end) → new state
  → TabContent recomposes
```

`flatMapLatest` on `rangeRepo.observe(tab)` makes range changes automatic — no manual refresh calls.

## Files

### New (5 source + 2 test)

| Path | Purpose |
|---|---|
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepository.kt` | DataStore-backed per-tab range store. `interface StatisticsRangeRepository` + `DataStoreStatisticsRangeRepository` impl. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/PresetRanges.kt` | `sealed class StatisticsPreset` with five `object` singletons: `Last7Days`, `Last30Days`, `ThisMonth`, `LastMonth`, `ThisYear`. Each carries a localized label and `resolve(nowMs: Long): LongRange`. (No `Custom` member — the calendar is always visible in the sheet, so a Custom chip would be a no-op.) |
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/RangeChip.kt` | Compose composable: pill chip with date range formatted via existing `DateFormat` helper. Clickable. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/RangePickerSheet.kt` | `ModalBottomSheet` with preset row + Material 3 `DateRangePicker` + Apply/Cancel. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/di/StatisticsModule.kt` | Hilt module: binds `DataStoreStatisticsRangeRepository` as the default impl, provides a DataStore extension for `statisticsDataStore`. |
| `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/PresetRangesTest.kt` | 8 tests. |
| `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepositoryTest.kt` | 5 tests (Robolectric, real DataStore). |

### Modified (4 source + 2 test)

| Path | Change |
|---|---|
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculator.kt` | All public functions: signature change to `(tx, startMs, endMs)`. YoY takes both windows. Adds `internal subtractOneYear(ms)`. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModel.kt` | Adds per-tab `RangeState` flows via `rangeRepo.observe(tab).flatMapLatest { ... }`. YoY computes prior window via `subtractOneYear`. Adds `onRangeSelected(tab, range)`. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsScreen.kt` | Adds `RangeChip` to each tab's header. Wires `RangePickerSheet`. Per-tab sheet state keyed by `selectedTab`. |
| `app/src/main/res/values/strings.xml` | Adds 10 new strings (see *Strings* section below). |
| `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculatorTest.kt` | ~26 of 31 tests rewritten for new signatures. 6 new tests added. |
| `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModelTest.kt` | 4 new tests added. |

**File count:** 7 new, 6 modified.

## DataStore Schema

Two `longPreferencesKey`s per tab, written atomically in a single `edit { }` block. Eight keys total.

```
stats_range_top_cats_start   long
stats_range_top_cats_end     long
stats_range_savings_start    long
stats_range_savings_end      long
stats_range_patterns_start   long
stats_range_patterns_end     long
stats_range_yoy_start        long
stats_range_yoy_end          long
```

A `LongRange` is stored as two Longs. DataStore's edit-block atomicity guarantees start and end stay consistent (no partial-write race). Each key holds one natural Long — no string parsing.

**Defaults** (returned by `observe()` when no value is stored, and via `defaultFor()`):

```kotlin
fun defaultFor(tab: StatisticsTab, nowMs: Long): LongRange = when (tab) {
    TOP_CATS, SAVINGS, PATTERNS -> currentCalendarMonth(nowMs)
    YOY                         -> currentCalendarMonth(nowMs)
}
```

Defaults are derived from `clock.nowMs()` on every read — they are **never** persisted. This guarantees that "This month" always means the current month, even weeks after the user first opened the app.

## Calculator Refactor

### Current Signatures

```kotlin
fun topCategories(tx: List<TransactionWithCategory>, nowMs: Long): TopCategories
fun savingsRate(tx: List<TransactionWithCategory>, nowMs: Long): SavingsRate
fun spendingPatterns(tx: List<TransactionWithCategory>, nowMs: Long): SpendingPatterns
fun yearOverYear(tx: List<TransactionWithCategory>, nowMs: Long): YearOverYear
```

### New Signatures

```kotlin
fun topCategories(
    tx: List<TransactionWithCategory>,
    startMs: Long, endMs: Long,         // half-open [start, end)
): TopCategories

fun savingsRate(
    tx: List<TransactionWithCategory>,
    startMs: Long, endMs: Long,
): SavingsRate

fun spendingPatterns(
    tx: List<TransactionWithCategory>,
    startMs: Long, endMs: Long,
): SpendingPatterns

fun yearOverYear(
    tx: List<TransactionWithCategory>,
    currentStartMs: Long, currentEndMs: Long,
    priorStartMs: Long,   priorEndMs: Long,
): YearOverYear

internal fun subtractOneYear(ms: Long): Long
```

### Invariants

- All filtering uses the half-open interval `[startMs, endMs)`. Transactions at exactly `endMs` are excluded.
- For YoY, the prior window is supplied by the caller (computed via `subtractOneYear`). The calculator never derives prior windows itself.
- `subtractOneYear` handles leap days conservatively: **Feb 29, 2024 → Feb 28, 2025** (clamp to Feb 28 if the prior year is non-leap).
- For `spendingPatterns`, every day-of-week in `[startMs, endMs)` contributes to its DOW bucket. Cross-month and cross-year ranges work identically to in-month ranges.

### Test Churn

| Bucket | Count | Action |
|---|---|---|
| Tests pinning to "current calendar month" | ~20 | Rewrite: pass explicit `startMs`/`endMs` |
| Tests pinning to YoY month-vs-last-year | ~6 | Rewrite: pass explicit `(currentStart, currentEnd, priorStart, priorEnd)` |
| Pure-computation tests (no time math) | ~5 | Unchanged |

Plus 6 new tests (see *Testing*).

## Presets

Defined in `PresetRanges.kt`:

| Preset | Resolved range (`resolve(nowMs)`) |
|---|---|
| `Last7Days` | `(nowMs - 7 days)..nowMs` |
| `Last30Days` | `(nowMs - 30 days)..nowMs` |
| `ThisMonth` | First instant of current calendar month → first instant of next month |
| `LastMonth` | First instant of previous calendar month → first instant of current month |
| `ThisYear` | First instant of current calendar year → first instant of next year |

Each preset is a Kotlin `object` (singleton) since they hold no state. The sealed class makes the set exhaustive for the preset chip row. Presets always resolve relative to `nowMs` at tap time. They are not stored — every tap re-derives the range.

## UI

### Chip

```
┌─────────────────────────────────────────┐
│  Top categories              Mar 1–31 ▾ │   ← RangeChip in tab header
│  ─────────────────────────────────────── │
│   ...category list...                   │
└─────────────────────────────────────────┘
```

- Compact format: "Mar 1–31" (same month), "Jan 15 – Feb 3" (cross-month), "Dec 28, 2025 – Jan 4, 2026" (cross-year).
- Color shifts from muted to default the moment a non-default range is picked — visual hint that a custom range is active.
- Each tab's chip is independent. Switching tabs does not reset others' chips.

### Bottom Sheet

```
┌─────────────────────────────────────────┐
│  Pick a range                           │
│                                         │
│  ┌────────┐ ┌────────┐ ┌────────┐ ...   │   ← horizontally scrollable
│  │Last 7d │ │Last 30d│ │This mo │        │
│  └────────┘ └────────┘ └────────┘        │
│                                         │
│  ┌─ DateRangePicker ──────────────────┐│
│  │   March 2026                       ││
│  │   ...calendar grid...              ││
│  │   April 2026                       ││
│  └────────────────────────────────────┘│
│                                         │
│         [Cancel]      [Apply]           │
└─────────────────────────────────────────┘
```

- Presets are always visible. No separate "Custom" preset — the calendar is always shown.
- Tapping a preset programmatically sets the `DateRangePicker`'s `Selection` to the preset's resolved range and visually marks the preset chip as selected.
- Tapping dates in the calendar deselects any active preset chip.
- Apply is enabled only when a valid `[start, end]` selection exists (M3-enforced).
- Cancel discards in-progress selections.

### Empty State

When the chosen window contains zero transactions, each tab shows:

```
┌─────────────────────────────────────────┐
│  Top categories              Mar 1–31 ▾ │
│  ─────────────────────────────────────── │
│                                         │
│        No transactions in this range    │
│                                         │
│           [Reset to this month]         │
└─────────────────────────────────────────┘
```

"Reset to this month" calls `rangeRepo.set(tab, defaultFor(tab, nowMs))`.

## Strings

All added to `app/src/main/res/values/strings.xml`:

| Key | Value |
|---|---|
| `stats_range_chip_label` | "Range" (a11y description for the chip) |
| `stats_preset_last_7d` | "Last 7 days" |
| `stats_preset_last_30d` | "Last 30 days" |
| `stats_preset_this_month` | "This month" |
| `stats_preset_last_month` | "Last month" |
| `stats_preset_this_year` | "This year" |
| `stats_picker_title` | "Pick a range" |
| `stats_picker_apply` | "Apply" |
| `stats_picker_cancel` | "Cancel" |
| `stats_empty_in_range` | "No transactions in this range" |
| `stats_empty_reset` | "Reset to this month" |

All keys must be grep-verified against `strings.xml` before commit (existing project discipline).

## Testing

### Existing tests updated

- `StatisticsCalculatorTest.kt`: ~26 of 31 tests rewritten for new signatures; 6 new tests added (see below).

### New test files

1. **`PresetRangesTest.kt`** (8 tests)
   - `last7Days_resolvesTo7x24hWindowEndingAtNow`
   - `last30Days_resolvesTo30x24hWindowEndingAtNow`
   - `thisMonth_resolvesToCalendarMonthContainingNow`
   - `lastMonth_resolvesToPreviousCalendarMonth`
   - `thisYear_resolvesToCalendarYearContainingNow`
   - `thisMonth_atMonthEnd_returnsSameMonthWindow` (boundary: Jan 31 23:59 → Jan 1..Feb 1)
   - `lastMonth_atJanuary_returnsDecemberOfPriorYear`
   - `thisYear_atYearBoundary_returnsCurrentYear`

2. **`StatisticsRangeRepositoryTest.kt`** (5 tests, Robolectric with real DataStore)
   - `setThenObserve_returnsSameRange`
   - `observeBeforeAnySet_returnsDefaultForTab`
   - `perTabIndependence_setOneTabDoesNotAffectOthers`
   - `defaultForYoy_returnsCurrentMonth`
   - `persistedAcrossInstances`

3. **`RangePickerSheetTest.kt`** (4 tests, optional — ship if time permits)
   - `tapChip_opensSheet`
   - `tapPresetChip_selectsPreset`
   - `tapApply_closesSheetAndUpdatesChip`
   - `emptyRange_showsEmptyState`

### Existing test files extended

1. **`StatisticsCalculatorTest.kt`** — 6 new tests added:
   - `topCategories_arbitraryWindow_filtersByStartEnd`
   - `savingsRate_windowWithNoIncome_returnsZero`
   - `spendingPatterns_weekSpanningMonthBoundary_aggregatesBothWeeks`
   - `yearOverYear_subtractOneYear_handlesLeapDay` (Feb 29, 2024 → Feb 28, 2025)
   - `yearOverYear_subtractOneYear_handlesNonLeapYear`
   - `yearOverYear_priorWindowComputation_matchesSubtractOneYear`

2. **`StatisticsViewModelTest.kt`** — 4 new tests added:
   - `rangeChangeForOneTab_doesNotAffectOthers`
   - `yoyPriorWindow_subtractsOneYear`
   - `yoyPriorWindow_handlesLeapDay`
   - `rangeChange_triggersRecomputation`

### Cumulative counts

- **Before:** 31 calculator + ~5 VM tests = 36
- **After:** 31 calculator (rewrite) + 8 preset + 5 repo + 4 VM + ~4 UI = 52
- **Net delta:** +16 tests, +3 test files, +2 test files modified

### Manual smoke test

`docs/superpowers/testdata/statistics-date-range-picker.md` — new file mirroring the structure of `member-cards-widget.md` and `close-account.md`. Covers:

1. Each tab picks a different range.
2. Force-stop and relaunch — all four ranges persist.
3. Switch tabs — each tab's chip is independent.
4. Each preset works on at least one tab.
5. Custom range from calendar (e.g. Jan 15 – Feb 28) renders correctly on every tab.
6. YoY with custom range compares same calendar dates last year.
7. Empty range shows "No transactions in this range" + reset.
8. Reset link restores default.
9. Chip text color shift on custom range.
10. Cancel discards selection; Apply commits.

## Error Handling

| Scenario | Handling |
|---|---|
| DataStore write fails | Repository throws; VM catches in `set()` scope, exposes `RangeSetError` state; chip reverts to previous text |
| DataStore read fails | VM exposes `RangeLoadError`; tab shows "Couldn't load range" with retry button |
| Empty window (start ≥ end) | Impossible — M3 DateRangePicker disallows; Apply is disabled until valid |
| No transactions in range | Empty-state UI with reset link (see *UI > Empty State*) |
| Cross-year range (Dec 2025 – Jan 2027) | Chip text uses long format; calculator handles arbitrary windows — no edge case |
| Range larger than available data | Filter returns empty → empty-state UI |
| App clock jumps backward | `Clock.nowMs()` always read fresh; presets resolve to current `now`; no stored time |

## Migration / Rollout

- No data migration needed. The new fields are pure additions.
- Defaults fire on first launch for every install — no empty state on upgrade.
- The shipped `version` field in the next APK tag will reflect this feature; not gated by a Room schema migration (DataStore writes are lazy).

## Open Questions

None. All design decisions resolved during brainstorming.

## Out-of-Scope Follow-ups

- "Compare to last year" toggle on non-YoY tabs.
- Named saved ranges.
- Range deep-linking.
- Additional presets (This week, Last week, Trailing 90d).
- Allow range to extend into the future (currently no future-date cap on the picker).

## References

- Phase 2.13 Statistics spec — `docs/superpowers/specs/2026-06-17-statistics-insights-design.md`
- Phase 2.13 plan — `docs/superpowers/plans/2026-07-03-statistics-insights.md`
- Manual smoke test pattern — `docs/superpowers/testdata/member-cards-widget.md`, `docs/superpowers/testdata/close-account.md`