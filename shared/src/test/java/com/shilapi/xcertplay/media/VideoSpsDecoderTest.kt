package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec

/** Proves the production configure path receives SPS hints; does not prove rendered pixel colors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoSpsDecoderTest {
    @Test fun productionCodecReceivesSpsHintsOnlyForTheTemporaryDiagnosticMode() {
        for ((name, codec) in listOf("h264" to VideoCodec.H264, "hevc" to VideoCodec.H265))
            for ((range, expectedRange) in listOf("full" to 1, "limited" to 2)) for (enabled in listOf(false, true)) {
                val mime = if (codec == VideoCodec.H264) "video/avc" else "video/hevc"
                val configured = CountDownLatch(1)
                val received = AtomicReference<Map<String, Int>>()
                val fake = object : ShadowMediaCodec.CodecConfig.Codec {
                    override fun process(input: ByteBuffer, output: ByteBuffer) {}
                    override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                        received.set(listOf("color-standard", "color-range", "color-transfer").mapNotNull { key ->
                            if (format?.containsKey(key) == true) key to format.getInteger(key) else null
                        }.toMap())
                        configured.countDown()
                    }
                }
                ShadowMediaCodec.addDecoder(mime, ShadowMediaCodec.CodecConfig(65536, 65536, fake))
                val texture = SurfaceTexture(0)
                val surface = Surface(texture)
                val sink = AndroidMediaSink(surface = surface, videoColorOverride = VideoColorOverride(fromSps = enabled))
                try {
                    sink.onVideoCodec(110, codec)
                    sink.onVideoConfig(110, File("../common/src/main/assets/color-test/${name}_$range.csd").readBytes())
                    assertTrue("$name/$range SPS=$enabled did not configure", configured.await(5, TimeUnit.SECONDS))
                    if (enabled) {
                        assertEquals(expectedRange, received.get()["color-range"])
                        assertEquals(1, received.get()["color-standard"])
                        assertEquals(3, received.get()["color-transfer"])
                    } else assertTrue("daily playback must keep its existing configuration", received.get().isEmpty())
                } finally {
                    sink.close()
                    assertTrue(sink.awaitVideoReleased(5000))
                    surface.release(); texture.release()
                    ShadowMediaCodec.clearCodecs()
                }
            }
    }
}
