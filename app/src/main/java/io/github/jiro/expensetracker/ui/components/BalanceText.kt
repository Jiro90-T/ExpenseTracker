package io.github.jiro.expensetracker.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.preferences.SettingsRepository

/**
 * Hilt entry point that lets any composable obtain the singleton
 * [SettingsRepository] without going through a ViewModel. Used only
 * by the balance-visibility helpers below.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SettingsEntryPoint {
    fun settingsRepository(): SettingsRepository
}

@Composable
internal fun rememberSettingsRepository(): SettingsRepository {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SettingsEntryPoint::class.java,
        ).settingsRepository()
    }
}

/**
 * Reads the global "balance hidden" preference. When true, every
 * [BalanceText] in the composition masks its value.
 */
@Composable
fun rememberBalanceHidden(): Boolean {
    val repo = rememberSettingsRepository()
    val hidden by repo.balanceHidden.collectAsStateWithLifecycle()
    return hidden
}

/**
 * Returns a callback that toggles the global "balance hidden" flag.
 */
@Composable
fun rememberBalanceHiddenToggler(): () -> Unit {
    val repo = rememberSettingsRepository()
    return remember(repo) { { repo.setBalanceHidden(!repo.balanceHidden.value) } }
}

/**
 * Renders a currency amount that respects the global hide-balances toggle.
 * When hidden, displays [MASK] regardless of the underlying value.
 *
 * Use this in place of [Text] for any value the user might not want
 * revealed on screen (dashboard totals, account balances, etc.).
 *
 * @param amountMinor value in minor currency units (cents, sen, …)
 * @param currencyCode optional ISO code to append after the amount
 *                     (e.g. "USD"). Pass null to render the bare number.
 * @param showSign when true, prepends "+" for positive values.
 *                 MoneyFormat already handles "-" for negatives.
 */
@Composable
fun BalanceText(
    amountMinor: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = MaterialTheme.colorScheme.onSurface,
    currencyCode: String? = null,
    showSign: Boolean = false,
) {
    val hidden = rememberBalanceHidden()
    val mask = stringResource(R.string.balance_masked)
    val display = if (hidden) {
        mask
    } else {
        val sign = if (showSign && amountMinor > 0) "+" else ""
        val formatted = MoneyFormat.formatForDisplay(amountMinor)
        if (currencyCode.isNullOrBlank()) "$sign$formatted"
        else "$sign$formatted $currencyCode"
    }
    Text(
        text = display,
        modifier = modifier,
        style = style,
        color = color,
    )
}

/**
 * Renders a pre-formatted balance string (e.g. "39,318.12 MYR") that
 * respects the global hide-balances toggle. Use when the value is already
 * formatted by the caller and you only need the mask-or-show behavior.
 */
@Composable
fun BalanceText(
    preFormatted: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val hidden = rememberBalanceHidden()
    val mask = stringResource(R.string.balance_masked)
    Text(
        text = if (hidden) mask else preFormatted,
        modifier = modifier,
        style = style,
        color = color,
    )
}