package io.github.jiro.expensetracker.domain.receipt

import android.graphics.Bitmap
import io.github.jiro.expensetracker.data.local.ReceiptOcrProcessor
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptReviewPipelineTest {

    /**
     * The pipeline checks the file first. If it doesn't exist (or isn't a
     * regular file), we return all-null without trying to decode anything.
     * This branch doesn't touch BitmapFactory, so it works in pure JVM tests.
     */
    @Test
    fun review_missingFile_returnsEmptyFields() = runTest {
        val missing = File("does-not-exist-${System.nanoTime()}.jpg")
        assertTrue(!missing.isFile)

        val result = ReceiptReviewPipeline.review(
            file = missing,
            ocrProcessor = FakeOcrProcessor(
                OcrFields(amountMinor = 999L, occurredAtEpochMillis = 999L, merchant = "x"),
            ),
        )
        assertEquals(OcrFields(null, null, null), result)
    }

    /**
     * The pipeline wraps the bitmap decode in try/catch. If decode throws
     * (BitmapFactory.decodeFile is stubbed in JVM unit tests — it throws
     * RuntimeException), we return all-null without invoking the OCR
     * processor. This is the "graceful degradation" contract.
     */
    @Test
    fun review_bitmapDecodeThrows_returnsEmptyFields() = runTest {
        val tmp = File.createTempFile("receipt", ".jpg").apply { writeText("fake") }
        try {
            val result = ReceiptReviewPipeline.review(
                file = tmp,
                ocrProcessor = FakeOcrProcessor(
                    OcrFields(amountMinor = 999L, occurredAtEpochMillis = 999L, merchant = "x"),
                ),
            )
            assertEquals(OcrFields(null, null, null), result)
        } finally {
            tmp.delete()
        }
    }
}

private class FakeOcrProcessor(private val result: OcrFields) : ReceiptOcrProcessor() {
    override suspend fun extract(bitmap: Bitmap): OcrFields = result
}
