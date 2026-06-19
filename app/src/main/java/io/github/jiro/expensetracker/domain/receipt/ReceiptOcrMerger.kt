package io.github.jiro.expensetracker.domain.receipt

/**
 * Pure merger for multi-page OCR results. Picks the first non-null value
 * per field across pages. No Android types — fully JVM-testable.
 *
 * Phase 2.15: confidence was dropped from OcrFields, so the merger now falls
 * back to "first non-null" per field. Task 3 deletes this class entirely.
 */
object ReceiptOcrMerger {

    fun merge(pages: List<OcrFields>): OcrFields {
        if (pages.isEmpty()) {
            return OcrFields(null, null, null)
        }

        val firstAmount = pages.firstOrNull { it.amountMinor != null }
        val firstDate = pages.firstOrNull { it.occurredAtEpochMillis != null }
        val firstMerchant = pages.firstOrNull { it.merchant != null }

        return OcrFields(
            amountMinor = firstAmount?.amountMinor,
            occurredAtEpochMillis = firstDate?.occurredAtEpochMillis,
            merchant = firstMerchant?.merchant,
        )
    }
}
