package io.github.jiro.expensetracker.ui.theme

import androidx.compose.ui.graphics.Color

// DESIGN 2 palette — vibrant purple primary, light surfaces, classic
// Material green/red for income/expense semantics. See Theme.kt.

// Primary: vibrant purple, used for the header bar, FAB, focused controls.
val BrandPrimary = Color(0xFF6750A4)
val OnBrandPrimary = Color(0xFFFFFFFF)
val BrandPrimaryContainer = Color(0xFFEADDFF)
val OnBrandPrimaryContainer = Color(0xFF21005D)

// Secondary: a complementary teal — used sparingly (badges, accents).
val BrandSecondary = Color(0xFF03DAC5)
val OnBrandSecondary = Color(0xFF003731)

// Surfaces — light, slightly tinted toward the primary so the brand reads.
val Surface = Color(0xFFFFFBFF)
val OnSurface = Color(0xFF1C1B1F)
val SurfaceVariant = Color(0xFFF3EDF7)
val OnSurfaceVariant = Color(0xFF49454F)
val Background = Color(0xFFFFFBFF)
val OnBackground = Color(0xFF1C1B1F)
val Outline = Color(0xFF79747E)

// Semantic — income (green) and expense (red) used in row amounts.
val IncomeGreen = Color(0xFF2E7D32)
val OnIncomeGreen = Color(0xFFFFFFFF)
val ExpenseRed = Color(0xFFC62828)
val OnExpenseRed = Color(0xFFFFFFFF)
