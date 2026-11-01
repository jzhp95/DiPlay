package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionVideoRouterTest {

    private class MockEndpoint(override val streamRole: Int) : SessionVideoEndpoint {
        override val implementation: VideoImplementation = VideoImplementation.INDEPENDENT
        var configsReceived = 0
        var framesReceived = 0
        var closed = false

        override fun onConfig(configBytes: ByteArray) { configsReceived++ }
        override fun onCodec(codec: Int) {}
        override fun onFrame(frameBytes: ByteArray, ptsUs: Long, isKey: Boolean) { framesReceived++ }
        override fun attachSurface(surface: android.view.Surface) {}
        override fun detachSurface(): ReleaseTicket = SettableReleaseTicket(ReleaseState.RELEASED)
        override fun snapshot(): RuntimeSnapshot = RuntimeSnapshot(
            sessionId = "mock",
            streamRole = streamRole,
            requestedImplementation = VideoImplementation.INDEPENDENT,
            actualImplementation = VideoImplementation.INDEPENDENT
        )
        override fun close() { closed = true }
    }

    @Test
    fun routesMainScreenAndLazilyConstructsMirrors() {
        val mainEp = MockEndpoint(110)
        var mirrorCreatedCount = 0

        val router = SessionVideoRouter(mainEp) { role ->
            mirrorCreatedCount++
            MockEndpoint(role)
        }

        // 1. Main endpoint is immediately available, mirror not yet created
        assertSame(mainEp, router.getEndpoint(110))
        assertEquals(0, mirrorCreatedCount)

        // 2. Deliver frame to main screen
        router.onFrame(110, byteArrayOf(1, 2, 3))
        assertEquals(1, mainEp.framesReceived)

        // 3. Mirror endpoint is lazily constructed on first access
        val mirrorEp = router.getEndpoint(111) as MockEndpoint
        assertEquals(1, mirrorCreatedCount)
        assertEquals(111, mirrorEp.streamRole)

        // 4. Frames are strictly isolated by role
        router.onFrame(111, byteArrayOf(4, 5))
        assertEquals(1, mainEp.framesReceived)
        assertEquals(1, mirrorEp.framesReceived)

        // 5. Closing router closes all endpoints
        router.close()
        assertTrue(mainEp.closed)
        assertTrue(mirrorEp.closed)
    }
}
