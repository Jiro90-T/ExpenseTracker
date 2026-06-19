package io.github.jiro.expensetracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptOcrMergerTest {

    private fun fields(
        amountMinor: Long? = null,
        occurredAtEpochMillis: Long? = null,
        merchant: String? = null,
    ) = OcrFields(amountMinor, occurredAtEpochMillis, merchant)

    @Test
    fun merge_emptyList_returnsEmptyFields() {
        val out = ReceiptOcrMerger.merge(emptyList())
        assertNull(out.amountMinor)
        assertNull(out.occurredAtEpochMillis)
        assertNull(out.merchant)
    }

    @Test
    fun merge_singlePage_returnsThatPage() {
        val page = fields(amountMinor = 100L)
        val out = ReceiptOcrMerger.merge(listOf(page))
        assertEquals(100L, out.amountMinor)
    }

    @Test
    fun merge_picksFirstNonNullPerField() {
        val pages = listOf(
            fields(amountMinor = 100L),
            fields(amountMinor = 200L),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals(100L, out.amountMinor)
    }

    @Test
    fun merge_firstPageHasField_secondPageEmpty_stillUsesFirst() {
        val pages = listOf(
            fields(merchant = "A"),
            fields(),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals("A", out.merchant)
    }

    @Test
    fun merge_conflictOnOneField_othersIndependent() {
        val pages = listOf(
            fields(amountMinor = 100L, merchant = "A"),
            fields(amountMinor = 200L),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals(100L, out.amountMinor)
        assertEquals("A", out.merchant)
    }

    @Test
    fun merge_firstNonNullWins() {
        val pages = listOf(
            fields(amountMinor = 100L),
            fields(amountMinor = 200L),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals(100L, out.amountMinor)
    }

    @Test
    fun merge_allFieldsConflictAcrossPages_picksPerFieldWinner() {
        val pages = listOf(
            fields(amountMinor = 100L, occurredAtEpochMillis = 1000L, merchant = "A"),
            fields(amountMinor = 200L, occurredAtEpochMillis = 2000L, merchant = "B"),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals(100L, out.amountMinor)
        assertEquals(1000L, out.occurredAtEpochMillis)
        assertEquals("A", out.merchant)
    }

    @Test
    fun merge_allPagesEmpty_returnsEmptyFields() {
        val pages = listOf(
            fields(),
            fields(),
            fields(),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertNull(out.amountMinor)
        assertNull(out.occurredAtEpochMillis)
        assertNull(out.merchant)
    }
}
