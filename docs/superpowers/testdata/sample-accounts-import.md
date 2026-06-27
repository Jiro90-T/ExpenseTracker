# CSV Balance Import — Manual Smoke Test

Smoke test for the Settings → Import accounts from CSV flow.

## Pre-conditions

- App installed on a device or emulator.
- For a clean baseline: delete all existing accounts (long-press each in More → Accounts → Delete Account).
- `sample-accounts.csv` from this directory is available locally.

## Steps

1. Copy `sample-accounts.csv` to device storage. Example via adb:
   `adb push sample-accounts.csv /sdcard/Download/sample-accounts.csv`
2. Open Settings → Import accounts from CSV → Choose CSV file → pick `sample-accounts.csv`.
3. Verify the preview shows three rows: 1 WillCreate (green), 1 WillUpdate (blue, if a baseline account with matching name exists), 1 Rejected (red, currency mismatch). On a clean baseline expect 2 WillCreate and 1 Rejected.
4. Tap Apply. Verify the snackbar reports created/updated counts.
5. Navigate to More → Accounts. Verify new accounts appear with the expected icon, color, and opening balance per account type.
6. Re-pick the same CSV. Verify all 3 rows now show as WillUpdate or Rejected (nothing left to create on second import).
7. Empty-file test: create an empty `.csv` file (just the header or truly empty), pick it. Verify the "File is empty." snackbar appears and no accounts change.

## Expected outcomes

- Step 1: file is visible in the system file picker.
- Step 3: preview dialog renders without error; row colours match the action.
- Step 4: snackbar matches the actual imported/updated counts.
- Step 5: each new account shows the correct icon/color and the parsed opening balance.
- Step 6: no duplicate accounts created; previews reflect existing state.
- Step 7: import is a no-op aside from the snackbar.

## Rollback

If you want to start over, delete each account you imported (long-press in More → Accounts → Delete Account, added in v0.16.0) and re-pick the CSV.