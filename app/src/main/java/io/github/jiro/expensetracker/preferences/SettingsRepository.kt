package io.github.jiro.expensetracker.preferences

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.domain.FxConverter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-selectable theme override. */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

@Singleton
open class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    // Lazily resolved so the constructor doesn't touch Android IO. JVM
    // tests can construct SettingsRepository with a stub Context (which
    // would throw on `getSharedPreferences`) without triggering the
    // preferences lookup.
    private val prefs: android.content.SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _theme: MutableStateFlow<ThemePreference> by lazy { MutableStateFlow(loadTheme()) }
    open val theme: StateFlow<ThemePreference> by lazy { _theme.asStateFlow() }

    fun setTheme(value: ThemePreference) {
        prefs.edit { putString(KEY_THEME, value.name) }
        _theme.value = value
    }

    private fun loadTheme(): ThemePreference {
        val stored = prefs.getString(KEY_THEME, null) ?: return ThemePreference.SYSTEM
        return runCatching { ThemePreference.valueOf(stored) }.getOrDefault(ThemePreference.SYSTEM)
    }

    // ---- Phase 2.2: home currency + FX rates ----

    /**
     * The currency that dashboard and chart totals are denominated in.
     * Defaults to USD until the user picks something else.
     */
    private val _homeCurrency: MutableStateFlow<String> by lazy { MutableStateFlow(loadHomeCurrency()) }
    open val homeCurrency: StateFlow<String> by lazy { _homeCurrency.asStateFlow() }

    open fun setHomeCurrency(code: String) {
        prefs.edit { putString(KEY_HOME_CURRENCY, code) }
        _homeCurrency.value = code
    }

    private fun loadHomeCurrency(): String =
        prefs.getString(KEY_HOME_CURRENCY, null) ?: DEFAULT_HOME_CURRENCY

    /**
     * FX rates as multiplicative factors keyed "FROM_to_TO" (e.g. "USD_to_EUR" = 0.92).
     * Persisted as a single "key=value;..." string for SharedPreferences round-trip.
     * Empty when the user hasn't entered any rates (UI will show a warning).
     */
    private val _fxRates: MutableStateFlow<Map<String, Double>> by lazy { MutableStateFlow(loadFxRates()) }
    open val fxRates: StateFlow<Map<String, Double>> by lazy { _fxRates.asStateFlow() }

    fun setFxRate(from: String, to: String, rate: Double) {
        if (from.isBlank() || to.isBlank() || rate < 0.0) return
        val updated = _fxRates.value.toMutableMap().apply {
            this[FxConverter.rateKey(from, to)] = rate
        }
        prefs.edit { putString(KEY_FX_RATES, FxConverter.encode(updated)) }
        _fxRates.value = updated.toMap()
    }

    fun removeFxRate(from: String, to: String) {
        val updated = _fxRates.value.toMutableMap().apply {
            remove(FxConverter.rateKey(from, to))
        }
        prefs.edit { putString(KEY_FX_RATES, FxConverter.encode(updated)) }
        _fxRates.value = updated.toMap()
    }

    /** Atomically replaces the entire FX rate map. Used by the UI's "Add rate"
     * (which writes both the direct and reverse rates in one go) and by the
     * "Remove rate" (which removes both directions in one go). */
    fun setFxRates(rates: Map<String, Double>) {
        prefs.edit { putString(KEY_FX_RATES, FxConverter.encode(rates)) }
        _fxRates.value = rates
    }

    private fun loadFxRates(): Map<String, Double> =
        FxConverter.decode(prefs.getString(KEY_FX_RATES, null).orEmpty())

    companion object {
        const val PREFS_NAME = "expense_tracker_settings"
        const val KEY_THEME = "theme"
        const val KEY_HOME_CURRENCY = "home_currency"
        const val KEY_FX_RATES = "fx_rates"
        const val DEFAULT_HOME_CURRENCY = "USD"
    }
}
