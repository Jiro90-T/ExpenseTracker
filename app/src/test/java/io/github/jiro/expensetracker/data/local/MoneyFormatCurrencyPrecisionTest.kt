package io.github.jiro.expensetracker.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFormatCurrencyPrecisionTest {

    @Test fun priceToMinor_usdTwoDp() {
        assertEquals(12345L, MoneyFormat.priceToMinor(123.45, "USD"))
    }

    @Test fun priceToMinor_jpyZeroDp() {
        assertEquals(2800L, MoneyFormat.priceToMinor(2800.0, "JPY"))
    }

    @Test fun priceToMinor_jpyFractionalRounds() {
        assertEquals(2801L, MoneyFormat.priceToMinor(2800.6, "JPY"))
    }

    @Test fun priceToMinor_btcTwoDp() {
        assertEquals(6723456L, MoneyFormat.priceToMinor(67234.56, "BTC"))
    }

    @Test fun priceToMinor_unknownCurrencyDefaultsToTwoDp() {
        assertEquals(12345L, MoneyFormat.priceToMinor(123.45, "XYZ"))
    }

    @Test fun minorToDisplay_usdFormatsTwoDp() {
        assertEquals("123.45", MoneyFormat.minorToDisplay(12345L, "USD"))
    }

    @Test fun minorToDisplay_jpyFormatsZeroDp() {
        assertEquals("2,800", MoneyFormat.minorToDisplay(2800L, "JPY"))
    }

    @Test fun minorToDisplay_jpyFormatsThousandsSeparator() {
        assertEquals("1,234,567", MoneyFormat.minorToDisplay(1234567L, "JPY"))
    }

    @Test fun minorToDisplay_btcFormatsTwoDp() {
        assertEquals("67,234.56", MoneyFormat.minorToDisplay(6723456L, "BTC"))
    }
}
