package io.github.jiro.expensetracker.data.local

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.domain.receipt.OcrFields
import io.github.jiro.expensetracker.domain.receipt.PdfOcrResult
import io.github.jiro.expensetracker.domain.receipt.ReceiptOcrMerger
import io.github.jiro.expensetracker.domain.receipt.ReceiptOcrParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Wraps ML Kit Text Recognition (Latin, on-device) and feeds the result
 * into [ReceiptOcrParser]. On-device, no API key, ~1s for a typical receipt.
 *
 * Supports both image receipts (via [extract]) and PDF receipts (via
 * [extractFromPdf]). PDF receipts are rasterized to bitmaps via
 * [ReceiptRepository.renderPdfPage], OCR'd page-by-page, then merged via
 * [ReceiptOcrMerger].
 */
@Singleton
class ReceiptOcrProcessor @Inject constructor(
    private val receiptRepository: ReceiptRepository,
) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Run OCR on [bitmap]. Returns parsed fields; any field may be null if
     * the parser couldn't find a confident match. Throws on unrecoverable
     * ML Kit failure (caller catches and shows a snackbar).
     */
    suspend fun extract(bitmap: Bitmap): OcrFields = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                cont.resume(ReceiptOcrParser.parse(result.text))
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.cancel(e)
            }
    }

    /**
     * Run OCR on up to [maxPages] pages of the PDF at [relativePath].
     * Returns a [PdfOcrResult] with the merged fields plus page counts.
     *
     * Best-effort: any per-page failure (corrupted page, ML Kit error, OOM)
     * is logged and skipped. If all pages fail, returns an empty result.
     * Never throws.
     */
    suspend fun extractFromPdf(
        relativePath: String,
        maxPages: Int = MAX_PDF_PAGES,
    ): PdfOcrResult = withContext(Dispatchers.IO) {
        val totalPages = try {
            receiptRepository.openPdfPageCount(relativePath)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "openPdfPageCount failed for $relativePath", e)
            0
        }
        if (totalPages == 0) {
            return@withContext PdfOcrResult(OcrFields(null, 0f, null, 0f, null, 0f), 0, 0)
        }

        val pageCount = minOf(totalPages, maxPages)
        val pages = mutableListOf<OcrFields>()
        for (i in 0 until pageCount) {
            currentCoroutineContext().ensureActive()
            val pageResult = runCatching {
                val bitmap = receiptRepository.renderPdfPage(relativePath, i)
                try {
                    extract(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                android.util.Log.w(TAG, "OCR failed for $relativePath page $i", e)
                null
            }
            if (pageResult != null) pages += pageResult
        }

        PdfOcrResult(
            fields = ReceiptOcrMerger.merge(pages),
            pagesScanned = pageCount,
            totalPages = totalPages,
        )
    }

    companion object {
        const val MAX_PDF_PAGES = 3
        private const val TAG = "ReceiptOcrProcessor"
    }
}
