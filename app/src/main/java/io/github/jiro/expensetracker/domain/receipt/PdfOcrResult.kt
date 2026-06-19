package io.github.jiro.expensetracker.domain.receipt

/**
 * The merged OCR result for a PDF receipt, plus the page counts needed to
 * build the snackbar. `pagesScanned` is the number of pages actually OCR'd
 * (capped at [ReceiptOcrProcessor.MAX_PDF_PAGES]). `totalPages` is the PDF's
 * full page count (or 0 if the file is missing/corrupt).
 *
 * Pure data carrier — no Android types.
 */
data class PdfOcrResult(
    val fields: OcrFields,
    val pagesScanned: Int,
    val totalPages: Int,
)
