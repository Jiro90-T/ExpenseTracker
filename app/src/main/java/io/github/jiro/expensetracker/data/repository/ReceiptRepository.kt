package io.github.jiro.expensetracker.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.data.local.ImageProcessor
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure path helpers for receipt files. No Android types, JVM-testable. The
 * [ReceiptRepository] delegates to this object for the path/exists/delete
 * logic so the security-relevant "don't delete outside the receipts dir"
 * guard has a unit test.
 */
object ReceiptPaths {
    fun absolutePath(receiptsDir: File, relativePath: String): File = File(receiptsDir, relativePath)

    fun exists(receiptsDir: File, relativePath: String): Boolean =
        relativePath.isNotBlank() && absolutePath(receiptsDir, relativePath).isFile

    /**
     * Delete the file at [relativePath] under [receiptsDir]. Refuses to
     * touch anything outside the directory (defense in depth).
     * Returns true if a file was deleted, false otherwise.
     */
    fun delete(receiptsDir: File, relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        val f = absolutePath(receiptsDir, relativePath)
        if (!f.exists()) return false
        val canonicalReceipts = receiptsDir.canonicalPath
        if (f.canonicalPath.startsWith(canonicalReceipts + File.separator) || f.canonicalPath == canonicalReceipts) {
            return f.delete()
        }
        return false
    }
}

/**
 * Owns the I/O for receipt files. The on-disk layout is
 * `<filesDir>/receipts/<relativePath>` where [relativePath] is a UUID-based
 * filename with a meaningful extension (`.jpg` for images, `.pdf` for PDFs).
 *
 * Image receipts are downscaled to a 2048px long edge and re-encoded as JPEG.
 * PDF receipts are stored as-is.
 *
 * **Why `Context` is a method parameter, not a field:** the I/O methods that
 * need a `Context` (currently [saveFromUri]) take it as a parameter. This
 * keeps the path/exists/canonical-guard logic (delegated to [ReceiptPaths])
 * free of Android dependencies and JVM-testable.
 */
@Singleton
open class ReceiptRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Lazily computed so the constructor doesn't touch Android IO. JVM
    // tests can construct ReceiptRepository with a stub Context (which
    // would throw on `context.filesDir`) without triggering the directory
    // lookup — and the test fake overrides `saveFromUri` / `absolutePath`
    // so it never reads this property.
    open val receiptsDir: File by lazy {
        File(context.filesDir, "receipts").apply { mkdirs() }
    }

    open fun absolutePath(relativePath: String): File = ReceiptPaths.absolutePath(receiptsDir, relativePath)
    fun exists(relativePath: String): Boolean = ReceiptPaths.exists(receiptsDir, relativePath)
    suspend fun delete(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        ReceiptPaths.delete(receiptsDir, relativePath)
    }

    /**
     * Copy a receipt from a content [Uri] into our internal storage. Returns
     * the relative path the caller should persist on the [TransactionEntity].
     */
    open suspend fun saveFromUri(context: Context, src: Uri): String = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(src) ?: ""
        when {
            mime == "application/pdf" -> copyBytesAsIs(src, context, ext = "pdf")
            mime.startsWith("image/") -> copyDownscaleAndReencode(src, context, sourceMime = mime)
            else -> error("Unsupported receipt MIME type: $mime")
        }
    }

    fun openInputStream(relativePath: String): InputStream? {
        val f = absolutePath(relativePath)
        if (!f.isFile) return null
        return f.inputStream()
    }

    /** Number of pages in the PDF at [relativePath], or 0 if the file is missing/corrupt. */
    @SuppressLint("RestrictedApi")
    fun openPdfPageCount(relativePath: String): Int {
        val f = absolutePath(relativePath)
        if (!f.isFile) return 0
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { it.pageCount }
        }
    }

    /** Render the [pageIndex]-th page of the PDF at [relativePath] as a Bitmap. */
    @SuppressLint("RestrictedApi")
    fun renderPdfPage(relativePath: String, pageIndex: Int): Bitmap {
        val f = absolutePath(relativePath)
        if (!f.isFile) error("Receipt file missing: $relativePath")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                    error("Page $pageIndex out of bounds (count = ${renderer.pageCount})")
                }
                renderer.openPage(pageIndex).use { page ->
                    val width = page.width
                    val height = page.height
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }

    // ---- private helpers ----

    private fun copyBytesAsIs(src: Uri, context: Context, ext: String): String {
        val name = "${UUID.randomUUID()}.$ext"
        val dest = File(receiptsDir, name)
        context.contentResolver.openInputStream(src).use { input ->
            requireNotNull(input) { "Could not open input stream for $src" }
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        return name
    }

    private fun copyDownscaleAndReencode(src: Uri, context: Context, sourceMime: String): String {
        val name = "${UUID.randomUUID()}.jpg"
        val dest = File(receiptsDir, name)
        val tempRaw = File.createTempFile("receipt-raw-", ".bin", receiptsDir)
        try {
            context.contentResolver.openInputStream(src).use { input ->
                requireNotNull(input) { "Could not open input stream for $src" }
                FileOutputStream(tempRaw).use { output -> input.copyTo(output) }
            }
            val decoded = ImageProcessor.decodeSampledBitmap(tempRaw, maxEdge = 4096)
            // downscaleToMaxEdge always returns a new bitmap (it copies even when the source
            // is already small), so decoded and downscaled are always distinct.
            val downscaled = ImageProcessor.downscaleToMaxEdge(decoded, maxEdge = 2048)
            FileOutputStream(dest).use { out ->
                downscaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            decoded.recycle()
            downscaled.recycle()
        } finally {
            tempRaw.delete()
        }
        return name
    }
}
