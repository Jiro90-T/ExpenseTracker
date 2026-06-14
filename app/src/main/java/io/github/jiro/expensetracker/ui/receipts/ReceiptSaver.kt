package io.github.jiro.expensetracker.ui.receipts

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.github.jiro.expensetracker.data.local.ImageProcessor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The collection URI strategy for a MediaStore insert. Pure enum so the
 * choice can be unit-tested without an Android Context.
 */
enum class ContentUri { ExternalPrimary, ExternalLegacy }

/**
 * A pure-JVM description of a MediaStore insert. Converted to a real
 * [ContentValues] on the device by [ReceiptSaver.saveToPhotos].
 */
data class ContentValuesRecipe(
    val collection: ContentUri,
    val isPending: Boolean,
    val mimeType: String,
    val displayName: String,
)

/**
 * Pure: picks the right MediaStore collection URI and the right ContentValues
 * flags for a given SDK + MIME type + display name. JVM-testable (no Android
 * imports — uses the [ContentUri] enum).
 *
 * - Android 10+ (API 29+): scoped storage. Uses [ContentUri.ExternalPrimary]
 *   and IS_PENDING=1, which is later cleared after the bitmap is written.
 * - Android 9 and below: legacy [ContentUri.ExternalLegacy], no IS_PENDING.
 */
internal fun buildContentValues(
    sdkInt: Int,
    mimeType: String,
    displayName: String,
): ContentValuesRecipe = if (sdkInt >= 29) {
    ContentValuesRecipe(
        collection = ContentUri.ExternalPrimary,
        isPending = true,
        mimeType = mimeType,
        displayName = displayName,
    )
} else {
    ContentValuesRecipe(
        collection = ContentUri.ExternalLegacy,
        isPending = false,
        mimeType = mimeType,
        displayName = displayName,
    )
}

/**
 * Saves a receipt image to the device's photo library. Android-bound — not
 * unit-testable at the JVM level (requires Context + ContentResolver). The
 * pure strategy selection lives in [buildContentValues] (tested) and is
 * consumed here.
 */
class ReceiptSaver(private val context: Context) {

    /**
     * Save the bitmap at [sourceFile] to the device's photo library. Returns
     * the inserted content URI on success, or null on any failure (with
     * [Log.w] of the exception for debugging).
     */
    suspend fun saveToPhotos(sourceFile: File, displayName: String): Uri? = withContext(Dispatchers.IO) {
        val recipe = buildContentValues(
            sdkInt = Build.VERSION.SDK_INT,
            mimeType = "image/jpeg",
            displayName = displayName,
        )

        val collection: Uri = when (recipe.collection) {
            ContentUri.ExternalPrimary ->
                if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ContentUri.ExternalLegacy -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, recipe.displayName)
            put(MediaStore.Images.Media.MIME_TYPE, recipe.mimeType)
            if (recipe.isPending) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = try {
            resolver.insert(collection, values)
        } catch (t: Throwable) {
            android.util.Log.w("ReceiptSaver", "insert failed: ${t.message}")
            return@withContext null
        } ?: return@withContext null

        try {
            // Decode the source file and write a JPEG to the inserted URI.
            val bitmap = ImageProcessor.decodeSampledBitmap(sourceFile, maxEdge = 4096)
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            } ?: return@withContext null
            bitmap.recycle()
        } catch (t: Throwable) {
            android.util.Log.w("ReceiptSaver", "write failed: ${t.message}")
            try { resolver.delete(uri, null, null) } catch (_: Throwable) { /* best effort */ }
            return@withContext null
        }

        if (recipe.isPending) {
            try {
                val finalize = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, finalize, null, null)
            } catch (t: Throwable) {
                android.util.Log.w("ReceiptSaver", "finalize failed: ${t.message}")
            }
        }

        uri
    }
}
