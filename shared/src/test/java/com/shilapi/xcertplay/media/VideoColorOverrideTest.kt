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
}
