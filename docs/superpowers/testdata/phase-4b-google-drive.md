# Phase 4b — Google Drive Provider — Smoke Test

## Scope

4b adds the first concrete cloud-sync provider. The OAuth flow, token
storage, and Drive REST v3 I/O are all wired up; the only thing missing
is a UI entry point (which 4d adds).

## Automated verification

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew testDebugUnitTest    # 25 new tests, 0 regressions
./gradlew :app:assembleDebug   # debug APK builds, Hilt graph resolves
```

Expected: `BUILD SUCCESSFUL` from both, with `testDebugUnitTest` reporting
`X/Y passing` where `Y = (previous count) + 25`.

The 25 new tests break down as:
- 8 `DriveApiClientTest` (MockWebServer)
- 3 `SyncTokensRepositoryTest` (Robolectric + Keystore)
- 12 `GoogleDriveCloudSyncRepositoryTest` (Robolectric + fakes)
- 2 `NoOpCloudSyncRepositoryTest` (new `signInIntent` + `handleSignInResult`)

## Manual verification

### Prerequisites

Before manual testing, the developer must:

1. Create an OAuth 2.0 Web Client ID in Google Cloud Console:
   - Application type: **Web application**
   - Authorized redirect URIs: leave empty (Play Services handles PKCE for
     installed apps via the `requestServerAuthCode` flow)
   - Note the Client ID string.
2. Either:
   - Paste the Client ID into `app/src/main/res/values/strings.xml` as the
     value of `default_web_client_id`, OR
   - Add `google.web.client.id=<your-client-id>` to `local.properties` (the
     `buildConfigField` reads it).

### Steps

- [ ] Build + install: `./gradlew :app:installDebug`
- [ ] Use a debug-only entry point (added in a future debug variant; for now,
      call `repo.signInIntent` directly from a test `Activity` or use
      `adb shell am start` to fire the OAuth intent).
- [ ] Complete Google consent. Verify `state` transitions to `SignedIn`.
- [ ] Push a snapshot. Verify `ExpenseTracker-sync.json` appears in the
      user's Drive root.
- [ ] Pull. Verify the snapshot decodes and `PullResult.Success` returns.
- [ ] Sign out. Verify tokens are wiped from DataStore (inspect
      `/data/data/io.github.jiro.expensetracker.debug/shared_prefs/sync_tokens.xml`
      — should be empty or absent).

## What this phase did NOT add

- No Settings UI for sign-in (4d).
- No sync status indicator (4d).
- No automatic push/pull triggers (4d).
- No WorkManager sync job (4d).
- No Dropbox provider (4c).
- No receipt binaries in cloud backup (later).
- No multi-account support (later).