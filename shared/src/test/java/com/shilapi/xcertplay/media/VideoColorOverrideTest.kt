package com.shilapi.xcertplay.media

import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoColorOverrideTest {
    @Test fun automaticModeLeavesBitstreamColorMetadataToTheDecoder() {
        val format = MediaFormat.createVideoFormat("video/avc", 640, 360)
        VideoColorOverride().applyTo(format)
        assertFalse(format.containsKey(MediaFormat.KEY_COLOR_RANGE))
        assertFalse(format.containsKey(MediaFormat.KEY_COLOR_STANDARD))
        assertFalse(format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
    }
    @Test fun diagnosticOverridesOnlyTouchTheExplicitlySelectedFields() {
        val format = MediaFormat.createVideoFormat("video/avc", 640, 360)
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, 3)
        VideoColorOverride(range = 1, standard = 1).applyTo(format)
        assertEquals(1, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
        assertEquals(1, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
        assertEquals(3, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
        assertEquals(640, format.getInteger(MediaFormat.KEY_WIDTH))
    }

    @Test fun spsModeCopiesKnownFieldsForBothCodecsAndBothEncodedRanges() {
        for ((name, codec) in listOf("h264" to com.shilapi.xcertplay.airplay.VideoCodec.H264,
            "hevc" to com.shilapi.xcertplay.airplay.VideoCodec.H265)) for ((range, expected) in listOf("full" to 1, "limited" to 2)) {
            val csd = java.io.File("../common/src/main/assets/color-test/${name}_$range.csd").readBytes()
            val description = VideoColorMetadata.parse(codec, csd)
            val format = MediaFormat.createVideoFormat("video/avc", 640, 360)
            VideoColorOverride(fromSps = true).applyTo(format, description)
            assertEquals(expected, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
            assertEquals(3, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
        }
    }
    @Test fun unknownSpsFieldsStayAbsentAndManualValuesWinOverSps() {
        val format = MediaFormat.createVideoFormat("video/avc", 640, 360)
        VideoColorOverride(fromSps = true).applyTo(format,
            VideoColorMetadata.Description(VideoColorMetadata.Status.MALFORMED))
        assertFalse(format.containsKey(MediaFormat.KEY_COLOR_RANGE))
        assertFalse(format.containsKey(MediaFormat.KEY_COLOR_STANDARD))
        assertFalse(format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
        VideoColorOverride(range = 2, standard = 4, fromSps = true).applyTo(format,
            VideoColorMetadata.Description(VideoColorMetadata.Status.PARSED, true, 1, 1, 1))
        assertEquals(2, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
        assertEquals(4, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
        assertEquals(3, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
        val unknown = MediaFormat.createVideoFormat("video/avc", 640, 360)
        VideoColorOverride(fromSps = true).applyTo(unknown,
            VideoColorMetadata.Description(VideoColorMetadata.Status.PARSED, false, 2, 2, 2))
        assertEquals(2, unknown.getInteger(MediaFormat.KEY_COLOR_RANGE))
        assertFalse(unknown.containsKey(MediaFormat.KEY_COLOR_STANDARD))
        assertFalse(unknown.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
    }
}
