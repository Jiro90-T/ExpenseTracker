package io.github.jiro.expensetracker.preferences

import io.github.jiro.expensetracker.domain.FxConverter

/**
 * One row of the FX rate list. [from] and [to] are 3-letter currency codes
 * (e.g. "USD", "EUR"); [rate] is the multiplicative factor: 1.0 [from] = [rate] [to].
 */
data class RateRow(
    val from: String,
    val to: String,
    val rate: Double,
) {
    val displayKey: String = "${from}_to_${to}"
}

/**
 * Pure: parses the [map] into a sorted list of valid [RateRow]s. Skips
 * malformed entries: empty from/to, from == to, non-positive rate, or a
 * key whose split on "_to_" does not produce exactly 2 segments. Defensive
 * for future migrations or manual prefs edits.
 */
internal fun parseRates(map: Map<String, Double>): List<RateRow> = map.entries
    .mapNotNull { entry ->
        val parts = entry.key.split("_to_")
        if (parts.size != 2) return@mapNotNull null
        if (parts[0].isBlank() || parts[1].isBlank()) return@mapNotNull null
        if (parts[0] == parts[1]) return@mapNotNull null
        if (entry.value <= 0.0) return@mapNotNull null
        RateRow(from = parts[0], to = parts[1], rate = entry.value)
    }
    .sortedBy { it.displayKey }

/**
 * Pure: adds a rate and auto-derives the reverse (1 / [rate]). The reverse
 * is omitted when the rate is non-positive (defensive). Existing entries
 * not touched by the operation are preserved.
 */
internal fun addRate(
    existing: Map<String, Double>,
    from: String,
    to: String,
    rate: Double,
): Map<String, Double> {
    val fromTo = FxConverter.rateKey(from, to)
    val toFrom = FxConverter.rateKey(to, from)
    val reverse = if (rate > 0.0) 1.0 / rate else 0.0
    val updated = existing.toMutableMap()
    updated[fromTo] = rate
    if (reverse > 0.0) updated[toFrom] = reverse
    return updated.toMap()
}

/**
 * Pure: removes a rate by its [displayKey] (e.g. "USD_to_EUR") and also
 * removes the reverse direction if present.
 */
internal fun removeRate(
    existing: Map<String, Double>,
    displayKey: String,
): Map<String, Double> {
    val updated = existing.toMutableMap()
    updated.remove(displayKey)
    val parts = displayKey.split("_to_")
    if (parts.size == 2) updated.remove(FxConverter.rateKey(parts[1], parts[0]))
    return updated.toMap()
}
