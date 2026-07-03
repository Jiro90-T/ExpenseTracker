# Close Account — Manual Smoke Test

Manual verification for the close-account feature. Mirror the structure
of `docs/superpowers/testdata/member-cards-widget.md`.

## Pre-conditions

- App built and installed (`./gradlew installDebug`, then launch once).
- At least 3 accounts saved: "Cash wallet" (the seeded default), one
  other active account, and one more for the dropdown test.
- Several transactions referencing each of the active accounts so the
  "Added" lines and historic-transaction display are visible.

## Steps

1. **Close from detail.** Open the account detail for "Checking". Tap
   the close icon in the top bar. Verify the confirm dialog renders with
   the message about staying in records. Tap Close.
2. **Snackbar + Undo.** Verify a "Account closed" snackbar with an Undo
   action appears. Tap Undo. Verify the account reopens (no longer
   "Closed" pill, dropdown lists it again).
3. **Close for real.** Repeat step 1, then dismiss the snackbar without
   tapping Undo. Verify the detail screen shows "Closed on MMM d, yyyy"
   below the account name.
4. **Dropdown hiding.** Open the Add Transaction screen. Verify the
   closed "Checking" account is NOT in the account dropdown. Verify it
   is also absent from the To-account dropdown (for transfers).
5. **Reopen from detail.** Tap the reopen icon on the closed account's
   detail screen. Confirm. Verify the snackbar "Account reopened"
   appears (no Undo). Verify the dropdown lists the account again.
6. **Closed-account filter.** Open the Accounts list. Verify "Show
   closed accounts" chip is OFF. Toggle it on. Verify closed accounts
   appear with 60% alpha and a "Closed" pill.
7. **Net balance inclusion.** With closed accounts visible (filter on)
   and not visible (filter off), verify the "Net balance (home)"
   number is the SAME in both views (closed accounts contribute to net
   balance).
8. **Historic transaction display.** Add a transaction against
   "Checking", then close "Checking". Open the transactions list.
   Verify the transaction row still shows the closed account's name.
   Verify the "Added MMM d" line is visible below the title.
9. **Detail-screen transaction list.** Open the closed account's detail
   screen. Verify its transaction list still renders and shows the
   closed account's name on each row.
10. **Close the seeded default.** Close the seeded "Cash wallet"
    (id=1) account. Verify the close succeeds (no FK error). Verify a
    new transaction defaults to the next active account (lowest id).
11. **Open detail via deep-link (no accountId state).** With the closed
    "Checking" still archived, force-stop the app. Reopen. Navigate
    straight to the closed account's detail via the home/accounts
    list. Verify the detail screen still loads (no blank).
12. **Close + reopen idempotency.** Close "Savings". Close it again
    (re-open the detail and tap close a second time without
    intervening reopen). Verify the `archivedAtEpochMillis` updates to
    the most-recent close time.
13. **All-archived default.** Close every active account. Open the
    Add-Edit Transaction screen. Verify the UI handles the missing
    default account gracefully (no crash). Verify reopening any
    account makes the default reappear.
14. **Migration upgrade.** Build a debug APK with `version = 7` of
    AppDatabase, install, seed an account, then install a v8 APK on
    top. Verify the existing account's `archivedAtEpochMillis` is
    `null` and the app still launches cleanly.

## Expected outcomes

- Step 1: dialog appears, Cancel keeps the account active.
- Step 2: account reopens, dropdowns restore.
- Step 3: snackbar fires once and the screen shows "Closed on …".
- Step 4: closed account absent from both account dropdowns.
- Step 5: account reappears, no data lost.
- Step 6: chip toggle swaps source; closed rows render muted with pill.
- Step 7: net balance unchanged by filter toggle.
- Step 8 / 9: closed account name visible on historic txns; "Added"
  line present.
- Step 10: seeded default closes; default fallback works.
- Step 11: detail screen resolves closed accounts.
- Step 12: closing twice updates the timestamp; reopen wipes it.
- Step 13: graceful empty default; reopens restore default.
- Step 14: schema migration runs cleanly; legacy data unchanged.

## Rollback

`adb shell pm clear io.github.jiro.expensetracker` resets app data and
removes all accounts.
