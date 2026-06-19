package io.github.jiro.expensetracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptOcrMergerTest {

    private fun fields(
        amountMinor: Long? = null, amountConfidence: Float = 0f,
        occurredAtEpochMillis: Long? = null, dateConfidence: Float = 0f,
        merchant: String? = null, merchantConfidence: Float = 0f,
    ) = OcrFields(amountMinor, amountConfidence, occurredAtEpochMillis, dateConfidence, merchant, merchantConfidence)

    @Test
    fun merge_emptyList_returnsEmptyFields() {
        val out = ReceiptOcrMerger.merge(emptyList())
        assertNull(out.amountMinor)
        assertNull(out.occurredAtEpochMillis)
        assertNull(out.merchant)
        assertEquals(0f, out.amountConfidence, 0.0001f)
        assertEquals(0f, out.dateConfidence, 0.0001f)
        assertEquals(0f, out.merchantConfidence, 0.0001f)
    }

    @Test
    fun merge_singlePage_returnsThatPage() {
        val page = fields(amountMinor = 100L, amountConfidence = 1.0f)
        val out = ReceiptOcrMerger.merge(listOf(page))
        assertEquals(100L, out.amountMinor)
        assertEquals(1.0f, out.amountConfidence, 0.0001f)
    }

    @Test
    fun merge_picksHighestConfidencePerField() {
        val pages = listOf(
            fields(amountMinor = 100L, amountConfidence = 1.0f),
            fields(amountMinor = 200L, amountConfidence = 0.6f),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals(100L, out.amountMinor)
        assertEquals(1.0f, out.amountConfidence, 0.0001f)
    }

    @Test
    fun merge_firstPageHasField_secondPageEmpty_stillUsesFirst() {
        val pages = listOf(
            fields(merchant = "A", merchantConfidence = 1.0f),
            fields(),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals("A", out.merchant)
        assertEquals(1.0f, out.merchantConfidence, 0.0001f)
    }

    @Test
    fun merge_conflictOnOneField_othersIndependent() {
        val pages = listOf(
            fields(amountMinor = 100L, amountConfidence = 1.0f, merchant = "A", merchantConfidence = 1.0f),
            fields(amountMinor = 200L, amountConfidence = 0.6f),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals(100L, out.amountMinor)
        assertEquals("A", out.merchant)
    }

    @Test
    fun merge_tieOnConfidence_firstPageWins() {
        val pages = listOf(
            fields(amountMinor = 100L, amountConfidence = 0.6f),
            fields(amountMinor = 200L, amountConfidence = 0.6f),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals(100L, out.amountMinor)
    }

    @Test
    fun merge_allFieldsConflictAcrossPages_picksPerFieldWinner() {
        val pages = listOf(
            fields(amountMinor = 100L, amountConfidence = 1.0f, occurredAtEpochMillis = 1000L, dateConfidence = 0.9f, merchant = "A", merchantConfidence = 0.7f),
            fields(amountMinor = 200L, amountConfidence = 0.6f, occurredAtEpochMillis = 2000L, dateConfidence = 0.7f, merchant = "B", merchantConfidence = 1.0f),
        )
        val out = ReceiptOcrMerger.merge(pages)
        // per-field independence: each slot picks its own winner
        assertEquals(100L, out.amountMinor)
        assertEquals(1000L, out.occurredAtEpochMillis)
        assertEquals("B", out.merchant)
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
        assertEquals(0f, out.amountConfidence, 0.0001f)
        assertEquals(0f, out.dateConfidence, 0.0001f)
        assertEquals(0f, out.merchantConfidence, 0.0001f)
    }
}
