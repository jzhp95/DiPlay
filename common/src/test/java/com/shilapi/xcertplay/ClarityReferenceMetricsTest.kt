package com.shilapi.xcertplay
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClarityReferenceMetricsTest {
    @Test fun nativeReferenceRetainsContrastAcrossAllFourStripeWidths() {
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            for ((band, step) in listOf(1, 2, 4, 8).withIndex())
                for (y in listOf(240, 300, 360)) for (x in band * 480 until (band + 1) * 480)
                    bitmap.setPixel(x, y, if (x / step % 2 == 0) Color.BLACK else Color.WHITE)
            val result = ClarityReferenceMetrics.measure(bitmap)
            assertTrue(result.getBoolean("available"))
            for (i in 0..3) assertEquals(255.0, result.getJSONArray("bands").getJSONObject(i).getDouble("contrastCodeValues"), 0.001)
        } finally { bitmap.recycle() }
    }
    @Test fun aFlatBlurredImageMeasuresZeroContrastRatherThanClaimingItIsSharp() {
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GRAY)
            val result = ClarityReferenceMetrics.measure(bitmap)
            assertEquals(0.0, result.getJSONArray("bands").getJSONObject(0).getDouble("contrastCodeValues"), 0.001)
            assertEquals("not_automatically_determined", result.getString("visualVerdict"))
        } finally { bitmap.recycle() }
    }
    @Test fun aScaledScreenshotCannotClaimNativeReferenceMeasurements() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try { assertFalse(ClarityReferenceMetrics.measure(bitmap).getBoolean("available")) } finally { bitmap.recycle() }
    }
}
