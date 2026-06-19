package io.github.jiro.expensetracker.domain.receipt

/**
 * Pure merger for multi-page OCR results. Picks the most-confident non-null
 * value per field across pages. Ties → first page wins.
 *
 * No Android types — fully JVM-testable.
 */
object ReceiptOcrMerger {

    fun merge(pages: List<OcrFields>): OcrFields {
        if (pages.isEmpty()) {
            return OcrFields(null, 0f, null, 0f, null, 0f)
        }

        val bestAmount = pages
            .filter { it.amountMinor != null }
            .maxByOrNull { it.amountConfidence }
        val bestDate = pages
            .filter { it.occurredAtEpochMillis != null }
            .maxByOrNull { it.dateConfidence }
        val bestMerchant = pages
            .filter { it.merchant != null }
            .maxByOrNull { it.merchantConfidence }

        return OcrFields(
            amountMinor = bestAmount?.amountMinor,
            amountConfidence = bestAmount?.amountConfidence ?: 0f,
            occurredAtEpochMillis = bestDate?.occurredAtEpochMillis,
            dateConfidence = bestDate?.dateConfidence ?: 0f,
            merchant = bestMerchant?.merchant,
            merchantConfidence = bestMerchant?.merchantConfidence ?: 0f,
        )
    }
}
