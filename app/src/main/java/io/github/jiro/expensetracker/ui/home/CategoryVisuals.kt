package io.github.jiro.expensetracker.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LaptopChromebook
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Stable, name-keyed color for a category. Same name → same color, even if
 * the user reorders the list or builds a new device. The palette is the same
 * 12-color set the pie chart uses, so the two are visually consistent.
 */
val CategoryPalette: List<Color> = listOf(
    Color(0xFF1A6CFF), // blue
    Color(0xFF03DAC5), // teal
    Color(0xFFFF6F00), // orange
    Color(0xFF9C27B0), // purple
    Color(0xFFE91E63), // pink
    Color(0xFF4CAF50), // green
    Color(0xFFFFC107), // amber
    Color(0xFF795548), // brown
    Color(0xFF607D8B), // blue-grey
    Color(0xFF009688), // teal-dark
    Color(0xFFFF5722), // deep orange
    Color(0xFF8BC34A), // light green
)

fun categoryColor(name: String): Color {
    val size = CategoryPalette.size
    val index = ((name.hashCode() % size) + size) % size
    return CategoryPalette[index]
}

/**
 * Maps the 12 built-in category names to a Material icon. User-added
 * categories (anything not in the table) get a generic `Category` icon —
 * the color still varies per name, so they remain visually distinct.
 */
fun categoryIcon(name: String): ImageVector = when (name.lowercase()) {
    // Expense
    "food" -> Icons.Filled.Restaurant
    "transport" -> Icons.Filled.DirectionsCar
    "housing" -> Icons.Filled.Home
    "bills" -> Icons.Filled.Receipt
    "entertainment" -> Icons.Filled.LocalMovies
    "shopping" -> Icons.Filled.ShoppingBag
    "health" -> Icons.Filled.HealthAndSafety
    "other" -> Icons.Filled.MoreHoriz

    // Income
    "salary" -> Icons.Filled.Work
    "freelance" -> Icons.Filled.LaptopChromebook
    "gift" -> Icons.Filled.CardGiftcard
    // "other" handled above; deliberately the same icon for both EXPENSE and INCOME

    // Per-name overrides for AttachMoney use cases — the helper above wins
    // because the case-insensitive match lands on the more-specific key first.
    else -> Icons.Filled.AttachMoney.takeIf { name.isNotBlank() } ?: Icons.Filled.Category
}
