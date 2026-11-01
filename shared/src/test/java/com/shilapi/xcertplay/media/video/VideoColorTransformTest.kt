package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoColorTransformTest {

    @Test
    fun limitedRangeNormalizes16ToBlackAnd235ToWhite() {
        val transform = VideoColorTransform(ColorRange.LIMITED, ColorMatrix.BT601)

        // Limited Range Neutral Gray / Black: Y=16, U=128, V=128 -> RGB (0, 0, 0)
        val blackRgb = transform.transformYuv8(16, 128, 128)
        assertEquals(0.0f, blackRgb[0], 0.005f)
        assertEquals(0.0f, blackRgb[1], 0.005f)
        assertEquals(0.0f, blackRgb[2], 0.005f)

        // Limited Range White: Y=235, U=128, V=128 -> RGB (1.0, 1.0, 1.0)
        val whiteRgb = transform.transformYuv8(235, 128, 128)
        assertEquals(1.0f, whiteRgb[0], 0.005f)
        assertEquals(1.0f, whiteRgb[1], 0.005f)
        assertEquals(1.0f, whiteRgb[2], 0.005f)
    }

    @Test
    fun fullRangePreservesIntermediateGrayscaleLevelsWithoutPrematureClipping() {
        val transform = VideoColorTransform(ColorRange.FULL, ColorMatrix.BT601)

        // Code values 4, 16, 224, 235, 236, 238, 240 must all produce distinct, monotonically increasing values
        val testLevels = listOf(4, 16, 224, 235, 236, 238, 240)
        var previousVal = -1.0f

        for (y in testLevels) {
            val rgb = transform.transformYuv8(y, 128, 128)
            val currentVal = rgb[0]

            // Full range level 4 is ~4/255 > 0
            if (y == 4) {
                assertTrue("Level 4 should not be clamped to 0 in Full range", currentVal > 0.01f)
            }
            // Full range level 235 is ~235/255 < 1.0
            if (y == 235) {
                assertTrue("Level 235 should not be prematurely clipped to 1.0 in Full range", currentVal < 0.95f)
            }

            assertTrue("Levels must be strictly monotonic: $currentVal > $previousVal for y=$y", currentVal > previousVal)
            previousVal = currentVal
        }
    }

    @Test
    fun distinguishesBt601AndBt709ColorMatricesForChroma() {
        val transform601 = VideoColorTransform(ColorRange.LIMITED, ColorMatrix.BT601)
        val transform709 = VideoColorTransform(ColorRange.LIMITED, ColorMatrix.BT709)

        // Moderate non-clamping chroma test point: Y=100, U=100, V=180
        val rgb601 = transform601.transformYuv8(100, 100, 180)
        val rgb709 = transform709.transformYuv8(100, 100, 180)

        // The R and G components differ significantly between 601 and 709 due to differing Cr coefficients
        assertTrue("R component must differ between 601 and 709", Math.abs(rgb601[0] - rgb709[0]) > 0.02f)
        assertTrue("G component must differ between 601 and 709", Math.abs(rgb601[1] - rgb709[1]) > 0.02f)
    }

    @Test
    fun glslUniformMatrixIsColumnMajor() {
        val transform = VideoColorTransform(ColorRange.FULL, ColorMatrix.BT709)
        val colMajor = transform.colorMatrixColumnMajor
        val rowMajor = transform.colorMatrixRowMajor

        // In colMajor: index 1 is Row 1 Col 0 (which is rowMajor[3])
        // In rowMajor: index 1 is Row 0 Col 1 (which is colMajor[3])
        assertEquals(rowMajor[1], colMajor[3], 0.0001f)
        assertEquals(rowMajor[2], colMajor[6], 0.0001f)
        assertEquals(rowMajor[5], colMajor[7], 0.0001f)
    }
}
