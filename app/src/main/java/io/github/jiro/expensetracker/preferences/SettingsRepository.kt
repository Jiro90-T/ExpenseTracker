package io.github.jiro.expensetracker.preferences

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-selectable theme override. */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(loadTheme())
    val theme: StateFlow<ThemePreference> = _theme.asStateFlow()

    fun setTheme(value: ThemePreference) {
        prefs.edit { putString(KEY_THEME, value.name) }
        _theme.value = value
    }

    private fun loadTheme(): ThemePreference {
        val stored = prefs.getString(KEY_THEME, null) ?: return ThemePreference.SYSTEM
        return runCatching { ThemePreference.valueOf(stored) }.getOrDefault(ThemePreference.SYSTEM)
    }

    companion object {
        const val PREFS_NAME = "expense_tracker_settings"
        const val KEY_THEME = "theme"
    }
}
