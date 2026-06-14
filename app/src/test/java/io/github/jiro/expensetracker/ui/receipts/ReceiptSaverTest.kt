package io.github.jiro.expensetracker.ui.receipts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptSaverTest {

    @Test
    fun buildContentValues_sdk29Plus_usesExternalPrimaryAndIsPending() {
        val r29 = buildContentValues(sdkInt = 29, mimeType = "image/jpeg", displayName = "receipt.jpg")
        assertEquals(ContentUri.ExternalPrimary, r29.collection)
        assertTrue(r29.isPending)
        assertEquals("image/jpeg", r29.mimeType)
        assertEquals("receipt.jpg", r29.displayName)

        val r33 = buildContentValues(sdkInt = 33, mimeType = "image/jpeg", displayName = "r.jpg")
        assertEquals(ContentUri.ExternalPrimary, r33.collection)
        assertTrue(r33.isPending)
    }

    @Test
    fun buildContentValues_sdk28_usesExternalLegacyNoPending() {
        val r = buildContentValues(sdkInt = 28, mimeType = "image/jpeg", displayName = "r.jpg")
        assertEquals(ContentUri.ExternalLegacy, r.collection)
        assertFalse(r.isPending)
    }

    @Test
    fun buildContentValues_sdk24_usesExternalLegacyNoPending() {
        val r = buildContentValues(sdkInt = 24, mimeType = "image/jpeg", displayName = "r.jpg")
        assertEquals(ContentUri.ExternalLegacy, r.collection)
        assertFalse(r.isPending)
    }

    @Test
    fun buildContentValues_propagatesMimeType() {
        assertEquals("image/png", buildContentValues(30, "image/png", "x.png").mimeType)
        assertEquals("image/jpeg", buildContentValues(30, "image/jpeg", "x.jpg").mimeType)
    }

    @Test
    fun buildContentValues_propagatesDisplayName() {
        assertEquals("trip-2026-receipt.jpg", buildContentValues(30, "image/jpeg", "trip-2026-receipt.jpg").displayName)
    }
}
