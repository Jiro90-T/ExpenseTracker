package io.github.jiro.expensetracker.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReceiptPathsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun receiptsDir(): File = File(tempFolder.root, "receipts").apply { mkdirs() }

    @Test
    fun absolutePath_relative_returnsFileInReceiptsDir() {
        val dir = receiptsDir()
        val f = ReceiptPaths.absolutePath(dir, "abc.jpg")
        assertEquals(File(dir, "abc.jpg"), f)
    }

    @Test
    fun exists_realFile_returnsTrue() {
        val dir = receiptsDir()
        val name = "real.jpg"
        File(dir, name).writeText("x")
        assertTrue(ReceiptPaths.exists(dir, name))
    }

    @Test
    fun exists_missingFile_returnsFalse() {
        val dir = receiptsDir()
        assertFalse(ReceiptPaths.exists(dir, "does-not-exist.jpg"))
    }

    @Test
    fun delete_realFile_deletes() {
        val dir = receiptsDir()
        val name = "to-delete.jpg"
        val f = File(dir, name).apply { writeText("x") }
        assertTrue(f.exists())
        assertTrue(ReceiptPaths.delete(dir, name))
        assertFalse(f.exists())
    }

    @Test
    fun delete_blank_returnsFalse() {
        val dir = receiptsDir()
        assertFalse(ReceiptPaths.delete(dir, ""))
        assertFalse(ReceiptPaths.delete(dir, "   "))
    }

    @Test
    fun delete_outsideReceiptsDir_refused() {
        val dir = receiptsDir()
        val outside = File(tempFolder.root, "outside.jpg").apply { writeText("x") }
        assertTrue(outside.exists())
        // The path "../outside.jpg" canonicalizes to outside the receipts dir.
        assertFalse(ReceiptPaths.delete(dir, "../outside.jpg"))
        assertTrue("file outside receipts/ must not be deleted", outside.exists())
    }
}
