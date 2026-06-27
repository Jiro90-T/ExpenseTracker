package io.github.jiro.expensetracker.data.accountimport

import io.github.jiro.expensetracker.data.local.MoneyFormat

/**
 * Pure CSV parser. Decodes UTF-8 bytes (with optional BOM), splits on
 * CRLF/LF, tokenizes per RFC 4180 (handles `"`-quoted fields with `""`
 * escapes and embedded commas), validates the header, then validates each
 * row. Per-row failures land in `ParseResult.Ok.rejected` keyed by their
 * 1-based line number; the rest are returned as `RawImportRow`s.
 *
 * Empty input → `ParseResult.Failed("File is empty.")`.
 */
object AccountImportParser {

    private val EXPECTED_HEADER = listOf("name", "type", "currency", "balance")

    fun parse(bytes: ByteArray): ParseResult {
        if (bytes.isEmpty()) return ParseResult.Failed("File is empty.")

        // Strip UTF-8 BOM if present.
        val body = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) bytes.copyOfRange(3, bytes.size) else bytes

        val text = body.toString(Charsets.UTF_8)
        if (text.isBlank()) return ParseResult.Failed("File is empty.")

        val lines = splitLines(text)
        if (lines.isEmpty()) return ParseResult.Failed("File is empty.")

        val header = tokenize(lines[0])
        if (!headerMatches(header)) {
            return ParseResult.Failed(
                "Header must be: ${EXPECTED_HEADER.joinToString(",")}",
            )
        }

        val rows = mutableListOf<RawImportRow>()
        val rejected = mutableListOf<Pair<Int, String>>()
        for (i in 1 until lines.size) {
            val fields = tokenize(lines[i])
            val lineNumber = i + 1
            validateAndAdd(fields, lineNumber, rows, rejected)
        }
        return ParseResult.Ok(rows, rejected)
    }

    private fun splitLines(text: String): List<String> {
        // Split on \r\n or \n, but skip fully-blank lines.
        return text.split("\r\n", "\n").filter { it.isNotEmpty() }
    }

    private fun headerMatches(header: List<String>): Boolean =
        header.size == EXPECTED_HEADER.size &&
            header.map { it.lowercase() } == EXPECTED_HEADER

    /**
     * Tokenize a single CSV line per RFC 4180. Handles `"`-quoted fields
     * with `""` as the escape for a literal quote, and commas inside quotes.
     */
    internal fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i += 2; continue
                }
                c == '"' -> { inQuotes = !inQuotes; i++; continue }
                c == ',' && !inQuotes -> {
                    out.add(sb.toString()); sb.clear(); i++; continue
                }
                else -> { sb.append(c); i++ }
            }
        }
        out.add(sb.toString())
        return out
    }

    private fun validateAndAdd(
        fields: List<String>,
        lineNumber: Int,
        rows: MutableList<RawImportRow>,
        rejected: MutableList<Pair<Int, String>>,
    ) {
        if (fields.size != 4) {
            rejected.add(lineNumber to "${fields.size} columns (expected 4)")
            return
        }
        val name = fields[0].trim()
        val type = fields[1].trim().uppercase()
        val currency = fields[2].trim().uppercase()
        val balanceStr = fields[3].trim()

        if (name.isEmpty()) {
            rejected.add(lineNumber to "name is required"); return
        }
        if (!currency.matches(Regex("^[A-Z]{3}$"))) {
            rejected.add(lineNumber to "currency must be a 3-letter code"); return
        }
        val balanceMinor = MoneyFormat.parseSignedAmountToMinor(balanceStr)
        if (balanceMinor == null) {
            rejected.add(lineNumber to "balance is not a valid amount"); return
        }
        rows.add(RawImportRow(lineNumber, name, type, currency, balanceMinor))
    }
}