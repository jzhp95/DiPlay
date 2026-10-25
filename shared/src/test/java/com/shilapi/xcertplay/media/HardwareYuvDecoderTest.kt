package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodecInfo
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
class HardwareYuvDecoderTest {
    @After fun reset() { ShadowMediaCodec.clearCodecs(); ShadowMediaCodecList.reset() }
    private fun register(name: String, raw: Boolean, configure: (MediaFormat?, Surface?) -> Unit) {
        val cap = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat("video/avc", 640, 360))
            .setColorFormats(if (raw) intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                else intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName(name).setCapabilities(cap).build())
        ShadowMediaCodec.addDecoder(name, ShadowMediaCodec.CodecConfig(65536, 65536,
            object : ShadowMediaCodec.CodecConfig.Codec {
                override fun process(input: ByteBuffer, output: ByteBuffer) {}
                override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                    configure(format, surface)
                }
            }))
    }
    private fun withSink(check: (AndroidMediaSink) -> Unit) {
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        val sink = AndroidMediaSink(surface, mainScreenHardwareYuv = true)
        try {
            sink.onVideoConfig(110, File("../common/src/main/assets/color-test/h264_full.csd").readBytes())
            check(sink)
        } finally {
            sink.close(); assertTrue(sink.awaitVideoReleased(5000)); surface.release(); texture.release()
        }
    }
    @Test fun rawHardwareExplicitlyChoosesMtkAndNeverRequestsASoftwareDecoder() {
        ShadowMediaCodecList.reset()
        val ready = CountDownLatch(1)
        register("OMX.google.h264.decoder", true) { _, _ -> fail("software is not the hardware experiment") }
        register("OMX.MTK.VIDEO.DECODER.AVC", true) { format, surface ->
            assertNull(surface)
            assertEquals(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                format!!.getInteger(MediaFormat.KEY_COLOR_FORMAT))
            ready.countDown()
        }
        withSink { sink ->
            assertFalse(sink.mainScreenSoftwareDecoder)
            assertTrue(ready.await(5, TimeUnit.SECONDS))
        }
    }
    @Test fun rawConfigureRefusalFallsBackOnceToSurfaceAndKeepsADiagnostic() {
        ShadowMediaCodecList.reset()
        val surfaceReady = CountDownLatch(1)
        val reports = CopyOnWriteArrayList<String>()
        register("OMX.MTK.VIDEO.DECODER.AVC", true) { _, surface ->
            if (surface == null) throw IllegalArgumentException("driver refuses raw output")
            surfaceReady.countDown()
        }
        registerSurfaceFallback(surfaceReady)
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        val sink = AndroidMediaSink(surface, mainScreenHardwareYuv = true)
        sink.setVideoDiagnosticHandler(110) { reports.add(it) }
        try {
            sink.onVideoConfig(110, File("../common/src/main/assets/color-test/h264_full.csd").readBytes())
            assertTrue(surfaceReady.await(5, TimeUnit.SECONDS))
            assertEquals(1, reports.count { it.startsWith("hardware-yuv unavailable:") })
            assertTrue(reports.any { it.contains("falling back to Surface") })
            assertFalse(reports.any { it.contains("hardware-yuv rendered") })
        } finally { sink.close(); assertTrue(sink.awaitVideoReleased(5000)); surface.release(); texture.release() }
    }
    private fun registerSurfaceFallback(ready: CountDownLatch) {
        // createDecoderByType uses the MIME entry in Robolectric, distinct from createByCodecName.
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, ShadowMediaCodec.CodecConfig(65536, 65536,
            object : ShadowMediaCodec.CodecConfig.Codec {
                override fun process(input: ByteBuffer, output: ByteBuffer) {}
                override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                    assertNotNull(surface); assertFalse(format!!.containsKey(MediaFormat.KEY_COLOR_FORMAT))
                    ready.countDown()
                }
            }))
    }
    @Test fun rawRuntimeFailureRetiresTheRawCodecBeforeStartingSurfaceFallback() {
        ShadowMediaCodecList.reset()
        register("OMX.MTK.VIDEO.DECODER.AVC", true) { _, surface -> assertNull(surface) }
        val surfaceReady = CountDownLatch(1); registerSurfaceFallback(surfaceReady)
        val unavailable = CountDownLatch(1)
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        val sink = AndroidMediaSink(surface, mainScreenHardwareYuv = true)
        sink.setVideoDiagnosticHandler(110) { if (it.startsWith("hardware-yuv unavailable:")) unavailable.countDown() }
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
        // Model the peer returning a fresh IDR after recovery, rather than racing the old queue cleanup.
        sink.setVideoRecoveryHandler(110) { sink.onVideoFrame(110, idr) }
        try {
            sink.onVideoConfig(110, File("../common/src/main/assets/color-test/h264_full.csd").readBytes())
            sink.onVideoFrame(110, idr)
            assertTrue(unavailable.await(8, TimeUnit.SECONDS))
            assertTrue(surfaceReady.await(5, TimeUnit.SECONDS))
        } finally { sink.close(); assertTrue(sink.awaitVideoReleased(5000)); surface.release(); texture.release() }
    }
    @Test fun mainRawExperimentPreservesSecondaryIndependentOutputPolicy() {
        ShadowMediaCodecList.reset()
        val configured = CountDownLatch(1); registerSurfaceFallback(CountDownLatch(1))
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        val sink = AndroidMediaSink(surface, mainScreenHardwareYuv = true, vendorLowLatencyDecoder = true)
        sink.setVideoDiagnosticHandler(111) { if (it.startsWith("decoder=") && it.contains("outputThread=true")) configured.countDown() }
        try {
            sink.onVideoConfig(111, File("../common/src/main/assets/color-test/h264_full.csd").readBytes())
            assertTrue(configured.await(5, TimeUnit.SECONDS))
        } finally { sink.close(); assertTrue(sink.awaitVideoReleased(5000)); surface.release(); texture.release() }
    }
    @Test fun surfaceOnlyMtkAndOtherVendorsAreNotRawHardwareCandidates() {
        ShadowMediaCodecList.reset()
        register("OMX.MTK.VIDEO.DECODER.AVC", false) { _, _ -> }
        register("OMX.vendor.h264.decoder", true) { _, _ -> }
        assertNull(mtkYuvDecoderName("video/avc"))
    }
}
