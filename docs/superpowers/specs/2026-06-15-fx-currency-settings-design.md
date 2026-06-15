# Phase 2.10 — FX & Currency Settings UI — Design

**Status:** Approved 2026-06-15
**Phase:** 2.10
**Predecessors:** Phase 2.2 ships the data layer (`SettingsRepository.homeCurrency` + `fxRates`, `FxConverter` pure helper). Phase 2.10 ships the UI that surfaces both — the missing top half of the FX feature.

## Goal

Replace the placeholder text in the Settings screen with two real, working affordances:

1. **Home currency picker** — a dropdown of 10 common currencies (MYR, SGD, USD, EUR, GBP, JPY, CNY, CAD, AUD, TWD) plus a "Custom…" option that lets the user type any 3-letter ISO 4217 code. Updates `SettingsRepository.homeCurrency`.
2. **FX rate entry** — a list of existing rates (each row is `from → to  rate  X`), an "Add rate" button, and per-row delete. The Add dialog picks from-currency, to-currency, and the rate. Saving **auto-derives the reverse rate** (e.g. `USD_to_EUR = 0.92` → `EUR_to_USD = 1.087`) so the user only enters each pair once.

Out of scope (deferred): network/API rate source (no new deps in this phase), "last updated" UI (a `lastUpdatedEpochMs` field is added to `SettingsRepository` for future use but not surfaced), multi-currency budgets.

## User-visible behavior

When the user opens Settings:

- **Currency section** (replacing the existing `settings_currency_placeholder` text): a card showing the current home currency (e.g. "USD") and an "Edit" button. Tapping Edit opens a picker with the 10 predefined currencies and a "Custom…" option. The "Custom…" option reveals a text field for a 3-letter code; typing past 3 chars is rejected. On confirm, the home currency updates immediately and the picker closes.
- **FX rates section** (new): a card listing the current rates as rows `USD → EUR  0.92  X`. An "Add rate" button at the bottom of the card opens a dialog: From picker, To picker, rate text field. On OK, the rate is saved (and the reverse auto-derived) and the dialog closes. Each row has a trailing `X` icon that deletes the rate (and its reverse, if present).
- **Empty FX rates list**: shows `settings_fx_empty` ("No FX rates yet. Add one to convert foreign transactions.").
- All changes persist across app restarts (via SharedPreferences, the same mechanism Phase 2.2 ships for `homeCurrency` + `fxRates`).
- Switching the home currency from USD to EUR re-evaluates the dashboard summary, budget alerts, and amount-range filter (the existing Phase 2.2 plumbing).

## Data model

**No schema changes.** The `SettingsRepository` already exposes `homeCurrency: StateFlow<String>` and `fxRates: StateFlow<Map<String, Double>>`. We add a `setFxRates(map: Map<String, Double>)` method (parallel to the existing single-rate setters) and a `lastUpdatedEpochMs: StateFlow<Long>` field for future use.

**New types** in `app/src/main/java/io/github/jiro/expensetracker/preferences/SupportedCurrencies.kt` (new):

```kotlin
/** Currencies surfaced in the home currency dropdown. */
internal val SUPPORTED_CURRENCIES: List<String> = listOf(
    "MYR", "SGD", "USD", "EUR", "GBP", "JPY", "CNY", "CAD", "AUD", "TWD",
)

/** Common pairs pre-populated as hints in the "Add rate" dialog. */
internal val COMMON_CURRENCY_PAIRS: List<Pair<String, String>> = listOf(
    "USD" to "MYR",
    "USD" to "SGD",
    "USD" to "TWD",
    "USD" to "EUR",
    "USD" to "GBP",
    "USD" to "JPY",
    "USD" to "CNY",
)
```

**`RateRow` + 3 pure helpers** in `app/src/main/java/io/github/jiro/expensetracker/preferences/FxRateRepository.kt` (new, ~80 lines):

```kotlin
data class RateRow(
    val from: String,        // 3-letter code, e.g. "USD"
    val to: String,          // 3-letter code, e.g. "EUR"
    val rate: Double,         // 1.0 `from` = `rate` `to`
) {
    val displayKey: String = "${from}_to_${to}"
}

/** Pure: parses the map into a sorted list of valid [RateRow]s. Skips
 * malformed entries (empty from/to, from == to, non-positive rate, key
 * without exactly 2 segments when split on "_to_"). */
internal fun parseRates(map: Map<String, Double>): List<RateRow> = map.entries
    .mapNotNull { entry ->
        val parts = entry.key.split("_to_")
        if (parts.size != 2) return@mapNotNull null
        if (parts[0].isBlank() || parts[1].isBlank()) return@mapNotNull null
        if (parts[0] == parts[1]) return@mapNotNull null
        if (entry.value <= 0.0) return@mapNotNull null
        RateRow(from = parts[0], to = parts[1], rate = entry.value)
    }
    .sortedBy { it.displayKey }

/** Pure: adds a rate and auto-derives the reverse (1 / rate). The reverse
 * is omitted when the rate is non-positive (defensive). Existing entries
 * not touched by the operation are preserved. */
internal fun addRate(
    existing: Map<String, Double>,
    from: String,
    to: String,
    rate: Double,
): Map<String, Double> {
    val fromTo = FxConverter.rateKey(from, to)
    val toFrom = FxConverter.rateKey(to, from)
    val reverse = if (rate > 0.0) 1.0 / rate else 0.0
    val updated = existing.toMutableMap()
    updated[fromTo] = rate
    if (reverse > 0.0) updated[toFrom] = reverse
    return updated.toMap()
}

/** Pure: removes a rate by its `displayKey` (e.g. "USD_to_EUR") and also
 * removes the reverse direction if present. */
internal fun removeRate(
    existing: Map<String, Double>,
    displayKey: String,
): Map<String, Double> {
    val updated = existing.toMutableMap()
    updated.remove(displayKey)
    val parts = displayKey.split("_to_")
    if (parts.size == 2) updated.remove(FxConverter.rateKey(parts[1], parts[0]))
    return updated.toMap()
}
```

