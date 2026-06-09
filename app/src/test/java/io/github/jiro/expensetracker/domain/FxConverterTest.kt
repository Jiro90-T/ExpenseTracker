package io.github.jiro.expensetracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FxConverterTest {

    @Test
    fun sameCurrency_isIdentity() {
        val rates = emptyMap<String, Double>()
        assertEquals(1234L, FxConverter.convertMinor(1234L, "USD", "USD", rates))
    }

    @Test
    fun knownRate_appliesMultiplicatively() {
        val rates = mapOf("USD_to_EUR" to 0.92)
        // 1000 USD-cents ($10.00) at 0.92 = 920 EUR-cents
        assertEquals(920L, FxConverter.convertMinor(1000L, "USD", "EUR", rates))
    }

    @Test
    fun fractionalRate_roundsHalfToEven() {
        val rates = mapOf("USD_to_JPY" to 149.65)
        // 100 * 149.65 = 14965.0 → exactly representable
        assertEquals(14965L, FxConverter.convertMinor(100L, "USD", "JPY", rates))
    }

    @Test
    fun missingRate_returnsNull() {
        val rates = mapOf("USD_to_EUR" to 0.92)
        // No USD_to_GBP rate known
        assertNull(FxConverter.convertMinor(1000L, "USD", "GBP", rates))
    }

    @Test
    fun emptyMapForUnknownPair_returnsNull() {
        assertNull(FxConverter.convertMinor(100L, "USD", "EUR", emptyMap()))
    }

    @Test
    fun blankCurrencyCode_returnsNull() {
        val rates = mapOf("USD_to_EUR" to 0.92)
        assertNull(FxConverter.convertMinor(100L, "", "EUR", rates))
        assertNull(FxConverter.convertMinor(100L, "USD", "", rates))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeRate_throws() {
        val rates = mapOf("USD_to_EUR" to -0.5)
        FxConverter.convertMinor(100L, "USD", "EUR", rates)
    }

    @Test
    fun zeroAmount_returnsZero() {
        val rates = mapOf("USD_to_EUR" to 0.92)
        assertEquals(0L, FxConverter.convertMinor(0L, "USD", "EUR", rates))
    }

    @Test
    fun roundTrip_isApproximatelyIdentity() {
        // EUR/USD == 1/0.92 ≈ 1.0870
        val rates = mapOf(
            "USD_to_EUR" to 0.92,
            "EUR_to_USD" to 1.0 / 0.92,
        )
        val original = 1000L
        val viaEur = FxConverter.convertMinor(original, "USD", "EUR", rates) ?: error("expected non-null")
        val back = FxConverter.convertMinor(viaEur, "EUR", "USD", rates) ?: error("expected non-null")
        // With banker's rounding, the round-trip can be off by 1 minor unit.
        assertTrue(
            "expected round-trip within 1 minor unit; original=$original, back=$back",
            kotlin.math.abs(original - back) <= 1,
        )
    }

    // ---- encode / decode ----

    @Test
    fun encodeDecode_roundTrip() {
        val rates = mapOf(
            "USD_to_EUR" to 0.92,
            "USD_to_JPY" to 149.65,
            "USD_to_MYR" to 4.71,
        )
        val encoded = FxConverter.encode(rates)
        val decoded = FxConverter.decode(encoded)
        assertEquals(rates, decoded)
    }

    @Test
    fun decodeEmpty_returnsEmpty() {
        assertEquals(emptyMap<String, Double>(), FxConverter.decode(""))
    }

    @Test
    fun decodeMalformed_skipsBadEntries() {
        val bad = "USD_to_EUR=0.92;broken_entry;USD_to_JPY=149.65;also_bad=value"
        val decoded = FxConverter.decode(bad)
        assertEquals(2, decoded.size)
        assertEquals(0.92, decoded["USD_to_EUR"]!!, 0.0)
        assertEquals(149.65, decoded["USD_to_JPY"]!!, 0.0)
    }

    @Test
    fun rateKey_formatsCorrectly() {
        assertEquals("USD_to_EUR", FxConverter.rateKey("USD", "EUR"))
    }
}
