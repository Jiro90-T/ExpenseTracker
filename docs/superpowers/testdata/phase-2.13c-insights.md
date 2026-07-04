# Phase 2.13c — Statistics Insights Tab — Manual Smoke Test

Manual verification for the new Insights tab on the Statistics screen.

## Pre-conditions

- App built and installed (`./gradlew installDebug`, then launch once).
- Seeded with at least:
  - 3+ transactions this calendar month, across 2+ categories
  - 3+ transactions the prior calendar month, across the same categories
  - At least one transaction on a Saturday or Sunday in the last 90 days
  - At least one income transaction this month and last month
  - A "largest expense" row this month with a distinctive title (e.g. "Dinner-out" or similar)
- Force-stop the app (`adb shell am force-stop io.github.jiro.expensetracker`)
  and relaunch between steps that depend on persisted state.

## Tab visibility

### Step 1 — Insights tab appears

1. Open the Statistics tab from bottom nav.
2. Verify the TabRow now shows **five** entries: Top Cats · Savings · Patterns · YoY · Insights.
3. Tap **Insights** — the body shows a scrollable column of insight cards (1–4 cards, depending on data).
4. Expected: tab text "Insights" is fully visible without truncation on a standard phone.

### Step 2 — Card layout

1. With the seeded data, expect up to four cards in this order:
   - **Category delta** (highest priority) — icon = trending-up/down/new; category name in headline; amounts in supporting text.
   - **Weekend vs weekday** — calendar icon; "Weekend spending is X% of your total".
   - **Savings trend** — savings icon (green up / red down / neutral unchanged); "Savings rate up/down/unchanged at X%".
   - **Top expense spotlight** — receipt icon; "Largest expense: $X" + "[Title] on [MMM d]".
2. Each card has an icon on the left (28dp), headline (bold), and supporting text (small, muted).
3. Expected: cards stack vertically with 12dp spacing.

## Insight correctness

### Step 3 — Category delta

1. Compare the CategoryDelta headline to your data:
   - If the category you spent most differently on this month vs last is "Food" with a +50% increase, expect **"Food up 50%"**.
   - Supporting text: **"$Y this month vs $Z last month"**.
2. If you added a brand-new spending category this month (none last month), expect **"New spending: [Cat]"** with a "FiberNew" icon and the supporting text **"$Y this month"** (no "vs" comparison).

### Step 4 — Weekend vs weekday

1. The 90-day window counts only expenses.
2. The headline rounds to the nearest whole percent: e.g. "Weekend spending is 31% of your total".
3. Supporting text: **"$A on weekends vs $B on weekdays"** (both formatted via MoneyFormat).

### Step 5 — Savings trend

1. If your rate moved up, expect **"Savings rate up X pts"** (green icon).
2. If it moved down, expect **"Savings rate down X pts"** (red icon).
3. If it didn't move (within 0.01pp), expect **"Savings rate unchanged at X%"** (neutral icon).
4. Supporting text: **"X% this month vs Y% last month"**.

### Step 6 — Top expense spotlight

1. The headline is **"Largest expense: $A"** where A is the single biggest transaction this month.
2. Amount is in the transaction's **native** currency (NOT the home currency) — verify by spot-checking a non-USD transaction.
3. Supporting text: **"[title] on [MMM d]"** (e.g. "Dinner-out on Jun 10").
4. If the title was blank, expect **"— on [date]"** instead of an empty title.

## Empty states

### Step 7 — No data ever

1. `adb shell pm clear io.github.jiro.expensetracker` to wipe all app data.
2. Open the Statistics tab → Insights.
3. Expected: a single centered card with text **"Not enough data yet — log transactions to see insights"**. No insight cards.

### Step 8 — Only one month of data

1. Add transactions ONLY in the current calendar month (no prior-month data).
2. Expected: CategoryDelta is omitted (no comparison possible). The other three insights still render if data permits.

### Step 9 — All transactions fall on weekdays in the last 90 days

1. Add expenses only on Monday–Friday in the last 90 days.
2. Expected: WeekendVsWeekday is omitted (weekendMinor = 0, total = weekdayMinor, but the rule is "skip when both are 0"; if weekendMinor = 0 and weekdayMinor > 0, the insight STILL shows with weekendPercent = 0%). This is intentional — a 0% result is still informative ("none of your spending is on weekends").

## Cross-tab consistency

### Step 10 — Numbers match the YoY tab

1. Open the YoY tab. The "this month" expense total should be the same home-currency value used in the CategoryDelta's `currentMinor` for the top-mover category.
2. Expected: no contradictions across tabs. The Insights tab is derived from the same Room flow.

### Step 11 — Refresh

1. Add a new expense on the home screen.
2. Switch back to the Statistics → Insights tab (within ~100ms).
3. Expected: the cards reflect the new transaction (no manual refresh needed).

## Expected outcomes

- All 4 insight cards render when 2+ months of data exist with mixed categories, weekend/weekday expenses, and income.
- Empty state appears only when there are no transactions at all.
- Currency formatting matches MoneyFormat (thousands separators, currency code on multi-currency tab if FX is missing).
- Icon tints: green for "savings up" and "category down"; red for "savings down" and "category up"; primary for new spending; neutral for unchanged.

## Rollback

The Insights tab is read-only and derived from existing data. To disable it temporarily:

```kotlin
// In StatisticsScreen.kt, change the `tabs` list to drop INSIGHTS:
val tabs = listOf(StatisticsTab.TOP_CATS, StatisticsTab.SAVINGS, StatisticsTab.PATTERNS, StatisticsTab.YOY)
```

No data is modified. To fully remove: revert the Phase 2.13c commits (`git revert <commit-1>..<commit-N>`) or delete the v0.18.11 tag and reset to v0.18.10.