package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VideoRuntimeCoordinatorTest {

    private val clock = FakeClock(1_000_000L)

    private class MockBackend(
        val kind: BackendKind,
        val identity: RuntimeIdentity
    ) : VideoBackend {
        var configureCalls = 0
        var stopCalls = 0
        var targetSurface: Any? = null
        var shouldBlockRelease = false

        override fun capabilities(): BackendCapabilities =
            BackendCapabilities(kind, setOf(CodecType.H264), true, true)

        override fun configure(
            config: VideoStreamConfig,
            target: OutputTarget,
            options: BackendOptions
        ): ConfigureResult {
            configureCalls++
            targetSurface = target.surface
            return ConfigureResult.Success(identity.backendInstanceId, capabilities())
        }

        override fun trySubmit(unit: EncodedAccessUnit): OfferResult = OfferResult.ACCEPTED

        override fun pollOutput(deadlineNs: Long): BackendOutput = BackendOutput.NoOutput

        override fun requestStop() {
            stopCalls++
        }

        override fun awaitReleased(deadlineNs: Long): ReleaseState =
            if (shouldBlockRelease) ReleaseState.BLOCKED else ReleaseState.RELEASED
    }

    private class BackendTracker : BackendFactory {
        val createdBackends = mutableListOf<MockBackend>()
        var nextBackendBlocksRelease = false

        override fun createBackend(kind: BackendKind, identity: RuntimeIdentity): VideoBackend {
            val backend = MockBackend(kind, identity).apply {
                shouldBlockRelease = nextBackendBlocksRelease
            }
            createdBackends.add(backend)
            return backend
        }
    }

    @Test
    fun attachSessionResetsStateAndRetiresOldBackend() {
        val tracker = BackendTracker()
        val coordinator = VideoRuntimeCoordinator(
            RuntimeDependencies(clock = clock, backendFactory = tracker)
        )

        coordinator.attachSession(SessionVideoId("sess-1", 110, 1L))
        coordinator.onConfig(
            VideoStreamConfig(SessionVideoId("sess-1"), CodecType.H264, 1280, 720)
        )
        assertEquals(1, tracker.createdBackends.size)

        // Attach new session -> retires sess-1 backend
        coordinator.attachSession(SessionVideoId("sess-2", 110, 2L))
        assertEquals(1, tracker.createdBackends[0].stopCalls)

        val snap = coordinator.snapshot()
        assertEquals("sess-2", snap.sessionId)
        assertEquals(0L, snap.configGeneration)
    }

    @Test
    fun onConfigBuildsBarrierAndDiscardsOldLeasesSafely() {
        val tracker = BackendTracker()
        val coordinator = VideoRuntimeCoordinator(
            RuntimeDependencies(clock = clock, backendFactory = tracker)
        )

        coordinator.attachSession(SessionVideoId("sess-1", 110, 1L))
        val config1 = VideoStreamConfig(
            SessionVideoId("sess-1"),
            CodecType.H264,
            1280,
            720,
            csdData = listOf(byteArrayOf(1, 2, 3))
        )
        coordinator.onConfig(config1)
        assertEquals(1L, coordinator.snapshot().configGeneration)

        // Offer 2 frames under config 1
        val lease1 = CountingBufferLease(16)
        val lease2 = CountingBufferLease(16)
        assertEquals(OfferResult.ACCEPTED, coordinator.tryOfferAccessUnit(testUnit(lease = lease1, configGeneration = 1L)))
        assertEquals(OfferResult.ACCEPTED, coordinator.tryOfferAccessUnit(testUnit(lease = lease2, configGeneration = 1L)))
        assertEquals(2, coordinator.snapshot().inputQueueDepth)

        // New config arrives -> barrier established, old units discarded and closed
        val config2 = VideoStreamConfig(
            SessionVideoId("sess-1"),
            CodecType.H264,
            1920,
            1080,
            csdData = listOf(byteArrayOf(4, 5, 6))
        )
        coordinator.onConfig(config2)
        assertEquals(2L, coordinator.snapshot().configGeneration)
        assertEquals(0, coordinator.snapshot().inputQueueDepth)

        // Verify both leases were closed during barrier discard
        assertEquals(1, lease1.closeCalls)
        assertEquals(1, lease2.closeCalls)

        // Old config frame offered after barrier -> rejected
        val leaseOld = CountingBufferLease(16)
        val rejectOld = coordinator.tryOfferAccessUnit(testUnit(lease = leaseOld, configGeneration = 1L))
        assertEquals(OfferResult.REJECTED, rejectOld)
    }

    @Test
    fun stopDrainsQueueAndReturnsReleaseTicket() {
        val tracker = BackendTracker()
        val coordinator = VideoRuntimeCoordinator(
            RuntimeDependencies(clock = clock, backendFactory = tracker)
        )

        coordinator.attachSession(SessionVideoId("sess-1", 110, 1L))
        coordinator.onConfig(VideoStreamConfig(SessionVideoId("sess-1"), CodecType.H264, 1280, 720))

        val lease = CountingBufferLease(32)
        coordinator.tryOfferAccessUnit(testUnit(lease = lease, configGeneration = 1L))

        val ticket = coordinator.stop("user_disconnect")
        assertEquals(ReleaseState.RELEASED, ticket.state())
        assertEquals(1, tracker.createdBackends[0].stopCalls)
        assertEquals(1, lease.closeCalls)

        val snap = coordinator.snapshot()
        assertEquals(ReleaseState.RELEASED, snap.releaseState)
        assertEquals(0, snap.inputQueueDepth)
    }

    @Test
    fun stopWithHangingBackendReportsBlockedRelease() {
        val tracker = BackendTracker()
        tracker.nextBackendBlocksRelease = true
        val coordinator = VideoRuntimeCoordinator(
            RuntimeDependencies(clock = clock, backendFactory = tracker)
        )

        coordinator.attachSession(SessionVideoId("sess-1", 110, 1L))
        coordinator.onConfig(VideoStreamConfig(SessionVideoId("sess-1"), CodecType.H264, 1280, 720))

        val ticket = coordinator.stop("error")
        // Does not fake RELEASED
        assertEquals(ReleaseState.BLOCKED, ticket.state())
        assertEquals(ReleaseState.BLOCKED, coordinator.snapshot().releaseState)
    }
}
