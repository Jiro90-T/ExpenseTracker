package io.github.jiro.expensetracker.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CardPathsTest {

    @get:Rule val tempFolder = TemporaryFolder()
    private fun cardsDir(): File = File(tempFolder.root, "cards").apply { mkdirs() }

    @Test fun absolutePath_relative_returnsFileInCardsDir() {
        val dir = cardsDir()
        val f = CardPaths.absolutePath(dir, "abc.jpg")
        assertEquals(File(dir, "abc.jpg"), f)
    }

    @Test fun exists_emptyOrMissing_returnsFalse() {
        val dir = cardsDir()
        assertFalse(CardPaths.exists(dir, ""))
        assertFalse(CardPaths.exists(dir, "  "))
        assertFalse(CardPaths.exists(dir, "missing.jpg"))
    }

    @Test fun exists_realFile_returnsTrue() {
        val dir = cardsDir()
        File(dir, "present.jpg").writeText("x")
        assertTrue(CardPaths.exists(dir, "present.jpg"))
    }

    @Test fun delete_realFile_deletes() {
        val dir = cardsDir()
        val name = "to-delete.jpg"
        File(dir, name).writeText("x")
        assertTrue(CardPaths.delete(dir, name))
        assertFalse(File(dir, name).exists())
    }

    @Test fun delete_outsideCardsDir_refused() {
        val dir = cardsDir()
        val outside = File(tempFolder.root, "outside.jpg").apply { writeText("x") }
        assertFalse(CardPaths.delete(dir, "../outside.jpg"))
        assertTrue(outside.exists())
    }

    @Test fun delete_blankOrMissing_returnsFalse() {
        val dir = cardsDir()
        assertFalse(CardPaths.delete(dir, ""))
        assertFalse(CardPaths.delete(dir, "missing.jpg"))
    }
}
