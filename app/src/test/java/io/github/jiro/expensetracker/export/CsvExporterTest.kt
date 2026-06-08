package io.github.jiro.expensetracker.export

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExporterTest {

    @Test
    fun emptyList_emitsOnlyTheHeader() {
        val csv = CsvExporter.toCsv(emptyList())
        assertEquals("Date,Type,Category,Title,Amount,Currency,Note\n", csv)
    }

    @Test
    fun singleRow_formatsAllFields() {
        val row = joined(
            title = "Lunch",
            type = "EXPENSE",
            categoryName = "Food",
            amountMinor = 1299,
            currency = "USD",
            note = null,
            occurredAt = dayOf(2026, 6, 7),
        )
        val csv = CsvExporter.toCsv(listOf(row))
        val lines = csv.trim().lines()
        assertEquals("Date,Type,Category,Title,Amount,Currency,Note", lines[0])
        assertEquals("2026-06-07,EXPENSE,Food,Lunch,12.99,USD,", lines[1])
    }

    @Test
    fun negativeBalance_isRepresentedByExpenseTypeAndPositiveAmount() {
        // Amount column is always positive; sign comes from the Type column.
        val row = joined(
            title = "Coffee",
            type = "EXPENSE",
            categoryName = "Food",
            amountMinor = 599,
            currency = "USD",
            note = null,
            occurredAt = dayOf(2026, 1, 1),
        )
        val csv = CsvExporter.toCsv(listOf(row))
        val dataLine = csv.trim().lines()[1]
        assertTrue("Expected EXPENSE in row: $dataLine", dataLine.contains(",EXPENSE,"))
        assertTrue("Expected positive 5.99: $dataLine", dataLine.contains(",5.99,"))
    }

    @Test
    fun noteWithCommaAndQuote_isProperlyEscaped() {
        val row = joined(
            title = "Lunch",
            type = "EXPENSE",
            categoryName = "Food",
            amountMinor = 100,
            currency = "USD",
            note = "W/ \"tax\", service, etc.",
            occurredAt = dayOf(2026, 6, 7),
        )
        val csv = CsvExporter.toCsv(listOf(row))
        val dataLine = csv.trim().lines()[1]
        // Quotes doubled, whole field wrapped in quotes
        assertTrue("Quote not escaped: $dataLine", dataLine.contains("\"\"tax\"\""))
        assertTrue("Comma not handled: $dataLine", dataLine.endsWith("\"W/ \"\"tax\"\", service, etc.\""))
    }

    @Test
    fun noteWithNewline_isQuoted() {
        val row = joined(
            title = "Multi",
            type = "EXPENSE",
            categoryName = "Food",
            amountMinor = 100,
            currency = "USD",
            note = "line1\nline2",
            occurredAt = dayOf(2026, 6, 7),
        )
        val csv = CsvExporter.toCsv(listOf(row))
        // Don't split on lines() — the embedded newline would split the row
        // apart. Instead, check the raw CSV string for the quoted multi-line
        // value, which is what an RFC 4180 parser would see.
        assertTrue("Newline-containing field must be quoted: $csv", csv.contains("\"line1\nline2\""))
    }

    @Test
    fun plainTextNote_isNotQuoted() {
        val row = joined(
            title = "Bus",
            type = "EXPENSE",
            categoryName = "Transport",
            amountMinor = 250,
            currency = "USD",
            note = "morning commute",
            occurredAt = dayOf(2026, 6, 7),
        )
        val csv = CsvExporter.toCsv(listOf(row))
        val dataLine = csv.trim().lines()[1]
        assertTrue("Plain note shouldn't be wrapped in quotes: $dataLine", dataLine.endsWith(",morning commute"))
    }

    @Test
    fun titleWithComma_isQuoted() {
        val row = joined(
            title = "Lunch, Dr. Smith",
            type = "EXPENSE",
            categoryName = "Food",
            amountMinor = 100,
            currency = "USD",
            note = null,
            occurredAt = dayOf(2026, 6, 7),
        )
        val csv = CsvExporter.toCsv(listOf(row))
        val dataLine = csv.trim().lines()[1]
        assertTrue("Comma in title must trigger quoting: $dataLine", dataLine.contains("\"Lunch, Dr. Smith\""))
    }

    @Test
    fun multipleRows_eachOnItsOwnLine() {
        val rows = listOf(
            joined("A", "EXPENSE", "Food", 100, "USD", null, dayOf(2026, 1, 1)),
            joined("B", "INCOME", "Salary", 500_000, "USD", null, dayOf(2026, 1, 15)),
            joined("C", "EXPENSE", "Transport", 250, "USD", null, dayOf(2026, 1, 20)),
        )
        val csv = CsvExporter.toCsv(rows)
        val lines = csv.trim().lines()
        assertEquals(4, lines.size)  // 1 header + 3 data
        assertEquals("Date,Type,Category,Title,Amount,Currency,Note", lines[0])
        assertTrue(lines[1].contains(",A,"))
        assertTrue(lines[2].contains(",B,"))
        assertTrue(lines[3].contains(",C,"))
    }

    // ---- helpers ----

    private fun dayOf(year: Int, month: Int, day: Int): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, 12, 0, 0)  // noon UTC — avoids DST edge cases
        return cal.timeInMillis
    }

    private fun joined(
        title: String,
        type: String,
        categoryName: String,
        amountMinor: Long,
        currency: String,
        note: String?,
        occurredAt: Long,
    ): TransactionWithCategory {
        val txn = TransactionEntity(
            id = 0L,
            title = title,
            amountMinor = amountMinor,
            currencyCode = currency,
            type = type,
            categoryId = 0L,
            occurredAtEpochMillis = occurredAt,
            note = note,
            createdAtEpochMillis = occurredAt,
        )
        val cat = CategoryEntity(
            id = 0L,
            name = categoryName,
            type = type,
            sortOrder = 0,
            isBuiltIn = true,
        )
        return TransactionWithCategory(txn, cat)
    }
}
