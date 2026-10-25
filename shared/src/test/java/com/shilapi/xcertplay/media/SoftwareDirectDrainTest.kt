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
class SoftwareDirectDrainTest {
    @After fun reset() { ShadowMediaCodec.clearCodecs(); ShadowMediaCodecList.reset() }
    @Test fun softwareSurfaceOutputDrainsOnItsOwnAndPreservesTheSurfaceCodecPath() {
        ShadowMediaCodecList.reset()
        val cap = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat("video/avc", 640, 360))
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
            .setName("OMX.google.h264.decoder").setCapabilities(cap).build())
        ShadowMediaCodec.addDecoder("OMX.google.h264.decoder", ShadowMediaCodec.CodecConfig(65536, 65536,
            object : ShadowMediaCodec.CodecConfig.Codec {
                override fun process(input: ByteBuffer, output: ByteBuffer) {}
                override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                    assertNotNull(surface)
                    assertFalse(format!!.containsKey(MediaFormat.KEY_COLOR_FORMAT))
                }
            }))
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        val sink = AndroidMediaSink(surface, mainScreenSoftwareDecoder = true)
        val reports = CopyOnWriteArrayList<String>(); val first = CountDownLatch(1)
        sink.setVideoDiagnosticHandler(110) { reports.add(it); if (it == "first frame rendered") first.countDown() }
        try {
            sink.onVideoConfig(110, File("../common/src/main/assets/color-test/h264_full.csd").readBytes())
            sink.onVideoFrame(110, byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21))
            assertTrue(first.await(5, TimeUnit.SECONDS))
            assertTrue(reports.any { it.contains("decoder=OMX.google.h264.decoder") && it.contains("outputThread=true") })
            assertTrue(sink.liveVideoCounters()!!.rendered > 0)
            assertTrue(sink.beginSurfaceDetach(surface, parkMain = false).await())
        } finally { sink.close(); assertTrue(sink.awaitVideoReleased(5000)); surface.release(); texture.release() }
    }
}
