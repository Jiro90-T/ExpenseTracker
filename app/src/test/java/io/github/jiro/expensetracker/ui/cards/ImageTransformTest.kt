package io.github.jiro.expensetracker.ui.cards

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTransformTest {

    @Test
    fun applyZoomAround_inverseReturnsToIdentity() {
        val t = ImageTransform(scale = 1f, offsetX = 50f, offsetY = -30f)
        val zoomed = applyZoomAround(
            centroid = Offset(200f, 300f),
            zoomFactor = 2.5f,
            transform = t,
            boxSize = IntSize(1080, 1920),
            sourceBitmapSize = IntSize(800, 600),
        )
        val restored = applyZoomAround(
            centroid = Offset(200f, 300f),
            zoomFactor = 1f / 2.5f,
            transform = zoomed,
            boxSize = IntSize(1080, 1920),
            sourceBitmapSize = IntSize(800, 600),
        )
        assertEquals(t.scale, restored.scale, 0.0001f)
        assertEquals(t.offsetX, restored.offsetX, 0.0001f)
        assertEquals(t.offsetY, restored.offsetY, 0.0001f)
    }

    @Test
    fun applyZoomAround_focalPointMapsToSameContentPixel() {
        val box = IntSize(1080, 1920)
        val src = IntSize(800, 600)
        val centroid = Offset(700f, 1200f)
        val initial = ImageTransform(scale = 1f, offsetX = 0f, offsetY = 0f)
        val zoomed = applyZoomAround(centroid, zoomFactor = 2f, initial, box, src)

        val cropBefore = computeBitmapCropRect(
            boxSize = box,
            cropRectInScreen = androidx.compose.ui.geometry.Rect(
                left = centroid.x - 1f, top = centroid.y - 1f,
                right = centroid.x + 1f, bottom = centroid.y + 1f,
            ),
            layout = CropLayout(displayedLeft = (box.width - src.width) / 2f, displayedTop = (box.height - src.height) / 2f, scale = 1f),
            sourceBitmapSize = src,
            imageTransform = initial,
        )
        val cropAfter = computeBitmapCropRect(
            boxSize = box,
            cropRectInScreen = androidx.compose.ui.geometry.Rect(
                left = centroid.x - 1f, top = centroid.y - 1f,
                right = centroid.x + 1f, bottom = centroid.y + 1f,
            ),
            layout = CropLayout(displayedLeft = (box.width - src.width) / 2f, displayedTop = (box.height - src.height) / 2f, scale = 1f),
            sourceBitmapSize = src,
            imageTransform = zoomed,
        )
        assertEquals(cropBefore.x, cropAfter.x)
        assertEquals(cropBefore.y, cropAfter.y)
    }

    @Test
    fun applyZoomAround_clampsToMin() {
        val initial = ImageTransform(scale = 1f, offsetX = 0f, offsetY = 0f)
        val out = applyZoomAround(
            centroid = Offset(100f, 100f),
            zoomFactor = 0.1f,
            transform = initial,
            boxSize = IntSize(1080, 1920),
            sourceBitmapSize = IntSize(800, 600),
        )
        assertEquals(MIN_SCALE, out.scale, 0.0001f)
    }

    @Test
    fun applyZoomAround_clampsToMax() {
        val initial = ImageTransform(scale = 1f, offsetX = 0f, offsetY = 0f)
        val out = applyZoomAround(
            centroid = Offset(100f, 100f),
            zoomFactor = 10f,
            transform = initial,
            boxSize = IntSize(1080, 1920),
            sourceBitmapSize = IntSize(800, 600),
        )
        assertEquals(MAX_SCALE, out.scale, 0.0001f)
    }

    @Test
    fun applyPan_atScaleOne_isNoop() {
        val t = ImageTransform(scale = 1f, offsetX = 50f, offsetY = -30f)
        val out = applyPan(Offset(100f, 200f), t, IntSize(1080, 1920))
        assertEquals(t, out)
    }

    @Test
    fun applyPan_atScaleAboveOne_offsetsByDelta() {
        val t = ImageTransform(scale = 2f, offsetX = 50f, offsetY = -30f)
        val out = applyPan(Offset(100f, 200f), t, IntSize(1080, 1920))
        assertEquals(150f, out.offsetX, 0.0001f)
        assertEquals(170f, out.offsetY, 0.0001f)
        assertEquals(2f, out.scale, 0.0001f)
    }

    @Test
    fun clampTransform_atScaleOne_isNoop() {
        val t = ImageTransform(scale = 1f, offsetX = 9999f, offsetY = -9999f)
        val out = clampTransform(
            transform = t,
            boxSize = IntSize(1080, 1920),
            sourceBitmapSize = IntSize(1080, 1920),
            cropRectInScreen = androidx.compose.ui.geometry.Rect(0f, 0f, 1080f, 1920f),
        )
        assertEquals(t, out)
    }

    @Test
    fun clampTransform_keepsDrawnRectCoveringCropRect() {
        val box = IntSize(1080, 1920)
        val src = IntSize(800, 600)
        val cropRect = androidx.compose.ui.geometry.Rect(200f, 200f, 880f, 1720f)
        val t = ImageTransform(scale = 3f, offsetX = 10_000f, offsetY = 0f)
        val out = clampTransform(t, boxSize = box, sourceBitmapSize = src, cropRectInScreen = cropRect)

        val drawnCenterX = box.width / 2f + out.offsetX
        val drawnW = src.width * out.scale
        val drawnLeft = drawnCenterX - drawnW / 2f
        val drawnRight = drawnLeft + drawnW
        assert(drawnLeft <= cropRect.left) {
            "drawnLeft=$drawnLeft must be <= cropRect.left=${cropRect.left}"
        }
        assert(drawnRight >= cropRect.right) {
            "drawnRight=$drawnRight must be >= cropRect.right=${cropRect.right}"
        }
    }
}