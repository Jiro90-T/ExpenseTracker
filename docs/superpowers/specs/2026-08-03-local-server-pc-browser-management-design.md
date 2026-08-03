# Local Server — PC Browser Management — Design

**Status:** Approved 2026-08-03
**Phase:** New
**Predecessors:** None (greenfield feature inside the app). The existing repositories (`TransactionRepository`, `AccountRepository`, `CategoryRepository`, `BudgetRepository`) are the only seam.

## Goal

Add a small HTTP server, hosted inside the Android app, that serves a server-rendered HTML UI for managing **transactions, accounts, categories, and budgets** from a PC browser on the same LAN. The phone retains full editing capability while the server is running — the server is just another writer to Room.

Out of scope (deferred): receipt OCR from the browser, real-time push between phone and browser, mDNS auto-discovery, HTTPS, multi-device, multi-user.

## User-visible behavior

When the user opens **Settings → Local server**, they see a new card:

- **Toggle** ("Start server") — off by default. Turning it on:
  1. Generates a new 32-byte base64url session token.
  2. Starts a foreground service (`LocalServerService`) so Android keeps the server alive when the user switches to the browser.
  3. Posts a persistent notification ("Local server :8080 — tap to manage") that deep-links back to Settings.
  4. Binds Ktor to `0.0.0.0:8080`. If the port is already in use (e.g. another local server is running), the toggle reverts to off and a snackbar shows `"Port 8080 is in use."`.
- **URL display** — once running, shows the full URL including the token, e.g. `http://192.168.1.42:8080/?t=abc123…`. A "Copy" button puts it on the clipboard.
- **Status text** — "Running on port 8080 · 192.168.1.42" while on, "Off" while off.
- **Stop** — toggling off stops the service, removes the notification, clears the token.
- **IP source** — the controller picks the device's current WiFi IPv4 address (`wifiManager.connectionInfo.ipAddress` → InetAddress). Falls back to `"<unknown-ip>"` with a hint to reconnect WiFi if the address is unavailable.

The PC browser experience:

- Visiting the URL renders the **dashboard** (`GET /`). Last 10 transactions, this-month spend, total balance across open accounts.
- Top nav links: Dashboard, Transactions, Accounts, Categories, Budgets, Settings.
- Each list page filters/paginates. Each entity has a `New` button and per-row edit/delete.
- Filter form: `?account=<id>&from=<yyyy-MM-dd>&to=<yyyy-MM-dd>&category=<id>`. All optional. The list page renders an empty filter form above the rows; submitting it re-issues the GET with the params.
- Forms submit via POST; successful writes redirect-303 to the list page. Validation errors re-render the form with errors inline.
- Delete is via htmx: each row has a "Delete" button with `hx-post="…/delete" hx-confirm="Delete this transaction?" hx-target="closest tr" hx-swap="outerHTML"`. The delete handler returns an empty `tr` (placeholder) so the row vanishes.
- Inline edit is **not** in v1 — every edit goes through the full `/edit` page.
- A 401 page (missing/wrong token) shows a friendly message: "Run the server on your phone, then copy the URL from Settings."

## Architecture

New package `io.github.jiro.expensetracker.local` with five pieces:

### `LocalServerState` (data class)

```kotlin
data class LocalServerState(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val ipAddress: String? = null,    // null when WiFi unavailable
    val token: String? = null,        // null when not running
    val lastError: String? = null,    // for snackbar surfacing
)
```

### `LocalServerController` — Hilt singleton

Owns lifecycle. Single source of truth that Settings observes.

```kotlin
@Singleton
class LocalServerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val settingsRepository: SettingsRepository,
) {
    val state: StateFlow<LocalServerState>
    fun start(): Result<Unit>        // generates token, starts service
    fun stop()
    fun refreshIpAddress()
}
```

`start()` builds a `LocalServer` Ktor module with the repositories injected, generates a token, calls `LocalServerService.start(ctx, port, token)`. The service starts the Ktor server inside `Service.onCreate()` (or via a coroutine launched from it).

### `LocalServerService` — foreground service

```kotlin
class LocalServerService : Service() {
    override fun onStartCommand(intent: Intent, ...): Int {
        val port = intent.getIntExtra(EXTRA_PORT, 8080)
        val token = intent.getStringExtra(EXTRA_TOKEN)!!
        startForeground(NOTIFICATION_ID, buildNotification(port))
        scope.launch { server.run(port = port, token = token) }
        return START_NOT_STICKY
    }
    override fun onDestroy() { server.stop(); scope.cancel() }
    companion object {
        fun start(ctx: Context, port: Int, token: String)
        fun stop(ctx: Context)
    }
}
```

Notification: low-priority channel, `setOngoing(true)`, deep-link PendingIntent to `SettingsActivity` filtered to the local-server section.

### `LocalServer` — Ktor module

