package io.github.jiro.expensetracker.data.repository

import java.io.File

/**
 * Pure path helpers for member-card image files. No Android types,
 * JVM-testable. The [MemberCardRepository] delegates to this object for
 * the path/exists/delete logic so the security-relevant
 * "don't delete outside the cards dir" guard has a unit test.
 *
 * Mirrors [ReceiptPaths].
 */
object CardPaths {
    fun absolutePath(cardsDir: File, relativePath: String): File =
        File(cardsDir, relativePath)

    fun exists(cardsDir: File, relativePath: String): Boolean =
        relativePath.isNotBlank() && absolutePath(cardsDir, relativePath).isFile

    /**
     * Delete the file at [relativePath] under [cardsDir]. Refuses to
     * touch anything outside the directory (defense in depth).
     * Returns true if a file was deleted, false otherwise.
     */
    fun delete(cardsDir: File, relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        val f = absolutePath(cardsDir, relativePath)
        if (!f.exists()) return false
        val canonicalCards = cardsDir.canonicalPath
        if (f.canonicalPath.startsWith(canonicalCards + File.separator) ||
            f.canonicalPath == canonicalCards
        ) {
            return f.delete()
        }
        return false
    }
}
