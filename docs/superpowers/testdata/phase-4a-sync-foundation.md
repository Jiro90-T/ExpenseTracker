# Phase 4a — Cloud Sync Foundation — Smoke Test

## Scope

4a is structural only — no UI, no I/O. There is nothing visible for the
user to do or see. The "smoke test" is therefore a build + test pass,
not an interactive flow.

## Automated verification

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew testDebugUnitTest    # 20 new tests, 0 regressions
./gradlew :app:assembleDebug   # debug APK builds, Hilt graph resolves
```

Expected: `BUILD SUCCESSFUL` from both, with `testDebugUnitTest` reporting
the full suite passing (the 4a additions are 2 BackupBody + 7
SyncSnapshotCodec + 3 DeviceIdProvider + 8 NoOpCloudSyncRepository = 20).

## Manual verification

- [ ] Open the app. Nothing should change — the home, transactions,
      statistics, accounts, and settings screens look and behave exactly
      as they did before this phase.
- [ ] The Settings screen does NOT have a "Sign in" or "Sync" entry.
- [ ] Force-stop and reopen the app. Still no change.

## What this phase did NOT add

- No cloud connection of any kind.
- No sign-in flow.
- No sync status indicator.
- No push/pull triggers.
- No data movement.

All of the above is reserved for 4b (Drive), 4c (Dropbox), and 4d (UI +
triggers + manual merge).