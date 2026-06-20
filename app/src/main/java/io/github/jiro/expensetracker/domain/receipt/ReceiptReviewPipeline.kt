package io.github.jiro.expensetracker.domain.receipt

import android.graphics.Bitmap
import io.github.jiro.expensetracker.data.local.ImageProcessor
import io.github.jiro.expensetracker.data.local.ReceiptOcrProcessor
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure OCR pipeline: load a saved receipt file, decode a sampled bitmap, run
 * on-device OCR via ML Kit, then recycle the bitmap. Returns [OcrFields] —
 * never throws. On any failure (missing file, decode OOM, ML Kit error), the
 * returned fields are all-null, and the caller can decide whether to surface
 * a snackbar or just continue with empty fields.
 *
 * The pipeline is implemented as a stateless `object` — pure function on
 * (file, ocr-processor, dispatcher). The dispatcher is a parameter (default
 * [Dispatchers.IO]) so JVM unit tests can pass a `TestDispatcher` and avoid
 * dispatching onto the real IO thread pool (which would escape the test
 * scheduler's `advanceUntilIdle`).
 */
object ReceiptReviewPipeline {

    private const val MAX_EDGE = 2048

    suspend fun review(
        file: File,
        ocrProcessor: ReceiptOcrProcessor,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): OcrFields {
        if (!file.isFile) return OcrFields(null, null, null)
        val bitmap: Bitmap = try {
            withContext(ioDispatcher) { ImageProcessor.decodeSampledBitmap(file, maxEdge = MAX_EDGE) }
        } catch (e: Exception) {
            return OcrFields(null, null, null)
        }
        return try {
            ocrProcessor.extract(bitmap)
        } catch (e: Exception) {
            OcrFields(null, null, null)
        } finally {
            bitmap.recycle()
        }
    }
}
