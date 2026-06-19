package io.github.jiro.expensetracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptOcrParserTest {

    @Test
    fun parseAmount_totalKeyword_picksTotal() {
        val text = """
            ACME COFFEE
            123 Main St

            Latte       $3.00
            Croissant   $2.00
            Subtotal    $5.00
            Tax         $0.40
            Total       $5.40
        """.trimIndent()
        val f = ReceiptOcrParser.parse(text)
        assertEquals(540L, f.amountMinor)
    }

    @Test
    fun parseAmount_multipleLines_picksLargest() {
        val text = """
            Item 1   $2.00
            Item 2   $8.00
            Item 3   $3.00
        """.trimIndent()
        val f = ReceiptOcrParser.parse(text)
        assertEquals(800L, f.amountMinor)
    }

    @Test
    fun parseAmount_noReasonableValue_returnsNull() {
        val text = "Item 1234567  (no decimal, looks like a phone number)"
        val f = ReceiptOcrParser.parse(text)
        assertNull(f.amountMinor)
    }

    @Test
    fun parseAmount_skipsPercentages() {
        val text = "Discount 10% off\nSubtotal $5.00\nTotal $5.00"
        val f = ReceiptOcrParser.parse(text)
        // 10% should be ignored. Either $5.00 (subtotal or total) is acceptable;
        // both map to 500L.
        assertEquals(500L, f.amountMinor)
    }

    @Test
    fun parseDate_isoFormat() {
        val text = "Date: 2026-06-09\nTotal: $5.00"
        val f = ReceiptOcrParser.parse(text)
        // Don't assert the exact epoch (timezone-dependent) — just assert the
        // year/month/day. We check that the year is 2026 in the local zone.
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = f.occurredAtEpochMillis!! }
        assertEquals(2026, cal.get(java.util.Calendar.YEAR))
        assertEquals(java.util.Calendar.JUNE, cal.get(java.util.Calendar.MONTH))
        assertEquals(9, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    @Test
    fun parseDate_usSlashFormat() {
        val text = "06/09/2026"
        val f = ReceiptOcrParser.parse(text)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = f.occurredAtEpochMillis!! }
        assertEquals(2026, cal.get(java.util.Calendar.YEAR))
        assertEquals(java.util.Calendar.JUNE, cal.get(java.util.Calendar.MONTH))
        assertEquals(9, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    @Test
    fun parseDate_europeanDotFormat() {
        // Ambiguous between EU (09.06.2026 = 9 June) and US-ish (06 Sept 2026).
        // The parser tries EU first when dots are used; both are accepted as
        // long as the resulting year is 2026.
        val text = "09.06.2026"
        val f = ReceiptOcrParser.parse(text)
        assertEquals(2026, java.util.Calendar.getInstance().apply {
            timeInMillis = f.occurredAtEpochMillis!!
        }.get(java.util.Calendar.YEAR))
    }

    @Test
    fun parseDate_invalid_returnsNull() {
        val text = "not a date at all"
        val f = ReceiptOcrParser.parse(text)
        assertNull(f.occurredAtEpochMillis)
    }

    @Test
    fun pickMerchant_firstNonTrivialLine() {
        val text = """
            Whole Foods Market
            123 Main St
            $5.40
        """.trimIndent()
        val f = ReceiptOcrParser.parse(text)
        assertEquals("Whole Foods Market", f.merchant)
    }

    @Test
    fun pickMerchant_skipsHeaders() {
        val text = """
            RECEIPT
            ACME COFFEE
            $4.00
        """.trimIndent()
        val f = ReceiptOcrParser.parse(text)
        assertEquals("ACME COFFEE", f.merchant)
    }

    @Test
    fun pickMerchant_empty() {
        val f = ReceiptOcrParser.parse("")
        assertNull(f.merchant)
    }

    @Test
    fun parse_combinedFields_allExtracted() {
        val text = """
            WHOLE FOODS MARKET
            123 Main St

            Items   $12.00
            Total   $12.00

            Date: 2026-06-09
        """.trimIndent()
        val f = ReceiptOcrParser.parse(text)
        assertEquals(1200L, f.amountMinor)
        assertEquals("WHOLE FOODS MARKET", f.merchant)
        assertEquals(2026, java.util.Calendar.getInstance().apply {
            timeInMillis = f.occurredAtEpochMillis!!
        }.get(java.util.Calendar.YEAR))
    }

    // ---- confidence scores (Phase 2.14) ----

    @Test
    fun parseAmount_totalKeyword_hasConfidence1() {
        val out = ReceiptOcrParser.parse(
            "Subtotal: \$5.00\nTax: \$0.40\nTotal: \$5.40"
        )
        assertEquals(540L, out.amountMinor)
        assertEquals(1.0f, out.amountConfidence, 0.0001f)
    }

    @Test
    fun parseAmount_fallbackLargest_hasConfidence06() {
        val out = ReceiptOcrParser.parse("Item 1 \$2.00\nItem 2 \$8.00\nItem 3 \$3.00")
        assertEquals(800L, out.amountMinor)
        assertEquals(0.6f, out.amountConfidence, 0.0001f)
    }

    @Test
    fun parseAmount_percentageOnly_returnsNullWithZeroConfidence() {
        val out = ReceiptOcrParser.parse("Discount 10%")
        assertNull(out.amountMinor)
        assertEquals(0f, out.amountConfidence, 0.0001f)
    }

    @Test
    fun parseDate_iso_hasConfidence1() {
        val out = ReceiptOcrParser.parse("Date: 2026-06-09")
        assertNotNull(out.occurredAtEpochMillis)
        assertEquals(1.0f, out.dateConfidence, 0.0001f)
    }

    @Test
    fun parseDate_euDot_hasConfidence09() {
        val out = ReceiptOcrParser.parse("Date: 09.06.2026")
        assertNotNull(out.occurredAtEpochMillis)
        assertEquals(0.9f, out.dateConfidence, 0.0001f)
    }

    @Test
    fun parseDate_usSlash_hasConfidence07() {
        val out = ReceiptOcrParser.parse("Date: 06/09/2026")
        assertNotNull(out.occurredAtEpochMillis)
        assertEquals(0.7f, out.dateConfidence, 0.0001f)
    }

    @Test
    fun parseDate_ddmmSlashFallback_hasConfidence06() {
        // 20/06/2026 — a=20 is not a valid month in MM/DD, b=6 is valid MM in DD/MM
        val out = ReceiptOcrParser.parse("Date: 20/06/2026")
        assertNotNull(out.occurredAtEpochMillis)
        assertEquals(0.6f, out.dateConfidence, 0.0001f)
    }

    @Test
    fun pickMerchant_longHasLetters_hasConfidence1() {
        val out = ReceiptOcrParser.parse("Coffee & Co Downtown\n\$4.50")
        assertEquals("Coffee & Co Downtown", out.merchant)
        assertEquals(1.0f, out.merchantConfidence, 0.0001f)
    }

    @Test
    fun pickMerchant_shortButAcceptable_hasConfidence07() {
        // 4 chars (>= 3 minimum, < 10 high-confidence threshold) and has letters
        val out = ReceiptOcrParser.parse("Nana\n\$4.50")
        assertEquals("Nana", out.merchant)
        assertEquals(0.7f, out.merchantConfidence, 0.0001f)
    }
}
