package io.github.jiro.expensetracker.data.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Pure bitmap helpers used by [ReceiptRepository] when storing image receipts.
 * The math is factored out into a JVM-testable helper; the Android-specific
 * `Bitmap.createScaledBitmap` and `BitmapFactory.decodeFile` calls are
 * exercised on device in the manual smoke test (Phase 2.4 Task 12).
 */
object ImageProcessor {

    /**
     * Compute the target (width, height) of a downscale. Returns the
     * original dimensions if already at or below [maxEdge], otherwise
     * scales the long edge to exactly [maxEdge] and the short edge
     * proportionally (truncated to int, minimum 1).
     *
     * Pure: no Android types, fully JVM-testable.
     */
    fun computeDownscaleDims(srcW: Int, srcH: Int, maxEdge: Int): Pair<Int, Int> {
        require(srcW > 0 && srcH > 0) { "src dimensions must be positive (got $srcW x $srcH)" }
        require(maxEdge > 0) { "maxEdge must be positive (got $maxEdge)" }
        val longEdge = maxOf(srcW, srcH)
        if (longEdge <= maxEdge) return srcW to srcH
        val scale = maxEdge.toFloat() / longEdge.toFloat()
        val newW = (srcW * scale).toInt().coerceAtLeast(1)
        val newH = (srcH * scale).toInt().coerceAtLeast(1)
        return newW to newH
    }

    /**
     * Returns a bitmap whose largest edge is at most [maxEdge] pixels. If the
     * source is already smaller, returns a copy of the source. If the source
     * is larger, returns a new bitmap scaled proportionally with [Bitmap]'s
     * bilinear filter.
     */
    fun downscaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val (newW, newH) = computeDownscaleDims(src.width, src.height, maxEdge)
        if (newW == src.width && newH == src.height) {
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    /**
     * Decode a bitmap from disk using `inSampleSize` so we don't OOM on huge
     * images. Caller must close the resulting bitmap when done.
     *
     * Android-only — exercised on device, not in JVM unit tests.
     */
    fun decodeSampledBitmap(file: File, maxEdge: Int): Bitmap {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        require(srcW > 0 && srcH > 0) { "Could not decode bitmap bounds for ${file.absolutePath}" }
        val longEdge = maxOf(srcW, srcH)
        var sample = 1
        while (longEdge / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: error("BitmapFactory.decodeFile returned null for ${file.absolutePath}")
        return applyExifRotation(bmp, file)
    }

    private fun applyExifRotation(bmp: Bitmap, file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }
}
