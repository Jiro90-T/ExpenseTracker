# Expense Tracker

Personal-finance Android app. Offline-first: Room is the single source of truth; UI observes it via Flow.

## Tech stack

- Kotlin 2.0.21, Jetpack Compose + Material 3
- MVVM (ViewModel + StateFlow)
- Room (KSP)
- Hilt
- Coroutines / Flow
- Gradle 8.10.2, AGP 8.7.3, version catalog at `gradle/libs.versions.toml`
- minSdk 24, compileSdk / targetSdk 35

## Open in Android Studio

This project does not run from a bare JDK 8 / no-Android-SDK shell. Open it in **Android Studio Ladybug (2024.2.1) or newer**, which provides JDK 17 and the Android SDK, and will sync the Gradle wrapper automatically.

1. Android Studio → File → Open → select this directory.
2. Wait for the initial Gradle sync.
3. Run the `app` configuration on an emulator (API 24+) or a physical device.

## Headless / CLI build

If you have JDK 17 and `ANDROID_HOME` pointing at an Android SDK:

```bash
./gradlew assembleDebug
./gradlew test
./gradlew connectedAndroidTest   # needs a running emulator / device
```

## Application ID

`io.github.jiro.expensetracker` (owner: Jiro). Lives in `app/build.gradle.kts` as both `namespace` and `applicationId`. To rename (e.g. before publishing), update those two lines, all `package` declarations under `app/src/`, and the directory paths under `app/src/{main,test,androidTest}/java/io/github/`.

## Architecture (big picture)

```
UI (Compose)  ──observes──▶  ViewModel (StateFlow)  ──reads/writes──▶  Repository  ──▶  Room DAO  ──▶  SQLite
```

Offline-first means the read path never touches the network. When a future sync / backup layer is added, it plugs in at the Repository boundary — ViewModels stay unchanged.

## Roadmap

Items are ordered roughly by what a real user would want first. Effort is S (≤ ½ day), M (1–3 days), L (a week+). The two MVP items that have been deliberately deferred to a later phase are flagged with ⚠️.

### Phase 1 — Daily-use essentials *(ship these first)*

