# Phase 2.10 — FX & Currency Settings UI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface the existing `SettingsRepository.homeCurrency` and `SettingsRepository.fxRates` data in the Settings screen — fill the Phase 2.2 UI gap with a home currency picker and a manual FX rate entry list (with auto-derived reverse rates).

**Architecture:** A new `FxRateRepository.kt` file holds the pure orchestrator logic (parse, add, remove) for the FX rate map. `SettingsRepository` gets a new `setFxRates(map)` method (parallel to the existing single-rate setters). The VM gains 3 new methods that delegate to the orchestrator. The Settings screen UI replaces the existing placeholder text with 2 new sections (home currency picker + FX rate list with add/remove).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 `AlertDialog` + `RadioButton` + `OutlinedTextField`), Hilt, JUnit 4.

**Working directory:** `F:/AndroidApp/ExpenseTracker`

**Required env (Windows):** `JAVA_HOME=C:/tools/jdk-21.0.5+11` (AGP 8.13.2 + bundled Kotlin choke on Java 8 and on Java 25+). Run gradle as:
```bash
export JAVA_HOME="C:/tools/jdk-21.0.5+11" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew <task>
```

**Commit identity:** All commits use inline author (no Co-Authored-By trailer):
```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "..."
```

---

## Task 1: Pure helpers + JUnit tests (TDD)

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/preferences/SupportedCurrencies.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/preferences/FxRateRepository.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/preferences/FxRateRepositoryTest.kt`

This task adds the `SUPPORTED_CURRENCIES` + `COMMON_CURRENCY_PAIRS` lists, the `RateRow` data class, and 3 pure helpers (`parseRates`, `addRate`, `removeRate`). 5 JVM tests.

- [ ] **Step 1: Create `SupportedCurrencies.kt`**

Create `app/src/main/java/io/github/jiro/expensetracker/preferences/SupportedCurrencies.kt` with this content:

```kotlin
package io.github.jiro.expensetracker.preferences

/**
 * Currencies surfaced in the home currency dropdown. Sorted by likelihood
 * of use (regional default first, then major global currencies).
 */
internal val SUPPORTED_CURRENCIES: List<String> = listOf(
    "MYR", "SGD", "USD", "EUR", "GBP", "JPY", "CNY", "CAD", "AUD", "TWD",
)

/**
 * Common pairs surfaced as hints in the "Add rate" dialog. Used to
 * pre-populate a starter set; the user can still add any pair.
 */
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

