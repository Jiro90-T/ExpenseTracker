package io.github.jiro.expensetracker.ui.cards

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CropMathTest {

    private fun layout(scale: Float, left: Float = 0f, top: Float = 0f) =
        CropLayout(displayedLeft = left, displayedTop = top, scale = scale)

    @Test
    fun computeBitmapCropRect_fullyInsideBitmap_returnsRequestedRect() {
        // Bitmap 200x200 displayed at scale 1.0, no offset.
        // Crop rect matches the image exactly.
        val l = layout(scale = 1f)
        val r = Rect(0f, 0f, 200f, 200f)
        val out = computeBitmapCropRect(r, l, 200, 200)
        assertEquals(BitmapCropRect(0, 0, 200, 200), out)
    }

    @Test
    fun computeBitmapCropRect_initial80PercentRect_mapsToCorrectBitmapRegion() {
        // Bitmap 1000x500 displayed in a 800x800 box, scale = 0.8 (long edge).
        // Displayed rect: 800x400, centered: left = 0, top = (800-400)/2 = 200.
        // 80% crop rect: w=640, h=320, left=80, top=240.
        // Bitmap coords: subtract offset (0, 200), divide by 0.8.
        //   imageX = (80 - 0) / 0.8 = 100
        //   imageY = (240 - 200) / 0.8 = 50
        //   imageW = 640 / 0.8 = 800
        //   imageH = 320 / 0.8 = 400
        // Bitmap is 1000x500, so this fits with margin.
        val l = layout(scale = 0.8f, left = 0f, top = 200f)
        val r = Rect(80f, 240f, 720f, 560f)
        val out = computeBitmapCropRect(r, l, 1000, 500)
        assertEquals(BitmapCropRect(100, 50, 800, 400), out)
    }

    @Test
    fun computeBitmapCropRect_rectExtendsPastRightEdge_shiftsLeftNotShrinks() {
        // Bitmap 100x100, scale 1, no offset.
        // Crop rect extends 30px past the right edge: width 50, left 80.
        // Old buggy code would return width=20 (clamped to source.width - x).
        // Correct behavior: shift left to (50, 50, 100, 100).
        val l = layout(scale = 1f)
        val r = Rect(80f, 0f, 130f, 50f)
        val out = computeBitmapCropRect(r, l, 100, 100)
        assertEquals(BitmapCropRect(50, 0, 50, 50), out)
    }

    @Test
    fun computeBitmapCropRect_rectExtendsPastBottomEdge_shiftsUpNotShrinks() {
        // Bitmap 100x100, scale 1, no offset.
        // Crop rect extends 30px past the bottom edge: height 50, top 80.
        // Correct behavior: shift up to (0, 50, 50, 100).
        val l = layout(scale = 1f)
        val r = Rect(0f, 80f, 50f, 130f)
        val out = computeBitmapCropRect(r, l, 100, 100)
        assertEquals(BitmapCropRect(0, 50, 50, 50), out)
    }

    @Test
    fun computeBitmapCropRect_rectLargerThanBitmap_anchorsAtOrigin() {
        // Bitmap 50x50, crop rect 100x100 starting at (-25, -25) (centered on
        // a larger box). The requested rect is bigger than the bitmap, so we
        // anchor at (0, 0) and shrink to the bitmap dimensions.
        val l = layout(scale = 1f)
        val r = Rect(-25f, -25f, 75f, 75f)
        val out = computeBitmapCropRect(r, l, 50, 50)
        assertEquals(BitmapCropRect(0, 0, 50, 50), out)
    }

    @Test
    fun computeBitmapCropRect_draggedToBottomRight_keepsRequestedSize() {
        // Bitmap 200x200 displayed at scale 1.0, no offset.
        // 80% crop rect (160x160) dragged all the way to the bottom-right
        // corner. Old code would shrink the crop because safeX = min(40, 199)
        // would then force safeW = min(160, 159) = 159. New code shifts the
        // rect left to keep width = 160.
        val l = layout(scale = 1f)
        val r = Rect(40f, 40f, 200f, 200f)
        val out = computeBitmapCropRect(r, l, 200, 200)
        assertEquals(BitmapCropRect(40, 40, 160, 160), out)
    }

    @Test
    fun computeBitmapCropRect_zeroScale_returnsNull() {
        // Degenerate layout — no fit math possible.
        val l = layout(scale = 0f)
        val r = Rect(0f, 0f, 100f, 100f)
        assertNull(computeBitmapCropRect(r, l, 100, 100))
    }

    @Test
    fun computeBitmapCropRect_zeroBitmapDimensions_returnsNull() {
        val l = layout(scale = 1f)
        val r = Rect(0f, 0f, 100f, 100f)
        assertNull(computeBitmapCropRect(r, l, 0, 100))
        assertNull(computeBitmapCropRect(r, l, 100, 0))
    }

    @Test
    fun computeBitmapCropRect_zeroSizedCropRect_returnsNull() {
        // Display rect collapsed to a line/point — degenerate, can't slice.
        val l = layout(scale = 1f)
        val zeroWidth = Rect(0f, 0f, 0f, 100f)
        assertNull(computeBitmapCropRect(zeroWidth, l, 100, 100))
        val zeroHeight = Rect(0f, 0f, 100f, 0f)
        assertNull(computeBitmapCropRect(zeroHeight, l, 100, 100))
    }

    @Test
    fun computeBitmapCropRect_centeredInBox_appliesOffset() {
        // Bitmap 100x100 in a 200x200 box. ContentScale.Fit → scale 1, image
        // sits at offset (50, 50). A crop rect at (60, 60, 80, 80) maps to
        // bitmap (10, 10, 20, 20).
        val l = layout(scale = 1f, left = 50f, top = 50f)
        val r = Rect(60f, 60f, 80f, 80f)
        val out = computeBitmapCropRect(r, l, 100, 100)
        assertEquals(BitmapCropRect(10, 10, 20, 20), out)
    }

    @Test
    fun computeBitmapCropRect_normalCase_producesNonNullResult() {
        // Sanity: typical user action — 80% crop on a portrait photo. Just
        // verify the result is non-null and has positive dimensions.
        val l = layout(scale = 0.5f, left = 50f, top = 0f)
        val r = Rect(90f, 100f, 490f, 700f)
        val out = computeBitmapCropRect(r, l, 800, 1200)
        assertNotNull(out)
        assert(out!!.width > 0)
        assert(out.height > 0)
    }
}