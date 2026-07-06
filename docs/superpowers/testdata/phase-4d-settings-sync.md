# Phase 4d — Settings UI + Sync Triggers + Provider Router — Smoke Test

## Scope

4d adds the user-facing cloud-sync experience. Previously (4a-c) the wiring
existed but the only way to sign in or trigger sync was via test entry
points. 4d:

- Replaces the lambda-token bug in `DropboxApiClientImpl` + `DriveApiClientImpl`
  with a real `*SyncTokensRepository` injection.
- Adds `SyncProviderId` enum + `Settings.syncProvider` so the user can
  switch between Dropbox and Google Drive from Settings.
- Adds `RoutingCloudSyncRepository` that mirrors the active provider's flows.
- Adds `TransactionMutationBus` so add/edit/delete operations trigger a
  debounced push, no manual button needed.
- Adds `SyncResult.ConflictPending(remote, local)` and a manual-merge screen.
- Fires a silent `syncOnce()` on app launch when signed in.
- Adds the Cloud-sync section to `SettingsScreen` with sign-in/sign-out/Sync-now buttons.

## Automated verification

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` from both; `testDebugUnitTest` reports
`X/Y passing` where `Y = 499 + 14_new_tests = 513`.

The 14 new tests break down as:
- 4 `SyncProviderIdTest`
- 3 `SettingsRepositoryTest`
- 3 `TransactionMutationBusTest`
- 2 `CloudSyncSessionStateTest`
- 4 `RoutingCloudSyncRepositoryTest` (state mirrors, provider flip, conflictPending passthrough, sign-in delegation)
- 2 `BackupManagerBodyTest`
- 1 `DropboxApiClientTest.upload_throwsAuthRevoked_whenTokenRepoReturnsNull`
- 1 `DriveApiClientTest.upload_throwsAuthRevoked_whenTokenRepoReturnsNull`
- 2 `DropboxCloudSyncRepositoryTest` / `GoogleDriveCloudSyncRepositoryTest`
  (`syncOnce` returns `NoRemoteSnapshot` when no remote — pins the
  orchestrator→router mapping compiles correctly)
- 2 `CloudSyncSectionTest` (signed-out shows Sign-in; conflict pending shows banner)

(Actual new tests may exceed 14 if Robolectric fakes are counted; the
spec's "~14" is the minimum.)

## Manual verification

### Prerequisites

Pre-existing: a Dropbox or Google Drive app registered per 4c / 4b docs;
`local.properties` has `dropbox.client.id=<app-key>` and/or
`google.web.client.id=<web-client-id>`.

### Steps

- [ ] Build + install: `./gradlew :app:installDebug`
- [ ] Sign in to Dropbox via Settings → Cloud sync → Sign in. Verify
      `state` flips to `Signed in as user@example.com (Dropbox)`.
- [ ] Add an offline transaction. Within 5 seconds (debounce), verify it
      appears in your Dropbox App folder at
      `/Apps/ExpenseTracker/ExpenseTracker-sync.json`.
- [ ] Tap Sync now. Verify the snackbar shows "Sync complete".
- [ ] Flip the provider to Google Drive (chip in Settings). Verify the
      state mirrors to `Signed out` and a Sign-in (Google) button appears.
- [ ] Sign in to Google Drive. Verify a snapshot begins uploading (you'll
      see a `last-synced` time update).
- [ ] Force a conflict: edit the cloud snapshot directly via the Drive web
      UI to bump `lastModifiedEpochMillis` past your local copy, then pull.
      Verify the conflict banner appears in Settings.
- [ ] Tap the banner. Verify the Conflict screen opens with two cards. Tap
      "Use cloud" — verify the local DB now matches the cloud snapshot.
- [ ] Restart the app from a cold launch (force-stop + reopen). Verify the
      silent `syncOnce()` fires (Sync state briefly shows "Syncing"), and
      `last-synced` is updated within a few seconds.
- [ ] Sign out. Verify the state flips to `Signed out` and tokens are
      cleared from `shared_prefs/sync_tokens.xml` (Drive) or
      `shared_prefs/dropbox_sync_tokens.xml` (Dropbox).

## What this phase did NOT add

- Multi-account.
- Receipt binaries in cloud backup.
- Periodic WorkManager sync.
- Dropbox refresh-token flow.
- Migrating pre-sync users' local data into cloud.
- Per-row conflict diff (resolution is at the snapshot level).