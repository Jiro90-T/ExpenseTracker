# Phase 2.14 — Image Fixes & Show/Hide Balance — Manual Smoke Test

Manual verification for the four image-bug fixes and the new show/hide
balance feature. Mirror the structure of
`docs/superpowers/testdata/close-account.md`.

## Pre-conditions

- App built and installed (`./gradlew installDebug`, then launch once).
- Seeded with at least 3 accounts and a few transactions across the
  current month so every screen has visible balances.
- Force-stop the app (`adb shell am force-stop io.github.jiro.expensetracker`)
  and relaunch between steps that depend on persisted state.

## Bug-fix verification

### Step 1 — Home title (image 2)

1. Open the app. Verify the top app bar shows **"Home"** (not
   "Transactions"). The bottom nav "Home" tab should still be active.
2. Expected: the title matches the active bottom-nav destination.

### Step 2 — Home Balance tile double-minus (image 2)

1. On Home, add (or keep) a month with no income but at least one
   expense, so the Balance tile is negative.
2. Verify the Balance tile renders **"-X.XX"** (single minus), not
   **"--X.XX"** (double minus).
3. With positive income + zero expense, verify the Balance tile
   renders **"+X.XX"** (explicit plus).
4. With zero income + zero expense, verify it renders **"0.00"** with
   no sign.

### Step 3 — Accounts net-balance thousand separator (image 1)

1. Open the Accounts screen.
2. Verify the "Net balance (home)" header shows the value with a
   thousands separator, e.g. **"39,318.12 MYR"** instead of
   **"39318.12 MYR"**. Both the whole portion (39,318) and the
   fraction (12) must use the locale grouping.
3. Expected: also true for the per-account tile balances
   (e.g. **"77,344.46 MYR"** for RHB, **"−2,853.75 MYR"** for Alliance
   Virtual Visa).

### Step 4 — Transactions filter collapsible (image 3)

1. Open the Transactions tab.
2. Verify only the search bar and the All/Income/Expense chips + a
   **"Filters"** button are visible. The sort row, amount range
   inputs, category/date dropdowns, and clear button must be hidden.
3. Tap **"Filters"** → arrow rotates up → all four rows below
   expand.
4. Tap **"Filters"** again → arrow rotates down → rows collapse.
5. Set a filter (e.g. type=Expense, min=10) and collapse. Reopen
   the section — the filter values are still applied; only the
   visual disclosure collapsed.
6. Tap **Clear filters** — verify the snackbar/locale updates and
   the chips/dropdowns reset.

## Show/hide balance feature

### Step 5 — Eye icon visibility

1. On Home, verify a small eye icon sits in the top-right of the
   app bar (left of the existing list icon).
2. On Accounts, verify the same eye icon sits in the top-right
   (left of nothing — Accounts has only the back arrow + eye).
3. Expected: the eye icon toggles between `Visibility` (currently
   shown) and `VisibilityOff` (currently hidden).

### Step 6 — Toggle from Home

1. From the Home dashboard with values visible, tap the eye icon.
2. Verify the Income, Expense, and Balance tiles all switch to
   **"••••"**. The labels (Income / Expense / Balance) stay.
3. The category breakdown pie chart legend is independent — its
   percentages should still show, since they aren't currency values.
4. Tap the eye icon again — verify all three tiles restore their
   numeric values.

### Step 7 — Toggle from Accounts

1. Open Accounts with values visible. Tap the eye icon.
2. Verify the "Net balance (home)" header switches to **"••••"** and
   every per-account tile shows **"••••"** in place of its balance.
3. Verify account names, icons, and the closed pill remain
   unchanged — only the numeric balance is masked.
4. Tap the eye icon again — values return.

### Step 8 — Cross-screen sync

1. From Home, tap the eye icon (hide balances). Navigate to Accounts.
2. Verify Accounts is also in the hidden state — the flag is global,
   not per-screen.
3. Tap the eye icon on Accounts to restore. Navigate back to Home.
4. Verify Home is also restored.

### Step 9 — Persistence across launches

1. Tap the eye icon on Home to hide balances.
2. Force-stop the app and relaunch.
3. Verify balances are still hidden on Home and Accounts — the
   preference survives an app restart.

### Step 10 — Interaction with other surfaces

1. With balances hidden, tap the chart legend in the dashboard.
   The pie chart percentages must remain visible.
2. Open a transaction detail (long-press a row, or tap it) and
   verify the per-transaction amounts are **not** masked — only the
   aggregate balance surfaces respond to the toggle in this phase.
3. Open Statistics. The chart values (stat tiles, YoY comparison)
   are also not masked in this phase — they're informational, not
   "balance" surfaces. (If they should be masked, file a follow-up
   issue and don't fix in this PR.)

## Expected outcomes

- Step 1: top bar reads "Home".
- Step 2: negative balance shows single "-"; positive shows "+";
  zero shows "0.00".
- Step 3: thousands grouped; tiles too.
- Step 4: filter section collapsed by default; tap toggles; values
  persist across collapse/expand.
- Step 5: eye icon present on Home and Accounts.
- Step 6: Home tiles mask then unmask.
- Step 7: Accounts header + tiles mask then unmask.
- Step 8: state shared between screens.
- Step 9: hidden state survives force-stop.
- Step 10: non-balance surfaces (chart %, transaction detail,
  statistics) are unaffected.

## Rollback

The hide-balance preference is stored in the `expense_tracker_settings`
SharedPreferences file under the key `balance_hidden`. To clear:

```bash
adb shell run-as io.github.jiro.expensetracker \
  rm -f shared_prefs/expense_tracker_settings.xml
```

Or `adb shell pm clear io.github.jiro.expensetracker` to wipe all app
data (also clears transactions).