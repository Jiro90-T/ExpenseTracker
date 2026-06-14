package io.github.jiro.expensetracker.ui.receipts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of a Save to Photos attempt — what the screen turns into a snackbar. */
sealed interface SaveResult {
    data class Success(val uri: Uri) : SaveResult
    data class Failure(val message: String) : SaveResult
}

@HiltViewModel
class ReceiptViewerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val receiptRepository: ReceiptRepository,
) : ViewModel() {

    private val fileProviderSuffix = ".fileprovider"

    /**
     * Build a share intent for the receipt file. The caller wraps this in
     * [Intent.createChooser] and starts it. Returns null if the file doesn't exist.
     */
    suspend fun buildShareIntent(receiptPath: String): Intent? = withContext(Dispatchers.IO) {
        if (!receiptRepository.exists(receiptPath)) return@withContext null
        val file = receiptRepository.absolutePath(receiptPath)
        val authority = "${context.packageName}$fileProviderSuffix"
        val uri = try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (t: Throwable) {
            android.util.Log.w("ReceiptVM", "share uri failed: ${t.message}")
            return@withContext null
        }
        Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Save the receipt file to the device's photo library. Returns [SaveResult.Success]
     * with the inserted URI on success, or [SaveResult.Failure] with a message.
     */
    suspend fun saveToPhotos(receiptPath: String, displayName: String): SaveResult = withContext(Dispatchers.IO) {
        if (!receiptRepository.exists(receiptPath)) {
            return@withContext SaveResult.Failure("file not found")
        }
        val file = receiptRepository.absolutePath(receiptPath)
        val uri = try {
            ReceiptSaver(context).saveToPhotos(file, displayName)
        } catch (t: Throwable) {
            android.util.Log.w("ReceiptVM", "save failed: ${t.message}")
            null
        }
        if (uri != null) SaveResult.Success(uri) else SaveResult.Failure("could not save")
    }
}