- [ ] **Step 2: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/preferences/FxRateRepositoryTest.kt` with this content:

```kotlin
package io.github.jiro.expensetracker.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FxRateRepositoryTest {

    @Test
    fun parseRates_emptyMap_returnsEmptyList() {
        val out = parseRates(emptyMap())
        assertTrue(out.isEmpty())
    }

    @Test
    fun parseRates_malformedKey_isSkipped() {
        // "_to_EUR" has empty from, "USD_to_" has empty to,
        // "USD" has no "_to_", "USD_to_EUR_to_GBP" has 3 segments,
        // "USD_to_USD" has from == to, "USD_to_EUR" -> 0.0 is non-positive.
        val out = parseRates(
            mapOf(
                "_to_EUR" to 0.5,
                "USD_to_" to 0.5,
                "USD" to 0.5,
                "USD_to_EUR_to_GBP" to 0.5,
                "USD_to_USD" to 1.0,
                "USD_to_EUR" to 0.0,
                "USD_to_GBP" to 0.79,  // the one valid entry
            )
        )
        assertEquals(1, out.size)
        assertEquals(RateRow("GBP", "USD", 0.79), out.first())
    }

    @Test
    fun addRate_existingMap_preservesOtherRates_andDerivesReverse() {
        val existing = mapOf(
            "EUR_to_GBP" to 0.85,
        )
        val out = addRate(existing, from = "USD", to = "MYR", rate = 4.7)
        assertEquals(0.85, out["EUR_to_GBP"]!!, 0.0001)
        assertEquals(4.7, out["USD_to_MYR"]!!, 0.0001)
        // Reverse is 1/4.7 ≈ 0.21277.
        assertEquals(1.0 / 4.7, out["MYR_to_USD"]!!, 0.0001)
    }

    @Test
    fun addRate_zeroRate_returnsMapWithoutReverse() {
        val out = addRate(emptyMap(), from = "USD", to = "EUR", rate = 0.0)
        // 0.0 is non-positive → only the direct rate is set; reverse is omitted.
        assertEquals(0.0, out["USD_to_EUR"]!!, 0.0001)
        assertTrue(out["EUR_to_USD"] == null)
    }

    @Test
    fun removeRate_removesBothDirections() {
        val existing = mapOf(
            "USD_to_EUR" to 0.92,
            "EUR_to_USD" to 1.087,
        )
        val out = removeRate(existing, "USD_to_EUR")
        assertTrue(out["USD_to_EUR"] == null)
        assertTrue(out["EUR_to_USD"] == null)
    }
```

- [ ] **Step 3: Run tests to verify they fail (function/type missing)**

Run: `./gradlew testDebugUnitTest --tests "*FxRateRepositoryTest"`
Expected: Compile error — `RateRow`, `parseRates`, `addRate`, `removeRate` are unresolved references.

- [ ] **Step 4: Create `FxRateRepository.kt`**

Create `app/src/main/java/io/github/jiro/expensetracker/preferences/FxRateRepository.kt` with this content:

```kotlin
package io.github.jiro.expensetracker.preferences

import io.github.jiro.expensetracker.domain.FxConverter

/**
 * One row of the FX rate list. [from] and [to] are 3-letter currency codes
 * (e.g. "USD", "EUR"); [rate] is the multiplicative factor: 1.0 [from] = [rate] [to].
 */
data class RateRow(
    val from: String,
    val to: String,
    val rate: Double,
) {
    val displayKey: String = "${from}_to_${to}"
}

/**
 * Pure: parses the [map] into a sorted list of valid [RateRow]s. Skips
 * malformed entries: empty from/to, from == to, non-positive rate, or a
 * key whose split on "_to_" does not produce exactly 2 segments. Defensive
 * for future migrations or manual prefs edits.
 */
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

/**
 * Pure: adds a rate and auto-derives the reverse (1 / [rate]). The reverse
 * is omitted when the rate is non-positive (defensive). Existing entries
 * not touched by the operation are preserved.
 */
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

/**
 * Pure: removes a rate by its [displayKey] (e.g. "USD_to_EUR") and also
 * removes the reverse direction if present.
 */
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

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "*FxRateRepositoryTest"`
Expected: 5/5 pass.

- [ ] **Step 6: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/preferences/SupportedCurrencies.kt \
  app/src/main/java/io/github/jiro/expensetracker/preferences/FxRateRepository.kt \
  app/src/test/java/io/github/jiro/expensetracker/preferences/FxRateRepositoryTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Settings: pure FX rate orchestrators (parseRates, addRate, removeRate) + 5 tests"
```

---

## Task 2: `SettingsRepository` + `SettingsViewModel` wiring

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/preferences/SettingsRepository.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsViewModel.kt`

This task adds 1 method to the repo (`setFxRates(map)`) and 3 methods to the VM (`setHomeCurrency`, `addFxRate`, `removeFxRate`).

- [ ] **Step 1: Add `setFxRates(map)` to `SettingsRepository.kt`**

Open `app/src/main/java/io/github/jiro/expensetracker/preferences/SettingsRepository.kt` and add a new method right after the existing `removeFxRate(from, to)` method (around line 75, before the `loadFxRates` private fun):

```kotlin
    /** Atomically replaces the entire FX rate map. Used by the UI's "Add rate"
     * (which writes both the direct and reverse rates in one go) and by the
     * "Remove rate" (which removes both directions in one go). */
    fun setFxRates(rates: Map<String, Double>) {
        prefs.edit { putString(KEY_FX_RATES, FxConverter.encode(rates)) }
        _fxRates.value = rates
    }
```

- [ ] **Step 2: Add 3 new methods to `SettingsViewModel.kt`**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsViewModel.kt` and add the following imports at the top (after the existing `import io.github.jiro.expensetracker.preferences.SettingsRepository` line; skip duplicates):

```kotlin
import io.github.jiro.expensetracker.preferences.addRate
import io.github.jiro.expensetracker.preferences.parseRates
import io.github.jiro.expensetracker.preferences.removeRate
```

Then add 3 new methods to the `SettingsViewModel` class (a good place is right after the existing `setTheme` method around line 43):

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

- [ ] **Step 3: Compile to verify**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. The data layer + VM are self-consistent.

- [ ] **Step 4: Run all unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: 193/193 pass (188 prior from v0.9.0 + 5 new from Task 1).

- [ ] **Step 5: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/preferences/SettingsRepository.kt \
  app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsViewModel.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Settings: repo setFxRates + VM setters for home currency and FX rates"
```

---

## Task 3: `SettingsScreen` UI + 11 new strings

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

This task replaces the existing `settings_currency_placeholder` text with 2 new sections (home currency picker + FX rate list with add/remove).

- [ ] **Step 1: Add 11 new strings to `strings.xml`**

Open `app/src/main/res/values/strings.xml` and add these lines at the end (after the existing settings strings):

```xml
    <string name="settings_currency_section">Currency</string>
    <string name="settings_currency_edit">Edit</string>
    <string name="settings_currency_custom">Custom…</string>
    <string name="settings_currency_custom_hint">3-letter code (e.g. USD)</string>
    <string name="settings_fx_section">FX rates</string>
    <string name="settings_fx_add">Add rate</string>
    <string name="settings_fx_from">From</string>
    <string name="settings_fx_to">To</string>
    <string name="settings_fx_rate_hint">Rate (1 from = ? to)</string>
    <string name="settings_fx_empty">No FX rates yet. Add one to convert foreign transactions.</string>
    <string name="settings_dialog_ok">OK</string>
    <string name="settings_dialog_cancel">Cancel</string>
```

- [ ] **Step 2: Update `SettingsScreen.kt` — add imports and replace the placeholder section**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt` and make 3 changes:

**(a)** Add these 7 new imports (after the existing `import io.github.jiro.expensetracker.preferences.SettingsRepository` line; skip duplicates):

```kotlin
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import io.github.jiro.expensetracker.preferences.SUPPORTED_CURRENCIES
import io.github.jiro.expensetracker.preferences.parseRates
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
```

**(b)** Find the existing placeholder text section. It looks like:

```kotlin
            SettingsSectionHeader(stringResource(R.string.settings_section_currency))
            Text(
                text = stringResource(R.string.settings_currency_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
```

Replace it with this 2-section block. The 2 new sections are wrapped in `items { ... }` if the parent is a `LazyColumn`, or just `Column { ... }` if not. The placeholder text is replaced with the home currency picker section first, then the FX rates section:

```kotlin
            // ---- Home currency section ----
            SettingsSectionHeader(stringResource(R.string.settings_currency_section))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = viewModel.homeCurrency.value,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showHomeCurrencyDialog = true }) {
                        Text(stringResource(R.string.settings_currency_edit))
                    }
                }
            }

            // ---- FX rates section ----
            SettingsSectionHeader(stringResource(R.string.settings_fx_section))
            val rateRows = remember(viewModel.fxRates) { parseRates(viewModel.fxRates.value) }
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    if (rateRows.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_fx_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    } else {
                        rateRows.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${row.from}  →  ${row.to}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "%.4f".format(row.rate).trimEnd('0').trimEnd('.'),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                IconButton(onClick = { viewModel.removeFxRate(row.displayKey) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.settings_dialog_cancel),
                                    )
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = { showAddRateDialog = true },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.settings_fx_add))
                    }
                }
            }
```

**(c)** Add the 2 dialog state vars and 2 dialog composables at the end of the `SettingsScreen` composable (before the closing `}` of the function). Insert them right after the `Scaffold { ... }` block and before the existing helper composables (e.g. `SettingsSectionHeader`):

```kotlin
    var showHomeCurrencyDialog by remember { mutableStateOf(false) }
    var showAddRateDialog by remember { mutableStateOf(false) }

    if (showHomeCurrencyDialog) {
        HomeCurrencyDialog(
            current = viewModel.homeCurrency.value,
            onDismiss = { showHomeCurrencyDialog = false },
            onConfirm = { code ->
                viewModel.setHomeCurrency(code)
                showHomeCurrencyDialog = false
            },
        )
    }
    if (showAddRateDialog) {
        AddRateDialog(
            onDismiss = { showAddRateDialog = false },
            onConfirm = { from, to, rate ->
                viewModel.addFxRate(from, to, rate)
                showAddRateDialog = false
            },
        )
    }
```

(You'll need to add `import androidx.compose.runtime.mutableStateOf`, `import androidx.compose.runtime.remember`, `import androidx.compose.runtime.setValue`, `import androidx.compose.runtime.getValue`, `import androidx.compose.runtime.mutableStateOf` to the imports if not already present. The new dialog composables `HomeCurrencyDialog` and `AddRateDialog` are added as private helpers further down in the file.)

**(d)** Add the 2 private dialog composables at the end of the file (after the existing `SettingsSectionHeader` helper):

```kotlin
@Composable
private fun HomeCurrencyDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var customCode by remember { mutableStateOf("") }
    val isCustom = selected == "CUSTOM"
    val effectiveCode = if (isCustom) customCode.uppercase() else (selected ?: current)
    val isValid = effectiveCode.length == 3

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(effectiveCode) }, enabled = isValid) {
                Text(stringResource(R.string.settings_dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_dialog_cancel))
            }
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_currency_section),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.size(8.dp))
                SUPPORTED_CURRENCIES.forEach { code ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = (selected == code) || (selected == null && code == current),
                            onClick = { selected = code },
                        )
                        Text(code, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = isCustom,
                        onClick = { selected = "CUSTOM" },
                    )
                    Text(
                        stringResource(R.string.settings_currency_custom),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (isCustom) {
                    OutlinedTextField(
                        value = customCode,
                        onValueChange = { customCode = it.uppercase().take(3) },
                        label = { Text(stringResource(R.string.settings_currency_custom_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun AddRateDialog(
    onDismiss: () -> Unit,
    onConfirm: (from: String, to: String, rate: Double) -> Unit,
) {
    var from by remember { mutableStateOf<String?>(null) }
    var to by remember { mutableStateOf<String?>(null) }
    var fromCustom by remember { mutableStateOf("") }
    var toCustom by remember { mutableStateOf("") }
    var rateInput by remember { mutableStateOf("") }
    val fromIsCustom = from == "CUSTOM"
    val toIsCustom = to == "CUSTOM"
    val effectiveFrom = if (fromIsCustom) fromCustom.uppercase() else (from ?: "")
    val effectiveTo = if (toIsCustom) toCustom.uppercase() else (to ?: "")
    val parsedRate = rateInput.toDoubleOrNull()
    val isValid = effectiveFrom.length == 3 && effectiveTo.length == 3 && effectiveFrom != effectiveTo && (parsedRate != null && parsedRate > 0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(effectiveFrom, effectiveTo, parsedRate!!) },
                enabled = isValid,
            ) { Text(stringResource(R.string.settings_dialog_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dialog_cancel)) }
        },
        text = {
            Column {
                // From picker
                Text(stringResource(R.string.settings_fx_from), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(4.dp))
                SUPPORTED_CURRENCIES.forEach { code ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = (from == code), onClick = { from = code })
                        Text(code, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = fromIsCustom, onClick = { from = "CUSTOM" })
                    Text(stringResource(R.string.settings_currency_custom), modifier = Modifier.padding(start = 8.dp))
                }
                if (fromIsCustom) {
                    OutlinedTextField(
                        value = fromCustom,
                        onValueChange = { fromCustom = it.uppercase().take(3) },
                        label = { Text(stringResource(R.string.settings_currency_custom_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.size(8.dp))
                // To picker
                Text(stringResource(R.string.settings_fx_to), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(4.dp))
                SUPPORTED_CURRENCIES.forEach { code ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = (to == code), onClick = { to = code })
                        Text(code, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = toIsCustom, onClick = { to = "CUSTOM" })
                    Text(stringResource(R.string.settings_currency_custom), modifier = Modifier.padding(start = 8.dp))
                }
                if (toIsCustom) {
                    OutlinedTextField(
                        value = toCustom,
                        onValueChange = { toCustom = it.uppercase().take(3) },
                        label = { Text(stringResource(R.string.settings_currency_custom_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.size(8.dp))
                // Rate input
                OutlinedTextField(
                    value = rateInput,
                    onValueChange = { rateInput = it },
                    label = { Text(stringResource(R.string.settings_fx_rate_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
```

You'll also need to add these imports to `SettingsScreen.kt` if not already present (the dialog helpers use them): `RadioButton`, `AlertDialog`, `TextButton`, `Card`, `Row`, `Column`, `OutlinedTextField`, `Text`, `Icon`, `IconButton`, `Spacer`, `Card`, `RadioButton`, `fillMaxWidth`. Most of these are likely already imported by the existing 327-line file. Skip duplicates per plan convention.

- [ ] **Step 3: Compile to verify**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run all unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: 193/193 pass.

- [ ] **Step 5: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt \
  app/src/main/res/values/strings.xml
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "UI: home currency picker + FX rate list with add/remove on Settings"
```

---

## Task 4: Final verification (assembleDebug + full test pass)

**Files:** none (read-only verification).

- [ ] **Step 1: Build the debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. APK written to `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: 193/193 pass, 0 failures, 0 errors.

- [ ] **Step 3: Sanity-check git state**

Run: `git log --oneline v0.9.0..HEAD`
Expected: 3 implementation commits (one per task: Task 1, 2, 3) plus the 2 doc commits (spec + spec self-review fix) that landed before Task 1.

- [ ] **Step 4: Report**

Report: build pass, test pass, commit count, and any smoke-test notes from the implementer. The on-device smoke test (open Settings, change home currency to EUR, add a USD_to_EUR rate, see both directions in the list, delete one direction, see both disappear) is described in the final review checklist.

---

## Self-review notes (already applied)

- **Spec coverage:** Every spec section maps to a task. Task 1 covers the 3 pure helpers + 5 tests. Task 2 covers the repo `setFxRates` + VM 3 setters. Task 3 covers the screen UI + 11 strings.
- **Placeholder scan:** No "TBD" or "implement later" anywhere. All code is complete. The `HomeCurrencyDialog` and `AddRateDialog` are concrete composables with explicit bodies.
- **Type consistency:** `RateRow(from, to, rate)`, `parseRates(map): List<RateRow>`, `addRate(existing, from, to, rate): Map<String, Double>`, `removeRate(existing, displayKey): Map<String, Double>` — all consistent across Tasks 1, 2, 3. The VM methods (`setHomeCurrency`, `addFxRate`, `removeFxRate`) and the repo method (`setFxRates`) are consistent. The strings are consistent.
- **Cumulative string-resource warning:** All 11 new strings are added in Task 3 Step 1, before any UI code references them. No incremental `R.string.settings_*` surprises.
- **`setHomeCurrency` auto-uppercases in the VM** (not the repo). The existing `SettingsRepository.setHomeCurrency(code)` does NOT auto-uppercase, so the VM's `code.uppercase()` call is the right place. The repo's `setHomeCurrency` becomes the "set this exact string" primitive.
- **`addRate` / `removeRate` are `internal`** (not `private`) — they're package-private, accessible from `SettingsViewModel` (same package `preferences`) and from the test file (same package). The visibility is correct.
- **The dialogs in Task 3 Step 2 (d) are large.** The plan's code blocks are exhaustive to avoid the implementer having to invent the dialog structure. The implementer can also extract the dialogs to separate files if `SettingsScreen.kt` grows past ~500 lines.

## Out of scope (intentional, deferred)

- FX rate source picker (manual vs free API) — new network layer, new dep.
- Last-updated timestamp + refresh button — `lastUpdatedEpochMs` field is added to `SettingsRepository` for future use, but no UI in this phase.
- Pre-populated common currency pairs — `COMMON_CURRENCY_PAIRS` is defined as hints but not pre-populated into the rates. Future polish.
- Multi-currency budgets (Phase 2.3 deferred).
- "thousands separators" search fix for Transactions filter polish (Phase 2.9 follow-up).
- Dark `NetBlue` variant for Trends chart (Phase 2.5 follow-up).
