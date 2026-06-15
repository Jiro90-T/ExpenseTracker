package io.github.jiro.expensetracker.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FxRateRepositoryTest {

    @Test
    fun parseRates_emptyMap_returnsEmptyList() {
        val out = parseRates(emptyMap())
        assertTrue(out.isEmpty())
    }

    @Test
    fun parseRates_malformedKey_isSkipped() {
        // "_to_EUR" has empty from, "USD_to_" has empty to,
        // "USD" has no "_to_", "USD_to_EUR_to_GBP" has 3 segments,
        // "USD_to_USD" has from == to, "USD_to_EUR" -> 0.0 is non-positive.
        val out = parseRates(
            mapOf(
                "_to_EUR" to 0.5,
                "USD_to_" to 0.5,
                "USD" to 0.5,
                "USD_to_EUR_to_GBP" to 0.5,
                "USD_to_USD" to 1.0,
                "USD_to_EUR" to 0.0,
                "USD_to_GBP" to 0.79,  // the one valid entry
            )
        )
        assertEquals(1, out.size)
        assertEquals(RateRow("USD", "GBP", 0.79), out.first())
    }

    @Test
    fun addRate_existingMap_preservesOtherRates_andDerivesReverse() {
        val existing = mapOf(
            "EUR_to_GBP" to 0.85,
        )
        val out = addRate(existing, from = "USD", to = "MYR", rate = 4.7)
        assertEquals(0.85, out["EUR_to_GBP"]!!, 0.0001)
        assertEquals(4.7, out["USD_to_MYR"]!!, 0.0001)
        // Reverse is 1/4.7 ≈ 0.21277.
        assertEquals(1.0 / 4.7, out["MYR_to_USD"]!!, 0.0001)
    }

    @Test
    fun addRate_zeroRate_returnsMapWithoutReverse() {
        val out = addRate(emptyMap(), from = "USD", to = "EUR", rate = 0.0)
        // 0.0 is non-positive → only the direct rate is set; reverse is omitted.
        assertEquals(0.0, out["USD_to_EUR"]!!, 0.0001)
        assertTrue(out["EUR_to_USD"] == null)
    }

    @Test
    fun removeRate_removesBothDirections() {
        val existing = mapOf(
            "USD_to_EUR" to 0.92,
            "EUR_to_USD" to 1.087,
        )
        val out = removeRate(existing, "USD_to_EUR")
        assertTrue(out["USD_to_EUR"] == null)
        assertTrue(out["EUR_to_USD"] == null)
    }
}
