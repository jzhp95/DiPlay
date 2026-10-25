package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MainScreenSoftwareDecoderTest {
    @Test fun compatibilityUsesStrictSoftwareOnlyOnTheMainScreen() {
        val sink = AndroidMediaSink(null, vendorLowLatencyDecoder = true, mainScreenSoftwareDecoder = true)
        val surface = Surface(SurfaceTexture(0))
        try {
            sink.setMirrorSurface(110, "test", surface)
            sink.onVideoConfig(110, byteArrayOf())
            sink.onVideoConfig(111, byteArrayOf())
            val decoders = ReflectionHelpers.getField<Map<Int, Any>>(sink, "videoDecoders")
            assertTrue(ReflectionHelpers.getField(decoders.getValue(110), "forceSoftwareVideoDecoder"))
            assertFalse(ReflectionHelpers.getField(decoders.getValue(111), "forceSoftwareVideoDecoder"))
            val mirrors = ReflectionHelpers.getField<Map<Any, Any>>(sink, "mirrorDecoders")
            assertFalse(ReflectionHelpers.getField(decoders.getValue(110), "vendorLowLatency"))
            assertTrue(ReflectionHelpers.getField(decoders.getValue(111), "vendorLowLatency"))
            assertEquals(1, mirrors.size)
            assertTrue(ReflectionHelpers.getField(mirrors.values.single(), "vendorLowLatency"))
            assertFalse(ReflectionHelpers.getField(mirrors.values.single(), "forceSoftwareVideoDecoder"))
        } finally {
            sink.close()
            assertTrue(sink.awaitVideoReleased(5_000))
            surface.release()
        }
    }
}