## Components

| File | Purpose |
| --- | --- |
| `preferences/SupportedCurrencies.kt` (new) | `SUPPORTED_CURRENCIES` + `COMMON_CURRENCY_PAIRS` `internal val`s. |
| `preferences/FxRateRepository.kt` (new) | `RateRow` data class + 3 pure helpers (`parseRates`, `addRate`, `removeRate`). |
| `app/src/test/.../preferences/FxRateRepositoryTest.kt` (new) | 5 JVM tests for the 3 helpers. |
| `preferences/SettingsRepository.kt` (modified) | Add `setFxRates(map)` and `lastUpdatedEpochMs: StateFlow<Long>` field. |
| `ui/settings/SettingsViewModel.kt` (modified) | Add 3 new methods (`setHomeCurrency`, `addFxRate`, `removeFxRate`). |
| `ui/settings/SettingsScreen.kt` (modified) | Replace the placeholder section with 2 new sections. |
| `res/values/strings.xml` (modified) | ~11 new strings. |
| `app/src/main/AndroidManifest.xml` | No changes. |

## `SettingsRepository` changes

Add 1 new method and 1 new field:

```kotlin
// New field (default 0L = "never refreshed"; not surfaced in v0.10.0 UI).
private val _lastUpdatedEpochMs = MutableStateFlow(0L)
val lastUpdatedEpochMs: StateFlow<Long> = _lastUpdatedEpochMs.asStateFlow()

// New setter (parallel to the existing single-rate setFxRate / removeFxRate).
fun setFxRates(rates: Map<String, Double>) {
    prefs.edit { putString(KEY_FX_RATES, FxConverter.encode(rates)) }
    _fxRates.value = rates
}
```

The `lastUpdatedEpochMs` field is added for future use. No UI surfaces it in this phase. The setter is added in a future polish pass (network API source).

## `SettingsViewModel` changes

Add 3 new methods:

```kotlin
fun setHomeCurrency(code: String) {
    require(code.length == 3) { "Currency code must be 3 letters" }
    settingsRepository.setHomeCurrency(code.uppercase())
}

fun addFxRate(from: String, to: String, rate: Double) {
    require(from != to) { "From and To must differ" }
    require(rate > 0.0) { "Rate must be positive" }
    val updated = addRate(settingsRepository.fxRates.value, from, to, rate)
    settingsRepository.setFxRates(updated)
}

fun removeFxRate(displayKey: String) {
    val updated = removeRate(settingsRepository.fxRates.value, displayKey)
    settingsRepository.setFxRates(updated)
}
```

The setters delegate the validation + auto-derive logic to the pure helpers and forward the resulting map to the repo.

## UI

**`SettingsScreen.kt` changes** — replace the existing `settings_currency_placeholder` text with 2 new sections:

**Section 1: Home currency**
- A `Card` with a `Row` inside: the current currency code (e.g. "USD") in a `titleMedium` style, and an "Edit" `TextButton` on the right.
- Tapping Edit opens an `AlertDialog`:
  - A list of `RadioButton`s, one per `SUPPORTED_CURRENCIES` entry. The currently-selected currency is checked.
  - A "Custom…" `RadioButton` at the bottom. When selected, a single `OutlinedTextField` appears below for a 3-letter code. As the user types, the field auto-uppercases the input.
  - "OK" and "Cancel" buttons. The "OK" button is enabled when the selection is valid (predefined or 3-letter custom code).
- On OK, calls `viewModel.setHomeCurrency(code)`.

**Section 2: FX rates**
- A `Card` with a `Column` inside.
- For each `RateRow` in `parseRates(viewModel.fxRates.value)`, a `Row` with:
  - The from-currency on the left, an arrow `→`, the to-currency.
  - The rate value formatted as `"0.92"` (4 decimal places, no currency symbol).
  - A trailing `IconButton` with an `X` icon that calls `viewModel.removeFxRate(displayKey)`.
- An empty state: if `parseRates(...).isEmpty()`, show `Text(settings_fx_empty)` (a friendly placeholder).
- An "Add rate" `TextButton` at the bottom of the card. Tapping opens an `AlertDialog`:
  - A "From" picker (a list of `RadioButton`s with `SUPPORTED_CURRENCIES` + a "Custom…" option at the bottom — same pattern as the home currency picker).
  - A "To" picker, same as From.
  - A `OutlinedTextField` for the rate, with `keyboardType = Decimal`.
  - "OK" and "Cancel" buttons. The "OK" button is enabled when from != to AND the rate is parseable as a positive double.
