package io.github.jiro.expensetracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * DESIGN 2 (Modern Colorful) palette — light only, vibrant purple primary.
 * Dynamic color is intentionally disabled so the brand is consistent across
 * devices (Android 12+ Material You would otherwise override our purple).
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

@Composable
fun ExpenseTrackerTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content,
    )
}