```kotlin
class LocalServer(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val settingsRepository: SettingsRepository,
    private val token: String,
) {
    fun Application.module() {
        install(DefaultHeaders)
        install(CallLogging) { level = Level.INFO }
        install(ContentNegotiation) { /* not needed — we render HTML */ }
        routing {
            authFilter(token)   // checks query param OR Authorization header
            get("/", ::dashboard)
            transactionsRoutes()
            accountsRoutes()
            categoriesRoutes()
            budgetsRoutes()
            staticResources("/static", "static") { /* htmx, picocss */ }
        }
    }
}
```

Embedded server: `embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }`. CIO is the pure-Kotlin engine that works on Android (Netty/Catalina don't).

### `AuthFilter` — Ktor interceptor

```kotlin
fun Route.authFilter(expectedToken: String) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.local.uri
        if (path.startsWith("/static/")) return@intercept  // assets are public
        val provided = call.request.queryParameters["t"]
            ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")
        if (provided != expectedToken) {
            call.respondText(renderUnauthorized(), status = HttpStatusCode.Unauthorized)
            finish()
        }
    }
}
```

The token is also accepted in a `t` form field on POSTs (so forms can submit it without JS).

## Routes (handler level)

Each handler is a small function that takes `ApplicationCall`, calls the repository, and either `respondText(html)` or `respondRedirect(path)`. Forms POST to the same handler that GETs them (classic POST-redirect-GET).

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Dashboard |
| GET | `/transactions` | List (filter by account, date, category) |
| GET | `/transactions/new` | Form |
| POST | `/transactions/new` | Create → 303 to `/transactions` |
| GET | `/transactions/{id}/edit` | Form |
| POST | `/transactions/{id}/edit` | Update → 303 to `/transactions` |
| POST | `/transactions/{id}/delete` | Delete → 303 to `/transactions` |
| GET | `/accounts` | List |
| GET | `/accounts/new` | Form |
| POST | `/accounts/new` | Create |
| GET | `/accounts/{id}/edit` | Form |
| POST | `/accounts/{id}/edit` | Update |
| POST | `/accounts/{id}/delete` | Delete (blocked if holdings exist; surfaces the existing block message) |
| GET | `/categories` | List |
| GET | `/categories/new` | Form |
| POST | `/categories/new` | Create |
| GET | `/categories/{id}/edit` | Form |
| POST | `/categories/{id}/edit` | Update |
| POST | `/categories/{id}/delete` | Delete (blocked if txs reference it) |
| GET | `/budgets` | List |
| GET | `/budgets/new` | Form |
| POST | `/budgets/new` | Create |
| GET | `/budgets/{id}/edit` | Form |
| POST | `/budgets/{id}/edit` | Update |
| POST | `/budgets/{id}/delete` | Delete |
| GET | `/settings` | Read-only view (home currency + FX rates table) |

~13 pages. Each form page is a Kotlinx.html function (~50-100 lines). htmx swaps (delete confirm, inline edit) are wired via `hx-post`, `hx-target`, `hx-confirm` attributes — no JS file of ours.

## Data model

**No schema changes.** All writes go through existing repositories. The server is a thin adapter layer.

**New files** (all under `app/src/main/java/io/github/jiro/expensetracker/local/`):

```
local/
├── LocalServerState.kt           (data class)
├── LocalServerController.kt      (Hilt singleton)
├── LocalServerService.kt         (foreground service)
├── LocalServer.kt                (Ktor module config)
├── routes/
│   ├── DashboardRoute.kt
│   ├── TransactionsRoutes.kt
│   ├── AccountsRoutes.kt
│   ├── CategoriesRoutes.kt
│   ├── BudgetsRoutes.kt
│   └── SettingsRoute.kt
├── templates/
│   ├── Layout.kt                 (page chrome: nav, header, footer)
│   ├── DashboardTemplate.kt
│   ├── TransactionsTemplate.kt
│   ├── AccountsTemplate.kt
│   ├── CategoriesTemplate.kt
│   ├── BudgetsTemplate.kt
│   ├── SettingsTemplate.kt
│   ├── FormFragments.kt          (text field, select, errors)
│   └── UnauthorizedTemplate.kt
├── auth/
│   ├── AuthFilter.kt
│   └── SessionTokenGenerator.kt  (32 bytes SecureRandom → base64url)
```

`LocalServerController` is `@Singleton` with `@Inject constructor(...)` — Hilt provides it directly via constructor injection. No `LocalServerModule` needed. The `LocalServer` Ktor instance is built by the controller (not a Hilt-provided singleton) since its lifetime is bound to the service.

**Static assets** bundled in `app/src/main/assets/static/`:
- `htmx.min.js` (vendored, ~14KB)
- `pico.min.css` (vendored, ~10KB)

**New strings** in `res/values/strings.xml`:
- `local_server_title`, `local_server_toggle_on`, `local_server_toggle_off`
- `local_server_status_running`, `local_server_status_off`
- `local_server_url_label`, `local_server_copy_url`
- `local_server_copy_failed`, `local_server_port_in_use`
- `local_server_notification_title`, `local_server_notification_text`
- `local_server_ip_unknown` ("<unknown-ip> — connect to WiFi")
- `local_server_unauthorized_title` ("Token missing or wrong")
- `local_server_unauthorized_body` ("Run the server on your phone, then copy the URL from Settings.")
- `local_server_foreground_channel_name`, `local_server_foreground_channel_description`
- `local_server_filter_account`, `local_server_filter_from`, `local_server_filter_to`, `local_server_filter_category`, `local_server_filter_apply`, `local_server_filter_clear`

**New permission** in `AndroidManifest.xml`: `android.permission.INTERNET` (already present at line 8 — verified). `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` (the latter is the closest matching type for "data sync" on Android 14+).

**App-level setup** in `ExpenseTrackerApp.onCreate()`:
- Create the notification channel `local_server_channel` with `IMPORTANCE_LOW` (low priority, no sound) for the foreground service notification.

## Error handling

- **Auth:** 401 with custom HTML. No token in the error page.
- **Validation:** handlers catch `IllegalArgumentException` from repository setters, re-render the form with field-level errors.
- **Repository failure:** 500 with a generic "Something went wrong" page. Log the full stack to Logcat with tag `LocalServer`. Never echo the stack to the browser.
- **Port in use:** `embeddedServer.start(wait = false)` throws `BindException`. Caught in `LocalServerController.start()`, surfaced as `state.lastError = "Port 8080 is in use."` and the toggle reverts.
- **Service kill:** if Android kills the service under memory pressure, the controller's state goes to `isRunning = false` on the next observed lifecycle change. The toggle reflects this within one polling tick (no special handling needed since the user reopens Settings manually).

## Concurrency & dispatcher

Ktor's CIO engine runs handlers on its own dispatcher pool. Existing repositories that touch Room use `Dispatchers.IO` internally. No new dispatcher setup. Handlers are `suspend` and call repos directly.

## Testing

### Unit tests (`app/src/test/java/.../local/`)

- `SessionTokenGeneratorTest` — generates 32-byte tokens, base64url-encoded, no `+/=`, length ~43.
- `AuthFilterTest` — Ktor `testApplication`: missing token → 401, wrong token → 401, right token (query) → 200, right token (header) → 200, static-asset path → 200 without token.
- `DashboardRouteTest` — renders with empty data; renders with seeded data.
- `TransactionsRoutesTest` — list, create, update, delete, validation error path.
- `AccountsRoutesTest` — same shape, plus delete-blocked-when-holdings case.
- `CategoriesRoutesTest` — same shape, plus delete-blocked-when-txs-reference case.
- `BudgetsRoutesTest` — list, create, update, delete.
- `LocalServerStateTest` — controller transitions: off → starting → running → stopping → off; error path reverts to off.

### Instrumented tests (`app/src/androidTest/java/.../local/`)

- `LocalServerServiceTest` — service starts Ktor on 8080, GET `/` returns 200 with the token, service stops cleanly.
- `LocalServerEndToEndTest` — happy path: add account on phone, see it in browser; add tx from browser, see it on phone (via Flow propagation, no extra sync).

### Manual verification (deployment checklist)

1. Install the debug APK.
2. Open Settings → Local server → toggle on.
3. Notification appears, URL shows.
4. Copy URL, paste in PC browser on same WiFi.
5. Dashboard loads with existing data.
6. Create a transaction from the browser, watch it appear on the phone.
7. Edit a transaction on the phone, refresh the browser, see the change.
8. Toggle off → server stops, browser gets connection refused, phone still works.

## Edge cases

- **No WiFi:** IP is null. Settings shows "Off — connect to WiFi to start".
- **Port 8080 in use:** toggle reverts, snackbar shows the error.
- **Token in browser history:** accepted as a trade-off for bookmarkable links. If the user wants stronger privacy, they can stop the server.
- **Concurrent edits from phone and browser:** last-write-wins (consistent with existing app behavior).
- **Multiple browsers:** same token works across all of them. The token is shared, not per-session.
- **CSRF:** POST-redirect-GET pattern + same-origin (the server is the only response surface) means no CSRF risk. No anti-CSRF token needed.

## Files

**New:**
- All files listed under "New files" in Data model.
- `app/src/main/AndroidManifest.xml` (modify): add `LocalServerService` declaration with `android:foregroundServiceType="dataSync"`.
- `app/src/main/res/values/strings.xml` (modify): add the strings listed.
- `app/build.gradle.kts` (modify) and `gradle/libs.versions.toml` (modify): add Ktor `server-cio`, `ktor-server-core`, `kotlinx-html-jvm` deps.

**Modified:**
- `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt` — add the Local server card.
- `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsViewModel.kt` — observe `LocalServerController.state`, expose `startServer()` / `stopServer()`.

**No changes to:**
- Data layer (Room, DAOs, entities).
- Existing repositories.
- Existing screens (transactions, accounts, etc.).
- Build config beyond the new deps.

## Rollout

1. Spec (this doc) — written 2026-08-03.
2. Plan — written after spec review.
3. Implementation — TDD via `superpowers:subagent-driven-development`.
4. Manual test on a real device with a real PC browser.
5. Tag as v0.21.0 (new feature, bump minor).
