package io.github.jiro.expensetracker.export

import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.domain.model.TransactionType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a CSV string from a (period-filtered) list of joined transactions.
 * Format follows RFC 4180: fields containing comma/quote/newline are wrapped
 * in double-quotes; embedded quotes are doubled.
 *
 * Columns: Date, Type, Category, Title, Amount, Currency, Note
 *   - Date is ISO 8601 (yyyy-MM-dd), local timezone.
 *   - Amount is a decimal (e.g. 12.99), no thousands separator; sign is implicit
 *     via the Type column.
 */
object CsvExporter {

    fun toCsv(rows: List<TransactionWithCategory>): String = buildString {
        appendLine("Date,Type,Category,Title,Amount,Currency,Note")
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for (row in rows) {
            val t = row.transaction
            val date = dateFmt.format(Date(t.occurredAtEpochMillis))
            val type = TransactionType.fromStorage(t.type).name
            val amount = "%d.%02d".format(t.amountMinor / 100, t.amountMinor % 100)
            append(csvEscape(date)).append(',')
            append(csvEscape(type)).append(',')
            append(csvEscape(row.category?.name.orEmpty())).append(',')
            append(csvEscape(t.title)).append(',')
            append(amount).append(',')
            append(csvEscape(t.currencyCode)).append(',')
            append(csvEscape(t.note.orEmpty()))
            append('\n')
        }
    }

    private fun csvEscape(value: String): String {
        if (value.isEmpty()) return value
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}
