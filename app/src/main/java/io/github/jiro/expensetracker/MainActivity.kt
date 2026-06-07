package io.github.jiro.expensetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.ui.navigation.AppNavHost
import io.github.jiro.expensetracker.ui.theme.ExpenseTrackerTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Pick up the theme preference as state so the app re-themes
            // immediately when the user toggles it in Settings.
            val themePref by settingsRepository.theme.collectAsStateWithLifecycle()
            ExpenseTrackerTheme(themePreference = themePref) {
                AppNavHost()
            }
        }
    }
}
