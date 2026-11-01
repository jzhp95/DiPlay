package com.shilapi.xcertplay.media.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlatformSurfaceBackendTest {

    private val identity = RuntimeIdentity(
        stream = SessionVideoId("s1", 110, 1L),
        configGeneration = 1L,
        decoderGeneration = 1L,
        surfaceGeneration = 1L,
        backendInstanceId = "surface-backend-1"
    )

    private class FakeCodecDriver(
        override val componentName: String = "OMX.MTK.VIDEO.DECODER.AVC",
        override val isHardwareAccelerated: Boolean = true
    ) : PlatformCodecDriver {
        var configured = false
        var started = false
        var stopped = false
        var closed = false
        var nextInputIndex = 0
        var nextOutput: CodecDriverOutput = CodecDriverOutput.TryAgainLater
        var releasedBuffers = mutableListOf<Pair<Int, Boolean>>()
        var targetSurface: Surface? = null

        val inputBuffers = arrayOf(ByteBuffer.allocate(1024), ByteBuffer.allocate(1024))

        override fun configure(format: MediaFormat, surface: Surface?, flags: Int) {
            configured = true
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

        override fun releaseOutputBuffer(index: Int, render: Boolean) {
            releasedBuffers.add(index to render)
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

    @Test
    fun configureInitializesDriverWithCsdAndTarget() {
        val fakeDriver = FakeCodecDriver()
        val backend = PlatformSurfaceBackend(identity) { fakeDriver }

        val config = VideoStreamConfig(
            streamId = SessionVideoId("s1"),
            codec = CodecType.H264,
            width = 1280,
            height = 720,
            csdData = listOf(byteArrayOf(0, 0, 0, 1, 7))
        )

        val result = backend.configure(
            config,
            OutputTarget(surface = null),
            BackendOptions()
        )

        assertTrue(result is ConfigureResult.Success)
        assertTrue(fakeDriver.configured)
        assertTrue(fakeDriver.started)
        assertEquals(BackendKind.PLATFORM_SURFACE, backend.capabilities().kind)
        assertFalse(backend.capabilities().supportsCpuSampling) // raw_yuv_unavailable
    }

    @Test
    fun trySubmitReturnsBackpressureWhenDriverQueueFull() {
        val fakeDriver = FakeCodecDriver()
        val backend = PlatformSurfaceBackend(identity) { fakeDriver }

        backend.configure(
            VideoStreamConfig(SessionVideoId("s1"), CodecType.H264, 1280, 720),
            OutputTarget(surface = null),
            BackendOptions()
        )

        // Driver returns -1 (INFO_TRY_AGAIN_LATER)
        fakeDriver.nextInputIndex = -1

        val lease = CountingBufferLease(64)
        val unit = testUnit(lease = lease)

        val offer = backend.trySubmit(unit)
        assertEquals(OfferResult.BACKPRESSURE, offer)
        assertEquals(0, lease.closeCalls) // lease not consumed on backpressure
    }

    @Test
    fun pollOutputYieldsFrameLeaseWithDirectRenderOnRelease() {
        val fakeDriver = FakeCodecDriver()
        val backend = PlatformSurfaceBackend(identity) { fakeDriver }

        backend.configure(
            VideoStreamConfig(SessionVideoId("s1"), CodecType.H264, 1280, 720),
            OutputTarget(surface = null),
            BackendOptions()
        )

        fakeDriver.nextOutput = CodecDriverOutput.OutputBuffer(
            index = 3,
            offset = 0,
            size = 100,
            presentationTimeUs = 50_000L,
            flags = 0
        )

        val output = backend.pollOutput(System.nanoTime() + 1_000_000L)
        assertTrue(output is BackendOutput.Frame)

        val frame = (output as BackendOutput.Frame).lease
        assertEquals(identity, frame.key.identity)
        assertEquals(50_000L, frame.key.codecPtsUs)
        assertEquals(0, fakeDriver.releasedBuffers.size)

        // Release frame lease triggers render
        frame.releaseOnce()
        assertEquals(1, fakeDriver.releasedBuffers.size)
        assertEquals(3 to true, fakeDriver.releasedBuffers[0]) // render = true
    }

    @Test
    fun stopAndAwaitReleasedClosesDriverGracefully() {
        val fakeDriver = FakeCodecDriver()
        val backend = PlatformSurfaceBackend(identity) { fakeDriver }

        backend.configure(
            VideoStreamConfig(SessionVideoId("s1"), CodecType.H264, 1280, 720),
            OutputTarget(surface = null),
            BackendOptions()
        )

        backend.requestStop()
        val releaseState = backend.awaitReleased(System.nanoTime() + 100_000_000L)

        assertEquals(ReleaseState.RELEASED, releaseState)
        assertTrue(fakeDriver.stopped)
        assertTrue(fakeDriver.closed)
    }
}
