# Statistics Date-Range Picker — Manual Smoke Test

Manual verification for the Phase 2.13b date-range picker on the
Statistics screen. Mirror the structure of
`docs/superpowers/testdata/close-account.md`.

## Pre-conditions

- App built and installed (`./gradlew installDebug`, then launch once).
- At least one transaction per Statistics tab's default range
  (current month) so each tab has visible content to re-render.
- For the YoY tab, ideally at least one transaction in the same
  calendar month one year ago so the prior-window tile isn't empty.

## Steps

1. **Open Statistics.** Navigate to the Statistics tab. Verify each
   of the four sub-tabs (Top Cats, Savings, Patterns, YoY) shows a
   "Range" chip in its header displaying the current calendar month
   (e.g. "Jul 1 – Jul 31") with the muted default color.
2. **Top Cats — preset.** On Top Cats, tap the chip. Verify a bottom
   sheet opens titled "Pick a range" with preset chips ("Last 7 days",
   "Last 30 days", "This month", "Last month", "This year") above a
   Material 3 `DateRangePicker`. Tap "Last 30 days" → tap Apply. Verify
   the chip text updates to a 30-day window and the chart re-renders
   with the new range.
3. **Savings — independent.** Switch to Savings. Verify its chip is
   still the current month (independent of Top Cats' choice from
   step 2). The three stat tiles (savings rate, avg monthly, top tx)
   should reflect the Savings tab's own range.
4. **Savings — custom range.** On Savings, tap the chip. Pick a custom
   range crossing a month boundary (e.g. Jan 15 – Feb 14, 2026).
   Apply. Verify the chip shows the cross-month format
   ("Jan 15 – Feb 14") and the stat tiles recompute against the new
   window.
5. **Patterns — independent.** Switch to Patterns. Verify its chip
   is still the current month (independent of both Top Cats and
   Savings).
6. **YoY — preset.** Switch to YoY. Tap the chip → pick "Last 7
   days" → Apply. Verify the two tiles now show the current 7-day
   window vs the prior 7-day window (derived via `subtractOneYear`).
   Verify the delta chip updates ("N% vs last year", "No spending
   last year", or "No change" depending on data).
7. **Persistence across launches.** With each tab on a different
   non-default range from steps 2/4/6, force-stop the app
   (`adb shell am force-stop io.github.jiro.expensetracker`). Reopen
   and navigate to Statistics. Verify all four chips show their last
   selected ranges.
8. **Empty range state.** On Top Cats, pick a custom range with no
   transactions (e.g. a year far in the future). Apply. Verify the
   "No transactions in this range" message plus a "Reset to this
   month" TextButton appear in place of the chart.
9. **Reset to default.** Tap the "Reset to this month" button. Verify
   the range returns to the current month and the chart re-renders
   (or the empty state clears if the default range also has no data).
10. **Chip color.** Verify the chip text color is muted/secondary
    when the range equals the default for that tab, and accent
    (primary) when it differs.
11. **Cancel from sheet.** Tap any chip → make a selection → tap
    Cancel. Verify no change is applied: the chip and chart remain on
    the previous range.
12. **Apply from sheet.** Tap any chip → make a different selection
    → tap Apply. Verify the tab updates immediately (chip text and
    computed values).
13. **Leap-day YoY.** If transaction data exists for Feb 29, 2024,
    pick that exact day as a 1-day YoY range and Apply. Verify the
    YoY tile does not crash and shows a sensible prior-window label
    (`LocalDate.minusYears(1)` should clamp to Feb 28, 2023).
14. **Empty-data range.** Pick a range with zero transactions on
    any tab. Verify the empty state appears without crashing.
15. **Preset resolution.** On any tab, tap the chip and tap each
    preset in turn → Apply. Verify the chip text reflects a sensible
    window (Last 7 days ≈ today − 7d .. today; This month ≈ first ..
    last day of current month; Last month ≈ first .. last day of
    previous month; This year ≈ Jan 1 .. Dec 31 of current year).

## Expected outcomes

- Step 1: all four chips default to current calendar month, muted color.
- Step 2: preset applies; chart re-renders; chip text updates.
- Step 3: Savings chip unaffected by Top Cats change.
- Step 4: cross-month chip text format correct; tiles recompute.
- Step 5: Patterns chip unaffected by Top Cats or Savings.
- Step 6: YoY prior window derived via `subtractOneYear`; delta chip
  sensible.
- Step 7: all four ranges restored after force-stop.
- Step 8: empty state visible with Reset button.
- Step 9: range returns to current month on Reset.
- Step 10: chip color reflects default vs custom state.
- Step 11: Cancel discards the selection; no state change.
- Step 12: Apply commits the selection; tab updates.
- Step 13: leap-day prior window clamps to Feb 28; no crash.
- Step 14: empty-data range renders empty state without error.
- Step 15: every preset resolves to the expected window.

## Rollback

`adb shell pm clear io.github.jiro.expensetracker` resets app data
and clears all persisted Statistics ranges from the
`statistics_range` DataStore Preferences file.