- [x] **Search** &nbsp;`S` &nbsp;·&nbsp; *Value: H* — A search bar in the home screen that filters the existing transactions `Flow` by title / note / category name. *(Shipped: `b151b8f`.)*
- [x] **Manual category CRUD UI** &nbsp;`S-M` &nbsp;·&nbsp; *Value: H* — Add / rename / delete user-created categories. Built-ins stay read-only (they're marked `isBuiltIn` in the schema). *(Shipped: `ecae9d6`.)*
- [x] **Settings screen** &nbsp;`S` &nbsp;·&nbsp; *Value: M* — App-wide preferences: theme override (light / dark / system), default currency, "about", and the data-management entries below. *(Shipped: `ab5f1e7` + theme/about/cur­rency in a follow-up. Currency is a "coming later" placeholder until the FX subsystem lands in Phase 2.)*
- [x] **Branded app icon** &nbsp;`S` &nbsp;·&nbsp; *Value: M* — Replace the placeholder dollar-glyph with a proper icon (adaptive layers, monochrome variant, multiple densities). Today's icon looks unfinished. *(Shipped in the latest commit — see the generator script at `scripts/generate_launcher_icon.py`.)*
- [x] **JSON backup & restore** &nbsp;`M` &nbsp;·&nbsp; *Value: H* — Export the full DB to a `backup-YYYY-MM-DD.json` file the user can keep; counterpart restore. ⚠️ Without this, **uninstalling the app loses all data** — this is the highest-risk gap. *(Shipped: `ab5f1e7`.)*

### Phase 2 — Power features

- [ ] **Recurring transactions** &nbsp;`M` &nbsp;·&nbsp; *Value: H* — Auto-create monthly / weekly entries (rent, salary). Needs a `RecurrenceRule` column on `TransactionEntity` and a `WorkManager` job to materialise the next occurrence.
- [ ] **Multi-currency** &nbsp;`M` &nbsp;·&nbsp; *Value: H* — Currency picker on the form; per-transaction `currencyCode` is already in the schema. The dashboard needs a user-set base currency and FX rates (manual or via a free API) to normalise totals.
- [ ] **Budgets per category per month** &nbsp;`M` &nbsp;·&nbsp; *Value: H* — Set a monthly budget; the dashboard shows progress (bar fills as you spend) and an overspend warning at 100 %.
- [ ] **Receipts / attachments** &nbsp;`M` &nbsp;·&nbsp; *Value: M* — Attach a photo to a transaction. App-internal storage (`<filesDir>/receipts/`) per the design decision; the JSON backup widens to `.zip` to include the media.
- [ ] **Trend line chart** &nbsp;`S` &nbsp;·&nbsp; *Value: M* — Daily cumulative balance over the selected period. Complements the existing pie + bar charts.

### Phase 3 — Quality & infrastructure

- [ ] **Unit tests** &nbsp;`M` &nbsp;·&nbsp; *Value: H* — `HomeViewModel`, `AddEditTransactionViewModel`, `TransactionRepository`, `CategoryRepository`, `CsvExporter`, `computeDashboardSummary`, `computeMonthlyTotals`. No tests exist today.
- [ ] **Compose UI tests** &nbsp;`M` &nbsp;·&nbsp; *Value: H* — `HomeScreen` (empty + populated), `AddEditTransactionScreen` (each validation error path), `DashboardSummaryCard`, `PieChartWithLegend`.
- [ ] **Replace destructive migration** &nbsp;`S` &nbsp;·&nbsp; *Value: H* — Today: `fallbackToDestructiveMigration()` wipes the DB on any schema bump. As soon as the app has real users, write a real `Migration(1, 2)` so existing data survives. The v2 schema JSON in `app/schemas/` is the source of truth for what to migrate.
- [ ] **GitHub Actions CI** &nbsp;`S` &nbsp;·&nbsp; *Value: M* — Run `./gradlew test lint` on every PR and push to `master`. Catches regressions before merge.
- [ ] **Detekt + ktlint** &nbsp;`S` &nbsp;·&nbsp; *Value: M* — Style enforcement in CI. Lock in conventions now, before the codebase grows.
- [ ] **Accessibility pass** &nbsp;`M` &nbsp;·&nbsp; *Value: M* — TalkBack labels on the FAB, swipe targets, chart slices; large-font support; minimum 4.5:1 contrast for the amount colours.
- [ ] **Crash reporting** &nbsp;`S` &nbsp;·&nbsp; *Value: M* — Firebase Crashlytics or Sentry. The current build has no way to learn about crashes from the field.

### Phase 4 — Sync & sharing

- [ ] **Cloud backup** &nbsp;`L` &nbsp;·&nbsp; *Value: H* — Google Drive or Dropbox via the official SDKs. Scheduled background sync. Real durability + restore on a new device.
- [ ] **Multi-device** &nbsp;`L` &nbsp;·&nbsp; *Value: M* — Builds on the sync layer. Conflict resolution: last-write-wins with a manual-merge UI for ties.
- [ ] **Share a single transaction** &nbsp;`S` &nbsp;·&nbsp; *Value: L* — Long-press a row → share as formatted text or a small image. Trivial once the CSV export exists.
- [ ] **Export to PDF** &nbsp;`M` &nbsp;·&nbsp; *Value: M* — Formatted statement: dashboard summary on top, transactions grouped by day. Built on Android's `PrintedPdfDocument` (no extra library).

### Phase 5 — Advanced (future exploration)

- [ ] **Voice input** &nbsp;`M` &nbsp;·&nbsp; *Value: M* — "Add five dollars for coffee" via on-device speech recognition. Pre-fills the form.
- [ ] **Receipt OCR** &nbsp;`L` &nbsp;·&nbsp; *Value: M* — Read a receipt photo, extract amount / merchant / date, prefill the form. ML Kit Text Recognition is the obvious base.
- [ ] **Bank integration** &nbsp;`L` &nbsp;·&nbsp; *Value: H but complex* — Plaid or similar. Adds regulatory burden; probably out of scope for a personal app.
- [ ] **Home-screen widget** &nbsp;`M` &nbsp;·&nbsp; *Value: M* — Quick-add from the launcher. Glance widget showing today's spend.

