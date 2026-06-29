package io.github.jiro.expensetracker.data.repository

import android.net.Uri
import io.github.jiro.expensetracker.data.local.MemberCardEntity
import io.github.jiro.expensetracker.ui.cards.MemberCardForm
import kotlinx.coroutines.flow.Flow

/**
 * Owns persistence for member cards: the DB row + the image file under
 * `<filesDir>/cards/<UUID>.jpg`.
 *
 * The Edit screen calls [add] with a freshly-picked URI (camera or
 * gallery); the URI is copied, downscaled, and stored. For edits, [update]
 * is called with the existing row's relative `imagePath` already on the
 * form, plus an optional `newImageUri` to replace the photo.
 */
interface MemberCardRepository {
    fun observeAll(): Flow<List<MemberCardEntity>>
    fun search(query: String): Flow<List<MemberCardEntity>>
    suspend fun getById(id: Long): MemberCardEntity?

    /** Copy [sourceUri] into internal storage and create a new card row. */
    suspend fun add(sourceUri: Uri, form: MemberCardForm): Long

    /**
     * Update an existing card. If [newImageUri] is non-null, copy the new
     * image into internal storage, replace the row's `imagePath`, and
     * delete the old image file.
     */
    suspend fun update(id: Long, form: MemberCardForm, newImageUri: Uri? = null)

    /** Delete the row + its image file. No-op if the row is already gone. */
    suspend fun delete(id: Long)

    /** Absolute path on disk for an image, or null if missing. */
    fun absolutePath(relativePath: String): java.io.File?
}