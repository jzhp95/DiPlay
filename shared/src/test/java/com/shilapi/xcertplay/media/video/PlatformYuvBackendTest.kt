package com.shilapi.xcertplay.media.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlatformYuvBackendTest {

    private val identity = RuntimeIdentity(
        stream = SessionVideoId("s1", 110, 1L),
        configGeneration = 1L,
        decoderGeneration = 1L,
        surfaceGeneration = 1L,
        backendInstanceId = "yuv-backend-1"
    )

    private class FakeImagePlane(
        override val buffer: ByteBuffer,
        override val rowStride: Int,
        override val pixelStride: Int
    ) : CodecImagePlane

    private class FakeOutputImage(
        override val width: Int = 1280,
        override val height: Int = 720,
        override val format: Int = 0x7F420888,
        override val cropLeft: Int = 0,
        override val cropTop: Int = 0,
        override val cropWidth: Int = width,
        override val cropHeight: Int = height,
        override val planes: Array<CodecImagePlane>,
        val onClose: () -> Unit = {}
    ) : CodecOutputImage {
        var closed = false
        override fun close() {
            closed = true
            onClose()
        }
    }

    private class FakeCodecDriver(
        override val componentName: String = "OMX.MTK.VIDEO.DECODER.AVC",
        override val isHardwareAccelerated: Boolean = true
    ) : PlatformCodecDriver {
        var configured = false
        var targetSurface: Surface? = null
        var configuredFormat: MediaFormat? = null
        var started = false
        var stopped = false
        var closed = false
        var nextInputIndex = 0
        var nextOutput: CodecDriverOutput = CodecDriverOutput.TryAgainLater
        var nextImage: CodecOutputImage? = null
        var throwOnGetImage: Throwable? = null
        val releasedBuffers = mutableListOf<Pair<Int, Boolean>>()
        var throwOnReleaseBuffer: Throwable? = null

        val inputBuffers = arrayOf(ByteBuffer.allocate(1024), ByteBuffer.allocate(1024))

        override fun configure(format: MediaFormat, surface: Surface?, flags: Int) {
            configured = true
            configuredFormat = format
            targetSurface = surface
        }

        override fun start() {
            started = true
        }

        override fun dequeueInputBuffer(timeoutUs: Long): Int = nextInputIndex

        override fun getInputBuffer(index: Int): ByteBuffer? =
            if (index in inputBuffers.indices) inputBuffers[index] else null

        override fun queueInputBuffer(
            index: Int,
            offset: Int,
            size: Int,
            presentationTimeUs: Long,
            flags: Int
        ) {}

        override fun dequeueOutputBuffer(
            bufferInfo: MediaCodec.BufferInfo,
            timeoutUs: Long
        ): CodecDriverOutput = nextOutput

        override fun getOutputImage(index: Int): CodecOutputImage? {
            throwOnGetImage?.let { throw it }
            return nextImage
        }

        override fun releaseOutputBuffer(index: Int, render: Boolean) {
            releasedBuffers.add(index to render)
            throwOnReleaseBuffer?.let { throw it }
        }

        override fun releaseOutputBufferAtTime(index: Int, renderTimestampNs: Long) {
            releasedBuffers.add(index to true)
        }

        override fun setOutputSurface(surface: Surface) {
            targetSurface = surface
        }

        override fun flush() {}
        override fun stop() { stopped = true }
        override fun close() { closed = true }
    }

    private fun createMockPlanes(
        width: Int,
        height: Int,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        uPixelStride: Int = 1,
        vPixelStride: Int = 1
    ): Array<CodecImagePlane> {
        val yBuf = ByteBuffer.allocateDirect(yRowStride * height)
        val uBuf = ByteBuffer.allocateDirect(uRowStride * (height / 2))
        val vBuf = ByteBuffer.allocateDirect(vRowStride * (height / 2))

        // Fill with distinct test byte patterns
        for (i in 0 until yBuf.capacity()) yBuf.put(i, (i % 250).toByte())
        for (i in 0 until uBuf.capacity()) uBuf.put(i, ((i + 50) % 250).toByte())
        for (i in 0 until vBuf.capacity()) vBuf.put(i, ((i + 100) % 250).toByte())

        return arrayOf(
            FakeImagePlane(yBuf, yRowStride, 1),
            FakeImagePlane(uBuf, uRowStride, uPixelStride),
            FakeImagePlane(vBuf, vRowStride, vPixelStride)
        )
    }

    @Test
    fun configureSetsFlexibleColorFormatAndNullSurface() {
        val fakeDriver = FakeCodecDriver()
        val backend = PlatformYuvBackend(identity) { _, _ -> fakeDriver }

        val config = VideoStreamConfig(
            streamId = SessionVideoId("s1"),
            codec = CodecType.H264,
            width = 1280,
            height = 720
        )

        val result = backend.configure(config, OutputTarget(surface = null), BackendOptions())
        assertTrue(result is ConfigureResult.Success)
        assertTrue(fakeDriver.configured)
        assertEquals(null, fakeDriver.targetSurface) // CPU-readable YUV requires surface = null

        val colorFormat = fakeDriver.configuredFormat?.getInteger(MediaFormat.KEY_COLOR_FORMAT)
        assertEquals(0x7F420888, colorFormat) // COLOR_FormatYUV420Flexible
        assertTrue(backend.capabilities().supportsCpuSampling)
        assertFalse(backend.capabilities().supportsDirectSurface)
    }

    @Test
    fun softwareDecoderCanBeSelectedByPreference() {
        var requestedPref = DecoderPreference.AUTO
        val fakeDriver = FakeCodecDriver(componentName = "OMX.google.h264.decoder", isHardwareAccelerated = false)
        val backend = PlatformYuvBackend(identity) { _, pref ->
            requestedPref = pref
            fakeDriver
        }

        val config = VideoStreamConfig(
            streamId = SessionVideoId("s1"),
            codec = CodecType.H264,
            width = 1280,
            height = 720
        )

        val options = BackendOptions(decoderPreference = DecoderPreference.SOFTWARE)
        val result = backend.configure(config, OutputTarget(surface = null), options)

        assertTrue(result is ConfigureResult.Success)
        assertEquals(DecoderPreference.SOFTWARE, requestedPref)
        assertEquals("OMX.google.h264.decoder", fakeDriver.componentName)
        assertFalse(fakeDriver.isHardwareAccelerated)
    }

    @Test
    fun pollOutputProvidesMemoryDecodedFrameLeaseWithDirectPlanes() {
        val fakeDriver = FakeCodecDriver()
        val backend = PlatformYuvBackend(identity) { _, _ -> fakeDriver }

        backend.configure(
            VideoStreamConfig(SessionVideoId("s1"), CodecType.H264, 1280, 720),
            OutputTarget(surface = null),
            BackendOptions()
        )

        val planes = createMockPlanes(
            width = 1280,
            height = 720,
            yRowStride = 1312, // Stride with padding
            uRowStride = 672,
            vRowStride = 672,
            uPixelStride = 1,
            vPixelStride = 1
        )
        val fakeImage = FakeOutputImage(
            width = 1280,
            height = 720,
            planes = planes
        )

        fakeDriver.nextOutput = CodecDriverOutput.OutputBuffer(
            index = 1,
            offset = 0,
            size = 1000,
            presentationTimeUs = 20_000L,
            flags = 0
        )
        fakeDriver.nextImage = fakeImage

        val output = backend.pollOutput(System.nanoTime() + 1_000_000L)
        assertTrue(output is BackendOutput.Frame)
        val frame = (output as BackendOutput.Frame).lease
        assertTrue(frame is MemoryDecodedFrameLease)

        val memFrame = frame as MemoryDecodedFrameLease
        assertEquals("OMX.MTK.VIDEO.DECODER.AVC", memFrame.outputDescription.actualComponent)
        assertEquals(1312, memFrame.yPlane.rowStride)
        assertEquals(1, memFrame.yPlane.pixelStride)

        // Read pixel samples
        val yVal = memFrame.yView.sample8(0, 0)
        assertNotNull(yVal)

        // Release lease: closes Image and releases codec buffer
        assertEquals(0, fakeDriver.releasedBuffers.size)
        assertFalse(fakeImage.closed)

        frame.releaseOnce()
        assertTrue(fakeImage.closed)
        assertEquals(1, fakeDriver.releasedBuffers.size)
        assertEquals(1 to false, fakeDriver.releasedBuffers[0]) // render=false for CPU YUV

        // Accessing plane after release throws IllegalStateException
        try {
            memFrame.yView.sample8(0, 0)
            fail("Expected IllegalStateException after release")
        } catch (_: IllegalStateException) {}
    }

    @Test
    fun pollOutputHandlesNullImageGracefully() {
        val fakeDriver = FakeCodecDriver()
        val backend = PlatformYuvBackend(identity) { _, _ -> fakeDriver }

        backend.configure(
            VideoStreamConfig(SessionVideoId("s1"), CodecType.H264, 1280, 720),
            OutputTarget(surface = null),
            BackendOptions()
        )

        fakeDriver.nextOutput = CodecDriverOutput.OutputBuffer(
            index = 2,
            offset = 0,
            size = 1000,
            presentationTimeUs = 30_000L,
            flags = 0
        )
        fakeDriver.nextImage = null // Null image from driver

        val output = backend.pollOutput(System.nanoTime() + 1_000_000L)
        assertTrue(output is BackendOutput.Failure)
        assertEquals(1, fakeDriver.releasedBuffers.size)
        assertEquals(2 to false, fakeDriver.releasedBuffers[0])
    }

    @Test
    fun releasePreservesBothExceptionsAndNeverReReleases() {
        val fakeDriver = FakeCodecDriver()
        val backend = PlatformYuvBackend(identity) { _, _ -> fakeDriver }

        backend.configure(
            VideoStreamConfig(SessionVideoId("s1"), CodecType.H264, 1280, 720),
            OutputTarget(surface = null),
            BackendOptions()
        )

        val planes = createMockPlanes(640, 480, 640, 320, 320)
        val imageErr = IllegalStateException("Image.close failure")
        val codecErr = RuntimeException("releaseOutputBuffer failure")

        val fakeImage = FakeOutputImage(
            width = 640,
            height = 480,
            planes = planes,
            onClose = { throw imageErr }
        )

        fakeDriver.nextOutput = CodecDriverOutput.OutputBuffer(
            index = 4,
            offset = 0,
            size = 100,
            presentationTimeUs = 10_000L,
            flags = 0
        )
        fakeDriver.nextImage = fakeImage
        fakeDriver.throwOnReleaseBuffer = codecErr

        val output = backend.pollOutput(System.nanoTime() + 1_000_000L)
        val frame = (output as BackendOutput.Frame).lease

        // First release attempts should throw imageErr with codecErr suppressed
        try {
            frame.releaseOnce()
            fail("Expected exception on release")
        } catch (t: Throwable) {
            assertEquals(imageErr, t)
            assertEquals(1, t.suppressed.size)
            assertEquals(codecErr, t.suppressed[0])
        }

        assertEquals(1, frame.releaseAttempts)

        // Second release call does not invoke driver/image again
        frame.releaseOnce()
        assertEquals(2, frame.releaseAttempts)
        assertEquals(1, fakeDriver.releasedBuffers.size) // Not re-released
    }
}
