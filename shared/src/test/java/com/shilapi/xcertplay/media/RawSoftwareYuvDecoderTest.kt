package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodecInfo
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RawSoftwareYuvDecoderTest {
    @After fun reset() { ShadowMediaCodec.clearCodecs(); ShadowMediaCodecList.reset() }
    private fun decoder(name: String) {
        val capability = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat("video/avc", 640, 360))
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName(name).setCapabilities(capability).build())
    }
    private val csd get() = File("../common/src/main/assets/color-test/h264_limited.csd").readBytes()

    @Test fun realConfigureUsesSoftwareWithImageOutputAndDetachNeverParksIt() {
        ShadowMediaCodecList.reset()
        decoder("OMX.google.h264.decoder")
        val configured = CountDownLatch(1)
        val raw = AtomicBoolean(false)
        val fake = object : ShadowMediaCodec.CodecConfig.Codec {
            override fun process(input: ByteBuffer, output: ByteBuffer) {}
            override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                raw.set(surface == null && format?.getInteger(MediaFormat.KEY_COLOR_FORMAT) ==
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                configured.countDown()
            }
        }
        ShadowMediaCodec.addDecoder("OMX.google.h264.decoder", ShadowMediaCodec.CodecConfig(65536, 65536, fake))
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        val sink = AndroidMediaSink(surface, mainScreenSoftwareYuv = SoftwareYuvConfig())
        try {
            assertTrue(sink.mainScreenSoftwareDecoder)
            sink.onVideoConfig(110, csd)
            assertTrue(configured.await(5, TimeUnit.SECONDS))
            assertTrue("raw output must use no codec Surface and request flexible YUV", raw.get())
            assertTrue(sink.beginSurfaceDetach(surface, parkMain = true).await())
            assertFalse(sink.videoColorOutput()?.active ?: false)
            assertEquals(0L, sink.liveVideoCounters()?.rendered)
        } finally {
            sink.close(); assertTrue(sink.awaitVideoReleased(5000)); surface.release(); texture.release()
        }
    }
    @Test fun vendorYuvSupportDoesNotAllowHardwareFallback() {
        ShadowMediaCodecList.reset()
        decoder("OMX.vendor.h264.decoder")
        assertNull(softwareYuvDecoderName("video/avc"))
        val refused = CountDownLatch(1)
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        val sink = AndroidMediaSink(surface, mainScreenSoftwareYuv = SoftwareYuvConfig())
        sink.setVideoDiagnosticHandler(110) { if (it.startsWith("software-yuv unavailable:")) refused.countDown() }
        try {
            sink.onVideoConfig(110, csd)
            assertTrue(refused.await(5, TimeUnit.SECONDS))
            assertFalse(sink.videoColorOutput()?.active ?: false)
        } finally {
            sink.close(); assertTrue(sink.awaitVideoReleased(5000)); surface.release(); texture.release()
        }
    }
}
