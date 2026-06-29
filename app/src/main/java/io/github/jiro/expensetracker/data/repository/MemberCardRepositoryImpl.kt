package io.github.jiro.expensetracker.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.data.local.ImageProcessor
import io.github.jiro.expensetracker.data.local.MemberCardDao
import io.github.jiro.expensetracker.data.local.MemberCardEntity
import io.github.jiro.expensetracker.ui.cards.MemberCardForm
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Default [MemberCardRepository] impl. Image files live under
 * `<filesDir>/cards/` and are downscaled to a 1024px long edge,
 * re-encoded as JPEG quality 85.
 *
 * The image copy step ([saveFromUri]) is `open` so tests can stub it
 * without standing up a [android.content.ContentProvider] — mirrors
 * [ReceiptRepository.saveFromUri] and [AccountImportRepositoryImpl.readInput].
 */
@Singleton
open class MemberCardRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: MemberCardDao,
) : MemberCardRepository {

    open val cardsDir: File by lazy { File(context.filesDir, "cards").apply { mkdirs() } }

    override fun observeAll(): Flow<List<MemberCardEntity>> = dao.observeAll()

    override fun search(query: String): Flow<List<MemberCardEntity>> =
        dao.searchByName(query.trim())

    override suspend fun getById(id: Long): MemberCardEntity? = dao.findById(id)

    override suspend fun add(sourceUri: Uri, form: MemberCardForm): Long {
        val relativePath = saveFromUri(sourceUri)
        return dao.insert(
            MemberCardEntity(
                id = 0,
                name = form.name,
                imagePath = relativePath,
                memberIdText = form.memberIdText,
                colorHex = form.colorHex,
                icon = form.icon,
                expiresAtEpochMillis = form.expiresAtEpochMillis,
                notes = form.notes,
                createdAtEpochMillis = System.currentTimeMillis(),
                sortOrder = 0,
            )
        )
    }

    override suspend fun update(id: Long, form: MemberCardForm, newImageUri: Uri?): Unit = withContext(Dispatchers.IO) {
        val existing = dao.findById(id) ?: return@withContext
        var path = existing.imagePath
        if (newImageUri != null) {
            val newPath = saveFromUri(newImageUri)
            CardPaths.delete(cardsDir, path)
            path = newPath
        }
        dao.update(
            existing.copy(
                name = form.name,
                imagePath = path,
                memberIdText = form.memberIdText,
                colorHex = form.colorHex,
                icon = form.icon,
                expiresAtEpochMillis = form.expiresAtEpochMillis,
                notes = form.notes,
            )
        )
        Unit
    }

    override suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) {
        val existing = dao.findById(id)
        if (existing != null) {
            CardPaths.delete(cardsDir, existing.imagePath)
        }
        dao.deleteById(id)
        Unit
    }

    override fun absolutePath(relativePath: String): File? {
        val f = CardPaths.absolutePath(cardsDir, relativePath)
        return if (f.isFile) f else null
    }

    /**
     * Copy [src] into internal storage, downscale to a 1024px long edge,
     * re-encode as JPEG q=85. Returns the relative path (just the filename,
     * since callers will prepend `<filesDir>/cards/` via [absolutePath]).
     *
     * Override in tests to bypass Android IO.
     */
    open suspend fun saveFromUri(src: Uri): String = withContext(Dispatchers.IO) {
        val name = "${UUID.randomUUID()}.jpg"
        val dest = File(cardsDir, name)
        val tempRaw = File.createTempFile("card-raw-", ".bin", cardsDir)
        var decoded: Bitmap? = null
        var downscaled: Bitmap? = null
        try {
            context.contentResolver.openInputStream(src).use { input ->
                requireNotNull(input) { "Could not open input stream for $src" }
                FileOutputStream(tempRaw).use { output -> input.copyTo(output) }
            }
            decoded = ImageProcessor.decodeSampledBitmap(tempRaw, maxEdge = 1024)
            downscaled = ImageProcessor.downscaleToMaxEdge(decoded, maxEdge = 1024)
            FileOutputStream(dest).use { out ->
                downscaled!!.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        } finally {
            if (decoded?.isRecycled == false) decoded.recycle()
            if (downscaled?.isRecycled == false) downscaled.recycle()
            tempRaw.delete()
        }
        name
    }
}