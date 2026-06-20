package io.github.jiro.expensetracker.data.local

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.jiro.expensetracker.domain.receipt.OcrFields
import io.github.jiro.expensetracker.domain.receipt.ReceiptOcrParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Wraps ML Kit Text Recognition (Latin, on-device) and feeds the result
 * into [ReceiptOcrParser]. On-device, no API key, ~1s for a typical receipt.
 */
@Singleton
open class ReceiptOcrProcessor @Inject constructor() {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Run OCR on [bitmap]. Returns parsed fields; any field may be null if
     * the parser couldn't find a confident match. Throws on unrecoverable
     * ML Kit failure (caller catches and shows a snackbar).
     */
    open suspend fun extract(bitmap: Bitmap): OcrFields = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                cont.resume(ReceiptOcrParser.parse(result.text))
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.cancel(e)
            }
    }
}
