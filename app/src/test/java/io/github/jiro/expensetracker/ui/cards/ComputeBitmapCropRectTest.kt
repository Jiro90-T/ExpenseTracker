package io.github.jiro.expensetracker.ui.cards

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeBitmapCropRectTest {

    private fun layoutFor(box: IntSize, src: IntSize, scale: Float = 1f): CropLayout = CropLayout(
        displayedLeft = (box.width - src.width * scale) / 2f,
        displayedTop = (box.height - src.height * scale) / 2f,
        scale = scale,
    )

    @Test
    fun identityTransform_matchesFitScaledOutput() {
        val src = IntSize(900, 1700)
        val box = src
        val cropRect = Rect(left = 100f, top = 100f, right = 900f, bottom = 1700f)
        val out = computeBitmapCropRect(
            boxSize = box,
            cropRectInScreen = cropRect,
            layout = layoutFor(box, src),
            sourceBitmapSize = src,
            imageTransform = ImageTransform(),
        )
        assertEquals(100, out.x)
        assertEquals(100, out.y)
        assertEquals(800, out.width)
        assertEquals(1600, out.height)
    }

    @Test
    fun zoomIn_returnsSmallSourceRect() {
        val box = IntSize(1080, 1920)
        val src = IntSize(800, 600)
        val cropRect = Rect(left = 540f, top = 960f, right = 740f, bottom = 1160f)
        val t = ImageTransform(scale = 3f, offsetX = 0f, offsetY = 0f)
        val out = computeBitmapCropRect(
            boxSize = box,
            cropRectInScreen = cropRect,
            layout = layoutFor(box, src),
            sourceBitmapSize = src,
            imageTransform = t,
        )
        val screenW = 200
        val screenH = 200
        assertTrue("expected width < $screenW, got ${out.width}", out.width < screenW)
        assertTrue("expected height < $screenH, got ${out.height}", out.height < screenH)
        assertTrue(out.width >= 1)
        assertTrue(out.height >= 1)
    }

    @Test
    fun panAndZoom_returnsTranslatedSourceRect() {
        val box = IntSize(1080, 1920)
        val src = IntSize(800, 600)
        val cropRect = Rect(left = 540f, top = 960f, right = 740f, bottom = 1160f)
        val t = ImageTransform(scale = 2f, offsetX = 200f, offsetY = -100f)
        val out = computeBitmapCropRect(
            boxSize = box,
            cropRectInScreen = cropRect,
            layout = layoutFor(box, src),
            sourceBitmapSize = src,
            imageTransform = t,
        )
        assertTrue(out.x >= 0)
        assertTrue(out.y >= 0)
        assertTrue(out.x + out.width <= src.width)
        assertTrue(out.y + out.height <= src.height)
    }

    @Test
    fun scaleOneWithOffset_translatesSourceRect() {
        val box = IntSize(1080, 1920)
        val src = IntSize(800, 600)
        val cropRect = Rect(560f, 970f, 660f, 1070f)
        val t = ImageTransform(scale = 1f, offsetX = 20f, offsetY = 10f)
        val out = computeBitmapCropRect(
            boxSize = box,
            cropRectInScreen = cropRect,
            layout = layoutFor(box, src),
            sourceBitmapSize = src,
            imageTransform = t,
        )
        assertEquals(400, out.x)
        assertEquals(300, out.y)
        assertEquals(100, out.width)
        assertEquals(100, out.height)
    }

    @Test
    fun clampsToSourceBounds_evenIfTransformWild() {
        val box = IntSize(1080, 1920)
        val src = IntSize(800, 600)
        val cropRect = Rect(left = 0f, top = 0f, right = 1f, bottom = 1f)
        val t = ImageTransform(scale = 3f, offsetX = 999_999f, offsetY = -999_999f)
        val out = computeBitmapCropRect(
            boxSize = box,
            cropRectInScreen = cropRect,
            layout = layoutFor(box, src),
            sourceBitmapSize = src,
            imageTransform = t,
        )
        assertTrue(out.x >= 0)
        assertTrue(out.y >= 0)
        assertTrue(out.x + out.width <= src.width)
        assertTrue(out.y + out.height <= src.height)
    }
}
