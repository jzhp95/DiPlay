package com.shilapi.xcertplay.media

import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SoftwareYuvColorTest {
    private fun rgb(transform: YuvColorTransform, y: Int, u: Int = 128, v: Int = 128): List<Int> {
        val input = floatArrayOf(y / 255f, u / 255f, v / 255f)
        return (0..2).map { row ->
            ((0..2).sumOf { column ->
                (transform.coefficients[column * 3 + row] * (input[column] - transform.offset[column])).toDouble()
            } * 255).toInt().coerceIn(0, 255)
        }
    }
    @Test fun fullRangeKeepsNearWhiteStepsDistinct() {
        val color = SoftwareYuvColor.resolve(SoftwareYuvConfig(1, 1), null, null)
        for (gray in listOf(0, 16, 192, 208, 224, 232, 240, 248, 252, 255))
            rgb(color, gray).forEach { assertEquals(gray.toDouble(), it.toDouble(), 1.0) }
    }
    @Test fun limitedRangeUsesLegalBlackAndWhiteInsteadOfClippingLightGray() {
        val color = SoftwareYuvColor.resolve(SoftwareYuvConfig(2, 1), null, null)
        for ((input, expected) in listOf(16 to 0, 32 to 19, 128 to 130, 224 to 242, 232 to 251, 235 to 255))
            rgb(color, input).forEach { assertEquals(expected.toDouble(), it.toDouble(), 1.0) }
        assertTrue(rgb(color, 224)[0] < rgb(color, 232)[0])
        assertTrue(rgb(color, 232)[0] < rgb(color, 235)[0])
    }
    @Test fun primaryColorReferenceVectorsSelectTheRightMatrix() {
        val bt601 = SoftwareYuvColor.resolve(SoftwareYuvConfig(1, 2), null, null)
        val bt709 = SoftwareYuvColor.resolve(SoftwareYuvConfig(1, 1), null, null)
        for ((transform, red) in listOf(bt601 to Triple(76, 85, 255), bt709 to Triple(54, 99, 255))) {
            val result = rgb(transform, red.first, red.second, red.third)
            assertTrue(result.toString(), result[0] >= 252 && result[1] <= 3 && result[2] <= 3)
        }
        assertNotEquals(rgb(bt601, 128, 80, 180), rgb(bt709, 128, 80, 180))
    }
    @Test fun explicitChoicesOverrideSpsWhichOverridesDecoderHints() {
        val source = VideoColorMetadata.Description(VideoColorMetadata.Status.PARSED, true, 6, 6, 6)
        val format = MediaFormat().apply {
            setInteger(MediaFormat.KEY_COLOR_RANGE, 2); setInteger(MediaFormat.KEY_COLOR_STANDARD, 1)
        }
        val auto = SoftwareYuvColor.resolve(SoftwareYuvConfig(), source, format)
        assertTrue(auto.fullRange); assertFalse(auto.bt709); assertEquals("SPS", auto.rangeSource)
        val manual = SoftwareYuvColor.resolve(SoftwareYuvConfig(2, 1), source, format)
        assertFalse(manual.fullRange); assertTrue(manual.bt709); assertEquals("manual", manual.rangeSource)
        val decoder = SoftwareYuvColor.resolve(SoftwareYuvConfig(), null, format)
        assertFalse(decoder.fullRange); assertTrue(decoder.bt709); assertEquals("decoder", decoder.matrixSource)
    }
    @Test fun missingInformationIsReportedAsAnAssumption() {
        val color = SoftwareYuvColor.resolve(SoftwareYuvConfig(), null, null)
        assertEquals("assumed-limited", color.rangeSource)
        assertEquals("assumed-BT709", color.matrixSource)
    }
    @Test fun unsupportedHdrAndWideGamutDoNotSilentlyUseSdrMath() {
        for (source in listOf(
            VideoColorMetadata.Description(VideoColorMetadata.Status.PARSED, false, 1, 16, 1),
            VideoColorMetadata.Description(VideoColorMetadata.Status.PARSED, false, 9, 1, 9),
            VideoColorMetadata.Description(VideoColorMetadata.Status.PARSED, false, 1, 1, 10))) {
            assertThrows(IllegalArgumentException::class.java) {
                SoftwareYuvColor.resolve(SoftwareYuvConfig(1, 1), source, null)
            }
        }
    }
}
