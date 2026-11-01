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
        // Router with main endpoint using INDEPENDENT pipeline
        val mainEndpoint = IndependentVideoEndpoint(
            streamRole = 110,
            sessionId = "iso-session-1"
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

        val snap = mainEndpoint.snapshot()
        assertEquals(VideoImplementation.INDEPENDENT, snap.actualImplementation)
        assertNotNull(snap.actualBackend)

        val detachTicket = router.detachSurface(110)
        assertTrue(detachTicket.state() != ReleaseState.BLOCKED)

        router.close()
    }
}
