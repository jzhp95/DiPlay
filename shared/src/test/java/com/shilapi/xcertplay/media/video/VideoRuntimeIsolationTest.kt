package com.shilapi.xcertplay.media.video

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoRuntimeIsolationTest {

    @Test
    fun independentEndpointOperatesSuccessfullyEvenIfLegacyFactoryFails() {
        val configured = java.util.concurrent.CountDownLatch(1)
        // Router with main endpoint using INDEPENDENT pipeline
        val mainEndpoint = IndependentVideoEndpoint(
            streamRole = 110,
            sessionId = "iso-session-1",
            backendFactory = { _, _, _ -> object : VideoBackend {
                override fun capabilities() = BackendCapabilities(BackendKind.PLATFORM_SURFACE, setOf(CodecType.H264), false, true)
                override fun configure(config: VideoStreamConfig, target: OutputTarget, options: BackendOptions) = ConfigureResult.Success("fake", capabilities())
                override fun trySubmit(unit: EncodedAccessUnit): OfferResult { unit.lease.close(); return OfferResult.ACCEPTED }
                override fun pollOutput(deadlineNs: Long) = BackendOutput.NoOutput
                override fun requestStop() {}
                override fun awaitReleased(deadlineNs: Long) = ReleaseState.RELEASED
            } },
            diagnostic = { if(it.contains("backend=PLATFORM_SURFACE generation="))configured.countDown() }
        )

        val router = SessionVideoRouter(mainEndpoint) {
            // Throw exception if any legacy mirror factory is mistakenly invoked for main screen
            throw AssertionError("Legacy factory must NEVER be called for independent main screen!")
        }

        // Send main screen config and frame
        router.onConfig(110, byteArrayOf(0, 0, 0, 1, 7, 66, -32, 31))
        router.onFrame(110, byteArrayOf(0, 0, 0, 1, 5, 1, 2, 3))

        val surface = Surface(SurfaceTexture(1))
        router.attachSurface(110, surface)
        assertTrue(configured.await(3, java.util.concurrent.TimeUnit.SECONDS))

        val snap = mainEndpoint.snapshot()
        assertEquals(VideoImplementation.INDEPENDENT, snap.actualImplementation)
        assertNotNull(snap.actualBackend)

        val detachTicket = router.detachSurface(110)
        assertEquals(ReleaseState.RELEASED, detachTicket.awaitUntil(System.nanoTime()+3_000_000_000L))

        router.close()
        assertTrue(mainEndpoint.awaitReleased(3000))
        surface.release()
    }
}
