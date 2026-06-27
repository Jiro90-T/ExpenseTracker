package io.github.jiro.expensetracker.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyFormatTest {

    @Test fun parseAmount_wholeNumber_padsFraction() {
        assertEquals(2_500L, MoneyFormat.parseAmountToMinor("25"))
    }

    @Test fun parseAmount_oneDecimal_padsFraction() {
        assertEquals(2_550L, MoneyFormat.parseAmountToMinor("25.5"))
    }

    @Test fun parseAmount_twoDecimals() {
        assertEquals(2_550L, MoneyFormat.parseAmountToMinor("25.50"))
        assertEquals(2_557L, MoneyFormat.parseAmountToMinor("25.57"))
    }

    @Test fun parseAmount_trimsWhitespace() {
        assertEquals(100L, MoneyFormat.parseAmountToMinor("  1  "))
    }

    @Test fun parseAmount_threeDecimalsTruncates() {
        // ".123" → take(2) → ".12" → 12
        assertEquals(2_512L, MoneyFormat.parseAmountToMinor("25.123"))
    }

    @Test fun parseAmount_empty_returnsNull() {
        assertNull(MoneyFormat.parseAmountToMinor(""))
        assertNull(MoneyFormat.parseAmountToMinor("   "))
    }

    @Test fun parseAmount_twoDots_returnsNull() {
        assertNull(MoneyFormat.parseAmountToMinor("1.2.3"))
    }

    @Test fun parseAmount_negative_returnsNull() {
        assertNull(MoneyFormat.parseAmountToMinor("-5"))
    }

    @Test fun parseAmount_nonNumeric_returnsNull() {
        assertNull(MoneyFormat.parseAmountToMinor("abc"))
    }

    @Test fun parseAmount_aboveMax_returnsNull() {
        assertNull(MoneyFormat.parseAmountToMinor("10000000000"))  // > 9_999_999_999
    }

    @Test fun formatAmount_twoDigitFraction() {
        assertEquals("25.00", MoneyFormat.formatAmountForEdit(2_500L))
        assertEquals("25.57", MoneyFormat.formatAmountForEdit(2_557L))
        assertEquals("0.05", MoneyFormat.formatAmountForEdit(5L))
    }

    @Test fun formatAmount_largeValue() {
        assertEquals("123456789.99", MoneyFormat.formatAmountForEdit(12_345_678_999L))
    }

    @Test fun formatDisplay_addsThousandsSeparator() {
        assertEquals("1,000.00", MoneyFormat.formatForDisplay(100_000L))
        assertEquals("1,234,567.89", MoneyFormat.formatForDisplay(123_456_789L))
        assertEquals("12,345,678,999.99", MoneyFormat.formatForDisplay(1_234_567_899_999L))
    }

    @Test fun formatDisplay_noSeparatorUnderThousand() {
        assertEquals("999.99", MoneyFormat.formatForDisplay(99_999L))
        assertEquals("0.05", MoneyFormat.formatForDisplay(5L))
        assertEquals("100.00", MoneyFormat.formatForDisplay(10_000L))
    }

    @Test fun formatDisplay_negative() {
        assertEquals("-1,000.00", MoneyFormat.formatForDisplay(-100_000L))
        assertEquals("-50.25", MoneyFormat.formatForDisplay(-5_025L))
    }

    @Test fun formatDisplay_zero() {
        assertEquals("0.00", MoneyFormat.formatForDisplay(0L))
    }
}
