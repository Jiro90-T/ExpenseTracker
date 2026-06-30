package io.github.jiro.expensetracker.widget

import io.github.jiro.expensetracker.data.local.MemberCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WidgetCardProjectionTest {

    private val now = System.currentTimeMillis()
    private val yesterday = LocalDate.now().minusDays(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val tomorrow = LocalDate.now().plusDays(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun card(
        id: Long = 1,
        name: String = "Costco",
        imagePath: String? = "uuid.jpg",
        expiresAtEpochMillis: Long? = null,
        notes: String? = null,
    ) = MemberCardEntity(
        id = id,
        name = name,
        imagePath = imagePath.orEmpty(),
        memberIdText = null,
        colorHex = null,
        icon = null,
        expiresAtEpochMillis = expiresAtEpochMillis,
        notes = notes,
        createdAtEpochMillis = now,
        sortOrder = 0,
    )

    @Test fun projection_nullImagePathMeansMissing() {
        val result = WidgetCard.from(card(imagePath = null), imageExists = true)
        assertTrue(result.imageMissing)
    }

    @Test fun projection_fileMissingOnDiskMeansMissing() {
        val result = WidgetCard.from(card(imagePath = "ghost.jpg"), imageExists = false)
        assertTrue(result.imageMissing)
    }

    @Test fun projection_cardOnDiskMeansImagePresent() {
        val result = WidgetCard.from(card(imagePath = "ok.jpg"), imageExists = true)
        assertFalse(result.imageMissing)
        assertEquals("ok.jpg", result.imagePath)
    }

    @Test fun projection_yesterdayIsExpired() {
        val result = WidgetCard.from(card(expiresAtEpochMillis = yesterday), true)
        assertTrue(result.isExpired)
    }

    @Test fun projection_tomorrowIsNotExpired() {
        val result = WidgetCard.from(card(expiresAtEpochMillis = tomorrow), true)
        assertFalse(result.isExpired)
    }

    @Test fun projection_noExpiryIsNotExpired() {
        val result = WidgetCard.from(card(expiresAtEpochMillis = null), true)
        assertFalse(result.isExpired)
    }

    @Test fun projection_dropsNotes() {
        val result = WidgetCard.from(card(notes = "secret"), true)
        assertNull(result.notes)
    }
}
