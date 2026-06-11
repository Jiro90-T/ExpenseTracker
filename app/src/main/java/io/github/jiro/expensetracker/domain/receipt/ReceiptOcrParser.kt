package io.github.jiro.expensetracker.domain.receipt

/**
 * The fields a parser can extract from a receipt's OCR text. Any field may
 * be null if no confident match was found. The view model treats a non-null
 * field as a candidate to auto-fill the form (subject to the pristine-field
 * check).
 */
data class OcrFields(
    val amountMinor: Long?,
    val occurredAtEpochMillis: Long?,
    val merchant: String?,
) {
    val hasAny: Boolean
        get() = amountMinor != null || occurredAtEpochMillis != null || merchant != null
}

/**
 * Pure text parser. No Android types, fully unit-testable. Heuristics are
 * deliberately conservative: when in doubt, return null (the user can fill
 * the field manually). The order of try-patterns matters: a `Total: $5.40`
 * line beats a plain `$5.40` line beats a stray number.
 */
object ReceiptOcrParser {

    private val TOTAL_KEYWORDS = listOf("total", "amount due", "balance due", "grand total")
    private val SKIP_HEADERS = setOf("receipt", "invoice", "bill", "order", "tax invoice")

    fun parse(text: String): OcrFields {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val amountMinor = parseAmount(lines)
        val date = parseDate(text)
        val merchant = pickMerchant(lines)
        return OcrFields(amountMinor, date, merchant)
    }

    /** Find a plausible total. Prefer lines containing a total keyword; otherwise
     *  the largest value in the receipt. Skip percentages. */
    private fun parseAmount(lines: List<String>): Long? {
        val candidates = mutableListOf<Pair<String, Long>>()  // (lineText, minorUnits)

        val currencyRegex = Regex("""\$?\s?(\d{1,6}(?:[.,]\d{2}))""")
        for (line in lines) {
            val matches = currencyRegex.findAll(line)
            for (m in matches) {
                val raw = m.groupValues[1].replace(',', '.')
                val value = raw.toDoubleOrNull() ?: continue
                if (value < 0.01 || value > 100_000.0) continue
                val minor = Math.round(value * 100.0)
                candidates.add(line.lowercase() to minor)
            }
        }

        // Skip lines that look like percentages (digits followed by %).
        val nonPct = candidates.filterNot { (line, _) ->
            Regex("""\d+\s*%""").containsMatchIn(line)
        }
        if (nonPct.isEmpty()) return null

        val withKeyword = nonPct.filter { (line, _) ->
            TOTAL_KEYWORDS.any { kw -> line.contains(kw) }
        }
        val chosen = withKeyword.maxByOrNull { it.second }
            ?: nonPct.maxByOrNull { it.second }
        return chosen?.second
    }

    /** Try common date formats. The first parseable match wins. */
    private fun parseDate(text: String): Long? {
        // ISO: 2026-06-09
        val iso = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""").find(text)
        if (iso != null) {
            val cal = java.util.Calendar.getInstance()
            cal.set(iso.groupValues[1].toInt(), iso.groupValues[2].toInt() - 1, iso.groupValues[3].toInt(), 0, 0, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        // European dot: 09.06.2026  (DD.MM.YYYY)
        val eu = Regex("""\b(\d{2})\.(\d{2})\.(\d{4})\b""").find(text)
        if (eu != null) {
            val day = eu.groupValues[1].toInt()
            val mon = eu.groupValues[2].toInt()
            val year = eu.groupValues[3].toInt()
            if (day in 1..31 && mon in 1..12) {
                val cal = java.util.Calendar.getInstance()
                cal.set(year, mon - 1, day, 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }
        }

        // Slash, ambiguous; assume US MM/DD/YYYY first, fall back to DD/MM/YYYY
        val slash = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""").find(text)
        if (slash != null) {
            val a = slash.groupValues[1].toInt()
            val b = slash.groupValues[2].toInt()
            val year = slash.groupValues[3].toInt()
            // Try US: MM/DD/YYYY
            if (a in 1..12 && b in 1..31) {
                val cal = java.util.Calendar.getInstance()
                cal.set(year, a - 1, b, 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }
            // Fall back to DD/MM/YYYY
            if (b in 1..12 && a in 1..31) {
                val cal = java.util.Calendar.getInstance()
                cal.set(year, b - 1, a, 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }
        }
        return null
    }

    /** First non-trivial line that isn't a known header word. */
    private fun pickMerchant(lines: List<String>): String? {
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.length < 3) continue
            if (line.all { it.isDigit() || it.isWhitespace() || it == '$' || it == '.' || it == ',' }) continue
            if (SKIP_HEADERS.any { line.equals(it, ignoreCase = true) }) continue
            return line.take(60)
        }
        return null
    }
}
