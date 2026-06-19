package io.github.jiro.expensetracker.domain.receipt

/**
 * The fields a parser can extract from a receipt's OCR text. Any field may
 * be null if no confident match was found. The view model treats a non-null
 * field as a candidate to auto-fill the form (subject to the pristine-field
 * check).
 */
data class OcrFields(
    val amountMinor: Long?,
    val amountConfidence: Float,        // 0f when amountMinor == null, else 0.6f..1.0f
    val occurredAtEpochMillis: Long?,
    val dateConfidence: Float,          // 0f when occurredAtEpochMillis == null, else 0.6f..1.0f
    val merchant: String?,
    val merchantConfidence: Float,      // 0f when merchant == null, else 0.7f..1.0f
) {
    val hasAny: Boolean
        get() = amountMinor != null || occurredAtEpochMillis != null || merchant != null

    /** True iff all three fields are non-null. */
    val isComplete: Boolean
        get() = amountMinor != null && occurredAtEpochMillis != null && merchant != null
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

    /**
     * Parse raw OCR text into structured fields. Returns an [OcrFields] with
     * any field nullable when no confident match was found. Never throws.
     */
    fun parse(text: String): OcrFields {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val (amount, amountConf) = parseAmount(lines)
        val (date, dateConf) = parseDate(text)
        val (merchant, merchantConf) = pickMerchant(lines)
        return OcrFields(
            amountMinor = amount,
            amountConfidence = amountConf,
            occurredAtEpochMillis = date,
            dateConfidence = dateConf,
            merchant = merchant,
            merchantConfidence = merchantConf,
        )
    }

    /** Returns (value, confidence). Confidence is 0f when value is null. */
    private fun parseAmount(lines: List<String>): Pair<Long?, Float> {
        val candidates = mutableListOf<Pair<String, Long>>()

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

        val nonPct = candidates.filterNot { (line, _) ->
            Regex("""\d+\s*%""").containsMatchIn(line)
        }
        if (nonPct.isEmpty()) return null to 0f

        val withKeyword = nonPct.filter { (line, _) ->
            TOTAL_KEYWORDS.any { kw -> line.contains(kw) }
        }
        return if (withKeyword.isNotEmpty()) {
            withKeyword.maxBy { it.second }.second to 1.0f
        } else {
            nonPct.maxBy { it.second }.second to 0.6f
        }
    }

    /** Returns (value, confidence). Confidence is 0f when value is null. */
    private fun parseDate(text: String): Pair<Long?, Float> {
        val iso = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""").find(text)
        if (iso != null) {
            val cal = java.util.Calendar.getInstance()
            cal.set(iso.groupValues[1].toInt(), iso.groupValues[2].toInt() - 1, iso.groupValues[3].toInt(), 0, 0, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis to 1.0f
        }

        val eu = Regex("""\b(\d{2})\.(\d{2})\.(\d{4})\b""").find(text)
        if (eu != null) {
            val day = eu.groupValues[1].toInt()
            val mon = eu.groupValues[2].toInt()
            val year = eu.groupValues[3].toInt()
            if (day in 1..31 && mon in 1..12) {
                val cal = java.util.Calendar.getInstance()
                cal.set(year, mon - 1, day, 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis to 0.9f
            }
        }

        val slash = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""").find(text)
        if (slash != null) {
            val a = slash.groupValues[1].toInt()
            val b = slash.groupValues[2].toInt()
            val year = slash.groupValues[3].toInt()
            if (a in 1..12 && b in 1..31) {
                val cal = java.util.Calendar.getInstance()
                cal.set(year, a - 1, b, 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis to 0.7f
            }
            if (b in 1..12 && a in 1..31) {
                val cal = java.util.Calendar.getInstance()
                cal.set(year, b - 1, a, 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis to 0.6f
            }
        }
        return null to 0f
    }

    /** Returns (value, confidence). Confidence is 0f when value is null. */
    private fun pickMerchant(lines: List<String>): Pair<String?, Float> {
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.length < 3) continue
            if (line.all { it.isDigit() || it.isWhitespace() || it == '$' || it == '.' || it == ',' }) continue
            if (SKIP_HEADERS.any { line.equals(it, ignoreCase = true) }) continue
            val kept = line.take(60)
            val confidence = if (line.length >= 10 && line.any { it.isLetter() }) 1.0f else 0.7f
            return kept to confidence
        }
        return null to 0f
    }
}
