package io.github.jiro.expensetracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import io.github.jiro.expensetracker.preferences.ThemePreference

/**
 * Light scheme — DESIGN 2 vibrant purple, brighter surfaces.
 * Locked to light in the original theme; now has a dark sibling for the
 * theme override setting.
 */
private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = OnBrandPrimary,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = OnBrandPrimaryContainer,
    secondary = BrandSecondary,
    onSecondary = OnBrandSecondary,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    background = Background,
    onBackground = OnBackground,
    outline = Outline,
)

/**
 * Dark scheme — same brand purple but with brighter / desaturated
 * variants for use on dark surfaces. Income/expense accents are
 * slightly lighter so they read against the dark surface.
 */
private val DarkColors = darkColorScheme(
    primary = DarkBrandPrimary,
    onPrimary = DarkOnBrandPrimary,
    primaryContainer = DarkBrandPrimaryContainer,
    onPrimaryContainer = DarkOnBrandPrimaryContainer,
    secondary = DarkBrandSecondary,
    onSecondary = DarkOnBrandSecondary,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    outline = DarkOutline,
    error = DarkExpenseRed,
)

@Composable
fun ExpenseTrackerTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> systemDark
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
