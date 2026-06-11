package io.github.jiro.expensetracker.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ImageProcessorTest {

    @Test
    fun computeDownscaleDims_smallImage_returnsSameSize() {
        assertEquals(100 to 100, ImageProcessor.computeDownscaleDims(100, 100, 2048))
    }

    @Test
    fun computeDownscaleDims_largeLandscapeImage_scalesLongEdge() {
        // 4000/3000 = 4/3. After downscaling: long edge = 2048, short edge = 1536.
        assertEquals(2048 to 1536, ImageProcessor.computeDownscaleDims(4000, 3000, 2048))
    }

    @Test
    fun computeDownscaleDims_alreadyAtMax_returnsSameSize() {
        assertEquals(2048 to 1536, ImageProcessor.computeDownscaleDims(2048, 1536, 2048))
    }

    @Test
    fun computeDownscaleDims_portraitAspect_preserved() {
        // 1500/3000 = 0.5. After downscaling: long edge = 2048, short edge = 1024.
        assertEquals(1024 to 2048, ImageProcessor.computeDownscaleDims(1500, 3000, 2048))
    }

    @Test
    fun computeDownscaleDims_squareImage_scalesToMax() {
        assertEquals(2048 to 2048, ImageProcessor.computeDownscaleDims(5000, 5000, 2048))
        // Long edge = 5000 > 2048, scale = 2048/5000 ≈ 0.4096, new edge ≈ 2048.
        // (5000 * 0.4096).toInt() = 2048.
    }

    @Test
    fun computeDownscaleDims_extremeAspect_preservesMinimumDimension() {
        // 100 x 10000: long edge = 10000, scale = 2048/10000 = 0.2048,
        // newW = (100 * 0.2048).toInt() = 20. coerceAtLeast(1) leaves it at 20.
        assertEquals(20 to 2048, ImageProcessor.computeDownscaleDims(100, 10000, 2048))
    }

    @Test
    fun computeDownscaleDims_zeroMaxEdge_throws() {
        try {
            ImageProcessor.computeDownscaleDims(100, 100, 0)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun computeDownscaleDims_zeroDimensions_throws() {
        try {
            ImageProcessor.computeDownscaleDims(0, 100, 2048)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
