package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.video.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class IndependentProductionRoutingTest {
    private class Backend : VideoBackend {
        val configured = CountDownLatch(1)
        val displayed = CountDownLatch(1)
        val attempts = AtomicInteger()
        val releaseCount = AtomicInteger()
        var codec: CodecType? = null
        var outputPending = false
        var releaseGate: CountDownLatch? = null
        override fun capabilities() = BackendCapabilities(BackendKind.PLATFORM_SURFACE, setOf(CodecType.H264, CodecType.HEVC), false, true)
        override fun configure(config: VideoStreamConfig, target: OutputTarget, options: BackendOptions): ConfigureResult {
            codec = config.codec; configured.countDown()
            return ConfigureResult.Success("fake", capabilities())
        }
        override fun trySubmit(unit: EncodedAccessUnit): OfferResult {
            if (attempts.incrementAndGet() == 1) return OfferResult.BACKPRESSURE
            unit.lease.close(); outputPending = true
            return OfferResult.ACCEPTED
        }
        override fun pollOutput(deadlineNs: Long): BackendOutput {
            if (!outputPending) return BackendOutput.NoOutput
            outputPending = false
            return BackendOutput.Frame(MediaCodecDecodedFrameLease(
                FrameKey(RuntimeIdentity(SessionVideoId("test"), 1, 1, 1, "fake"), 1, 1), 0,
                DecoderOutputDescription(actualComponent = "fake.decoder")) { displayed.countDown() })
        }
        override fun requestStop() {}
        override fun awaitReleased(deadlineNs: Long): ReleaseState {
            releaseGate?.await(3, TimeUnit.SECONDS)
            releaseCount.incrementAndGet()
            return ReleaseState.RELEASED
        }
    }

    @Test fun independentMainActuallyConsumesFramesWithoutCreatingLegacyMainDecoder() {
        val backend = Backend()
        val endpoint = IndependentVideoEndpoint(backendFactory = { _, _, _ -> backend })
        val texture = SurfaceTexture(1)
        val surface = Surface(texture)
        val sink = AndroidMediaSink(videoImplementation = VideoImplementation.INDEPENDENT,
            independentEndpointFactory = { endpoint })
        try {
            sink.onVideoCodec(110, VideoCodec.H264)
            sink.onVideoConfig(110, byteArrayOf(0,0,0,1,0x67,66,0,31))
            sink.setSurface(110, surface)
            assertTrue(backend.configured.await(3, TimeUnit.SECONDS))
            sink.onVideoFrame(110, byteArrayOf(0,0,0,1,0x65,1,2,3))
            assertTrue("frame must traverse submit, output and display despite backpressure", backend.displayed.await(3, TimeUnit.SECONDS))
            assertTrue(backend.attempts.get() >= 2)
            assertEquals(VideoImplementation.INDEPENDENT, sink.videoRuntimeSnapshot()?.actualImplementation)
            assertEquals(0, sink.resourceOwnershipCounts()["primaryVideoDecoders"])
            assertNull(sink.videoColorEvidence())
            assertTrue(sink.beginSurfaceDetach(surface, false).await())
            assertEquals(1, backend.releaseCount.get())
        } finally {
            sink.close(); assertTrue(sink.awaitVideoReleased(3000)); surface.release(); texture.release()
        }
    }

    @Test fun surfaceBeforeHevcConfigWorksAndStaleDetachWaitsForReplacementRelease() {
        val old = Backend()
        val next = Backend()
        val index = AtomicInteger()
        val endpoint = IndependentVideoEndpoint(backendFactory = { _, _, _ -> if(index.getAndIncrement()==0)old else next })
        val t1 = SurfaceTexture(1); val t2 = SurfaceTexture(2)
        val s1 = Surface(t1); val s2 = Surface(t2)
        val gate = CountDownLatch(1)
        try {
            endpoint.attachSurface(s1)
            endpoint.onCodec(1)
            endpoint.onConfig(byteArrayOf(0,0,0,1,0x40,1,2))
            assertTrue(old.configured.await(3, TimeUnit.SECONDS))
            assertEquals(CodecType.HEVC, old.codec)
            old.releaseGate = gate
            endpoint.attachSurface(s2)
            val ticket = endpoint.detachSurface(s1)
            assertEquals(ReleaseState.PENDING, ticket.awaitUntil(System.nanoTime()+20_000_000))
            gate.countDown()
            assertEquals(ReleaseState.RELEASED, ticket.awaitUntil(System.nanoTime()+3_000_000_000))
            assertTrue(next.configured.await(3, TimeUnit.SECONDS))
            assertEquals(0, next.releaseCount.get())
        } finally {
            gate.countDown(); endpoint.close(); assertTrue(endpoint.awaitReleased(3000))
            s1.release(); s2.release(); t1.release(); t2.release()
        }
    }

    @Test fun failedConfigureDoesNotFallBackToLegacyAndReleasesPartialBackend() {
        val backend = Backend()
        val failed = CountDownLatch(1)
        val endpoint = IndependentVideoEndpoint(backendFactory = { _, _, _ -> object : VideoBackend by backend {
            override fun configure(config: VideoStreamConfig, target: OutputTarget, options: BackendOptions) =
                ConfigureResult.Failed(IllegalStateException("configure_probe"))
        } }, diagnostic = { if(it.contains("state=stopped"))failed.countDown() })
        val texture = SurfaceTexture(1); val surface = Surface(texture)
        try {
            endpoint.attachSurface(surface)
            endpoint.onConfig(byteArrayOf(0,0,0,1,0x67,66,0,31))
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertTrue(endpoint.snapshot().lastError!!.contains("configure_probe"))
            assertNull(endpoint.snapshot().actualBackend)
            assertEquals(1, backend.releaseCount.get())
        } finally { endpoint.close(); assertTrue(endpoint.awaitReleased(3000)); surface.release(); texture.release() }
    }

    @Test fun stoppedStreamRetiresDecoderAndIdenticalConfigCanStartANewStream() {
        val first = Backend(); val second = Backend(); val index = AtomicInteger()
        val stopped = CountDownLatch(1)
        val endpoint = IndependentVideoEndpoint(backendFactory = { _, _, _ -> if(index.getAndIncrement()==0)first else second },
            diagnostic = { if(it.contains("state=stream_stopped"))stopped.countDown() })
        val texture = SurfaceTexture(1); val surface = Surface(texture)
        val config = byteArrayOf(0,0,0,1,0x67,66,0,31)
        try {
            endpoint.attachSurface(surface); endpoint.onConfig(config)
            assertTrue(first.configured.await(3, TimeUnit.SECONDS))
            endpoint.onStreamActive(false)
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            assertEquals(1, first.releaseCount.get())
            assertNull(endpoint.sourceDescription())
            endpoint.onStreamActive(true); endpoint.onConfig(config)
            assertTrue(second.configured.await(3, TimeUnit.SECONDS))
            endpoint.onFrame(byteArrayOf(0,0,0,1,0x65,1,2,3))
            assertTrue(second.displayed.await(3, TimeUnit.SECONDS))
        } finally { endpoint.close(); assertTrue(endpoint.awaitReleased(3000)); surface.release(); texture.release() }
    }
}