- On OK, calls `viewModel.addFxRate(from, to, rate)`.

The dialogs are private composables inside `SettingsScreen.kt` (or extracted to small helpers if the file grows past ~400 lines).

## Strings to add

```
settings_currency_section     "Currency"
settings_currency_edit       "Edit"
settings_currency_custom     "Custom…"
settings_currency_custom_hint "3-letter code (e.g. USD)"
settings_fx_section          "FX rates"
settings_fx_add              "Add rate"
settings_fx_from             "From"
settings_fx_to               "To"
settings_fx_rate_hint        "Rate (1 from = ? to)"
settings_fx_empty            "No FX rates yet. Add one to convert foreign transactions."
settings_dialog_ok           "OK"
settings_dialog_cancel       "Cancel"
```

(11 new strings.)

## Tests

5 JVM tests in `app/src/test/.../preferences/FxRateRepositoryTest.kt`:

1. `parseRates_emptyMap_returnsEmptyList`
2. `parseRates_malformedKey_isSkipped` (e.g. `"_to_EUR"` with empty from, `"USD_to_"`, `"USD"`, `"USD_to_EUR_to_GBP"`)
3. `addRate_existingMap_preservesOtherRates_andDerivesReverse`
4. `addRate_zeroRate_returnsMapWithoutReverse` (defensive — 0 rate is rejected for reverse, only the direct rate is set)
5. `removeRate_removesBothDirections`

(5 new tests. 188 prior from v0.9.0 + 5 new = 193 total.)

## Edge cases

| Case | Behavior |
| --- | --- |
| User opens Settings, sees USD as home currency, taps Edit | Dialog opens with USD checked, no Custom… field shown. |
| User picks "Custom…" in the home picker, types "us" (2 chars) | Field rejects (length < 3); OK disabled. |
| User types "usd" (lowercase) in Custom… | Field auto-uppercases to "USD". OK enabled after 3 chars. |
| User picks "Custom…" and types "12X" (digits) | Field accepts (we only validate length); OK enabled. The currency code is "12X" — invalid in real life, but acceptable for v0.10.0. |
| User changes home currency from USD to EUR | All dashboard / budget / amount-range conversions re-evaluate. The `SettingsRepository.setHomeCurrency` already triggers this (emits new value to the `homeCurrency` StateFlow). |
| User adds USD_to_EUR = 0.92 | Both `USD_to_EUR = 0.92` and `EUR_to_USD = 1.087` are saved. |
| User deletes USD_to_EUR | Both `USD_to_EUR` and `EUR_to_USD` are deleted. |
| User adds a rate where from == to | OK button is disabled, inline error "From and To must differ". |
| User adds a rate of 0 or negative | OK button is disabled, inline error "Rate must be positive". |
| User adds a rate that already exists (e.g. USD_to_EUR = 0.92, opens dialog, types 0.95) | The new value OVERWRITES the old. The reverse is re-derived. The dialog does not pre-fill the existing value. |
| User has set rates before, then uninstalls + reinstalls | Rates are gone (SharedPreferences cleared). No migration. |
| The `parseRates` function encounters a malformed key (e.g. a key without `_to_`) | The entry is silently skipped. Defensive — defensive for future migrations or manual prefs edits. |
| The `lastUpdatedEpochMs` is 0L (default) | No UI surfaces this. Future polish pass adds a "last refreshed: never" indicator. |

## Out of scope (intentional, deferred)

- **FX rate source picker (manual vs free API like `open.er-api.com`)** — would need a new network layer, OkHttp/Retrofit/Ktor dep, and offline-mode error handling. The "MVP" is manual entry. Future polish pass.
- **Last-updated timestamp + refresh button** — the `lastUpdatedEpochMs: StateFlow<Long>` is added to `SettingsRepository` for future use, but no UI in this phase. The placeholder exists for a future "Refresh from open.er-api.com" feature.
- **Pre-populated common currency pairs** — `COMMON_CURRENCY_PAIRS` is defined as hints but not pre-populated into the rates. The user starts with an empty rate list. Future polish could pre-populate with the day's rates from the API.
- **Multi-currency budgets** (Phase 2.3 deferred item) — budget cap in non-home currency. The data layer supports it (the `BudgetEntity` has a `currencyCode`); only the UI is missing. Future polish.
- **The "thousands separators" search fix** for Transactions filter polish (Phase 2.9 follow-up).
- **Dark `NetBlue` variant** for the Trends chart (Phase 2.5 follow-up).
- **"Save to Photos" chart share** (Phase 2.5 follow-up).
- **Pinch-to-zoom on the receipt viewer** (Phase 2.4 follow-up).
- **Inline real-time amount-range validation** beyond the OK-disabled state.

## Open questions

None. Decisions taken one at a time during brainstorming and recorded in the User-visible behavior, Data model, Components, and Edge cases sections above.
