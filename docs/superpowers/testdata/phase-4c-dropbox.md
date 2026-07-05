# Phase 4c — Dropbox Provider — Smoke Test

## Scope

4c adds the Dropbox provider as the second concrete cloud-sync implementation.
OAuth uses AppAuth-Android (PKCE); tokens are bridged into a Keystore-protected
SharedPreferences store; HTTP I/O uses Dropbox API v2 with the `Dropbox-API-Arg`
header convention. 4c ships Dropbox-bound by default; 4d adds the provider
selector.

## Automated verification

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew testDebugUnitTest    # 22 new tests, 0 regressions
./gradlew :app:assembleDebug   # debug APK builds, Hilt graph resolves
```

Expected: `BUILD SUCCESSFUL` from both, with `testDebugUnitTest` reporting
`X/Y passing` where `Y = (v0.18.13 count) + 22`.

The 22 new tests break down as:
- 8 `DropboxApiClientTest` (MockWebServer)
- 4 `DropboxSyncTokensRepositoryTest` (Robolectric + FakeTokenCrypto)
- 10 `DropboxCloudSyncRepositoryTest` (Robolectric + fakes)

## Manual verification

### Prerequisites

Before manual testing, the developer must:

1. Create a Dropbox app at https://www.dropbox.com/developers/apps:
   - **API:** Scoped access
   - **Type of access:** App folder
   - **Name:** Expense Tracker (or similar)
   - **Permissions:** `account_info.read`, `files.content.read`, `files.content.write`
   - **Redirect URIs:** `io.github.jiro.expensetracker:/oauth2redirect`
2. Note the App key (this is the OAuth client_id).
3. Add `dropbox.client.id=<your-app-key>` to `local.properties`. The
   `buildConfigField` reads it into `BuildConfig.DROPBOX_CLIENT_ID`.

### Steps

- [ ] Build + install: `./gradlew :app:installDebug`
- [ ] Use a debug-only entry point (added in 4d; for now, call `repo.signInIntent`
      directly from a test Activity or fire the OAuth intent via
      `adb shell am start -a android.intent.action.VIEW -d "io.github.jiro.expensetracker:/oauth2redirect?..."`).
- [ ] Complete Dropbox consent. Verify `state` transitions to `SignedIn`.
- [ ] Push a snapshot. Verify `ExpenseTracker-sync.json` appears in the
      user's App folder (`/Apps/ExpenseTracker/ExpenseTracker-sync.json`).
- [ ] Pull. Verify the snapshot decodes and `PullResult.Success` returns.
- [ ] Sign out. Verify tokens are wiped from SharedPreferences (inspect
      `/data/data/io.github.jiro.expensetracker.debug/shared_prefs/dropbox_sync_tokens.xml`
      — should be empty or absent).

## What this phase did NOT add

- No Settings UI for provider selection or sign-in (4d).
- No sync status indicator (4d).
- No automatic push/pull triggers (4d).
- No WorkManager sync job (4d).
- No Google Drive provider as the active binding (4c ships with Dropbox bound
  by default; 4d adds the selector that flips between Drive and Dropbox).
- No Dropbox refresh-token flow (AppAuth's 4-hour access tokens are used as-is).
- No Dropbox folder picker (4c uses fixed path `/ExpenseTracker-sync.json`).
- No multi-account support (later).
- No receipt binaries in cloud backup (later).
