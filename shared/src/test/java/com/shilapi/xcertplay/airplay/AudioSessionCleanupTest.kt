package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AudioSessionCleanupTest {
    @Test fun closingProbePreservesLiveFeedback() = withSessions { engine, live, probe, _ ->
        assertNotNull(engine.onAudio(live, 100, setup("media")))
        assertNotNull(engine.onFeedback(live))
        engine.onSessionClosed(probe)
        assertNotNull(engine.onFeedback(live))
    }

    @Test fun feedbackContainsOnlyTheRequestingSessionsStreams() = withSessions { engine, live, other, _ ->
        engine.onAudio(live, 100, setup("media"))
        engine.onAudio(other, 102, setup("default"))
        fun types(session: AirPlaySession) = (engine.onFeedback(session)!!["streams"] as List<*>).map { (it as Map<*, *>)["type"] }
        assertEquals(listOf(100), types(live))
        assertEquals(listOf(102), types(other))
    }

    @Test fun closingOwnSessionStopsItsAudioExactlyOnce() = withSessions { engine, live, _, sink ->
        engine.onAudio(live, 100, setup("media"))
        sink.audioStopped.clear()
        engine.onSessionClosed(live)
        engine.onSessionClosed(live)
        assertEquals(listOf(AudioStreamId(100, "media")), sink.audioStopped.toList())
        assertNull(engine.onFeedback(live))
    }

    @Test fun oldSessionCloseAndTeardownCannotStopReplacementWithSameAudioId() = withSessions { engine, old, replacement, sink ->
        engine.onAudio(old, 100, setup("media"))
        engine.onAudio(replacement, 100, setup("media"))
        sink.audioStopped.clear()
        engine.onTeardown(old, 100)
        engine.onSessionClosed(old)
        assertTrue(sink.audioStopped.isEmpty())
        assertNotNull(engine.onFeedback(replacement))
        engine.onSessionClosed(replacement)
        assertEquals(listOf(AudioStreamId(100, "media")), sink.audioStopped.toList())
    }

    @Test fun failedReplacementDoesNotDestroyTheAcceptedStream() = withSessions { engine, live, _, sink ->
        engine.onAudio(live, 100, setup("media"))
        sink.audioStopped.clear()
        assertNull(engine.onAudio(live, 100, setup("media") + ("streamConnectionID" to null)))
        assertNotNull(engine.onFeedback(live))
        assertTrue(sink.audioStopped.isEmpty())
    }

    @Test fun closingSessionStopsItsStartedMicrophone() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val live = session()
        try {
            assertNotNull(engine.onAudio(live, 100, setup("default") + mapOf("input" to true, "dataPort" to 3333)))
            assertTrue("Silent input should have started", sink.microphoneStarted.await(2, TimeUnit.SECONDS))
            engine.onSessionClosed(live)
            engine.onSessionClosed(live)
            assertEquals(listOf(AudioStreamId(100, "default")), sink.microphoneStopped.toList())
        } finally { engine.onSessionClosed(live); live.close() }
    }

    @Test fun reentrantSetupDuringRetirementIsDeclinedWithoutLeakingARegistration() {
        lateinit var engine: CarPlayMediaEngine
        val live = session()
        var armed = false
        var nested: Map<String, Any?>? = null
        engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) {
                if (armed) { armed = false; nested = engine.onAudio(live, 100, setup("media")) }
            }
        })
        try {
            engine.onAudio(live, 100, setup("media"))
            armed = true
            assertNotNull(engine.onAudio(live, 100, setup("media")))
            assertNull("Retirement must not publish a nested same-ID replacement", nested)
        } finally { engine.onSessionClosed(live); live.close() }
    }

    @Test fun sessionClosedFromRetirementCallbackCannotPublishItsCandidate() {
        lateinit var engine: CarPlayMediaEngine
        val old = session(); val candidate = session()
        engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { engine.onSessionClosed(candidate) }
        })
        try {
            engine.onAudio(old, 100, setup("media"))
            assertNull(engine.onAudio(candidate, 100, setup("media")))
            assertNull(engine.onFeedback(candidate))
        } finally { engine.onSessionClosed(old); engine.onSessionClosed(candidate); old.close(); candidate.close() }
    }

    @Test fun teardownSnapshotCannotRetireAnotherTypesNewRegistration() {
        lateinit var engine: CarPlayMediaEngine
        val live = session(); var armed = false
        var replacement: Map<String, Any?>? = null
        engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) {
                if (armed) {
                    armed = false
                    val other = if (id.audioType == "media") "default" else "media"
                    replacement = engine.onAudio(live, 100, setup(other))
                }
            }
        })
        try {
            engine.onAudio(live, 100, setup("media"))
            engine.onAudio(live, 100, setup("default"))
            armed = true
            engine.onTeardown(live, 100)
            assertNotNull("Callback installed a distinct new registration", replacement)
            assertNotNull("A retirement snapshot must not target a newer generation", engine.onFeedback(live))
        } finally { engine.onSessionClosed(live); live.close() }
    }

    private fun withSessions(block: (CarPlayMediaEngine, AirPlaySession, AirPlaySession, RecordingSink) -> Unit) {
        val sink = RecordingSink(); val engine = CarPlayMediaEngine(sink)
        val first = session(); val second = session()
        try { block(engine, first, second, sink) }
        finally { engine.onSessionClosed(first); engine.onSessionClosed(second); first.close(); second.close() }
    }

    private class RecordingSink : MediaSink {
        val audioStopped = CopyOnWriteArrayList<AudioStreamId>()
        val microphoneStopped = CopyOnWriteArrayList<AudioStreamId>()
        val microphoneStarted = CountDownLatch(1)
        override fun onAudioStopped(id: AudioStreamId) { audioStopped += id }
        override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { microphoneStarted.countDown() }
        override fun onMicrophoneStopped(id: AudioStreamId) { microphoneStopped += id }
    }

    private fun setup(audioType: String): Map<String, Any?> = mapOf("audioType" to audioType, "audioFormat" to 0x8000L, "streamConnectionID" to 42L)
    private fun session(): AirPlaySession {
        val session = AirPlaySession(object : Socket() {
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress("127.0.0.1", 1234)
        }, AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, ByteArray(32).also(SecureRandom()::nextBytes))
        return session
    }
}
