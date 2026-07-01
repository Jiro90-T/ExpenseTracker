package io.github.jiro.expensetracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.ui.navigation.AppNavHost
import io.github.jiro.expensetracker.ui.theme.ExpenseTrackerTheme
import io.github.jiro.expensetracker.widget.EXTRA_MEMBER_CARD_ID
import javax.inject.Inject

/** Bubble so the widget deep-link can drive navigation without coupling AppNavHost to the activity. */
val LocalPendingMemberCardNavId = compositionLocalOf<Long?> { null }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    /** Set by [onCreate] and [onNewIntent]; consumed by AppNavHost via [LocalPendingMemberCardNavId]. */
    var pendingMemberCardNavId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingMemberCardNavId = intent.extractMemberCardNavId()
        enableEdgeToEdge()
        setContent {
            // Pick up the theme preference as state so the app re-themes
            // immediately when the user toggles it in Settings.
            val themePref by settingsRepository.theme.collectAsStateWithLifecycle()
            ExpenseTrackerTheme(themePreference = themePref) {
                CompositionLocalProvider(LocalPendingMemberCardNavId provides pendingMemberCardNavId) {
                    AppNavHost(activity = this)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingMemberCardNavId = intent.extractMemberCardNavId()
    }

    private fun Intent.extractMemberCardNavId(): Long? {
        // Sentinel `0L` (from EmptyStateAddAction) means "open the Add screen";
        // AppNavHost branches on that value. No extra → no nav.
        if (!hasExtra(EXTRA_MEMBER_CARD_ID)) return null
        return getLongExtra(EXTRA_MEMBER_CARD_ID, 0L)
    }
}
