package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayVideoStreamOwnershipTest {
    @Test fun oldSessionCloseKeepsTheReplacementVideoConfiguration() {
        val actual = com.shilapi.xcertplay.media.AndroidMediaSink(surface = null)
        val inactive = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val engine = CarPlayMediaEngine(object : MediaSink by actual {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                if (!active) inactive.add(type)
                actual.onScreenStreamActive(type, active)
            }
        })
        val old = session(); val current = session()
        try {
            assertNotNull(engine.onScreen(old, 110, mapOf("streamConnectionID" to 42L)))
            assertNotNull(engine.onScreen(current, 110, mapOf("streamConnectionID" to 43L)))
            val config = byteArrayOf(1, 0x42, 0, 0x1e, 0xff.toByte(), 0xe1.toByte(), 0, 4,
                0x67, 0x42, 0xc0.toByte(), 0x1e, 1, 0, 2, 0x68, 0xce.toByte())
            actual.onVideoConfig(110, config)
            val field = actual.javaClass.getDeclaredField("lastVideoConfig").apply { isAccessible = true }
            assertTrue((field.get(actual) as Map<*, *>).containsKey(110))
            inactive.clear()
            engine.onSessionClosed(old)
            assertTrue("Old session must not erase the current session video config", (field.get(actual) as Map<*, *>).containsKey(110))
            assertTrue("Old session must not stop current video", inactive.isEmpty())
        } finally {
            engine.onSessionClosed(old); engine.onSessionClosed(current)
            old.close(); current.close(); actual.close(); actual.awaitVideoReleased(5000)
        }
    }

    @Test fun staleCallbacksCannotDeliverOrCloseASameSessionReplacement() {
        val frames = mutableListOf<Int>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { frames += type }
        })
        val session = session()
        try {
            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 42L)))
            val registry = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            val first = (registry.get(engine) as Map<*, *>).values.single() as ScreenStream
            val field = ScreenStream::class.java.getDeclaredField("listener").apply { isAccessible = true }
            val stale = field.get(first) as ScreenStream.Listener
            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 43L)))
            stale.onFrame(byteArrayOf(1))
            stale.onClosed(null)
            assertTrue(frames.isEmpty())
            assertFalse("A late EOF must not close the replacement session", session.isClosed)
            val current = (registry.get(engine) as Map<*, *>).values.single() as ScreenStream
            (field.get(current) as ScreenStream.Listener).onFrame(byteArrayOf(2))
            assertEquals(listOf(110), frames)
            engine.onSessionClosed(session)
            assertNull("A retired session cannot install another screen", engine.onScreen(session, 110, mapOf("streamConnectionID" to 44L)))
        } finally { engine.onSessionClosed(session); session.close() }
    }

    @Test fun oldTeardownDoesNotStopTheCurrentSessionOrAnotherScreenType() {
        val stopped = mutableListOf<Int>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) { if (!active) stopped += type }
        })
        val old = session(); val current = session()
        try {
            assertNotNull(engine.onScreen(old, 110, mapOf("streamConnectionID" to 42L)))
            assertNotNull(engine.onScreen(current, 110, mapOf("streamConnectionID" to 43L)))
            assertNotNull(engine.onScreen(current, 111, mapOf("streamConnectionID" to 44L)))
            engine.onTeardown(old, 110)
            assertTrue(stopped.isEmpty())
            engine.onTeardown(current, 110)
            assertEquals(listOf(110), stopped)
            engine.onSessionClosed(current)
            assertEquals(listOf(110, 111), stopped)
        } finally { engine.onSessionClosed(old); engine.onSessionClosed(current); old.close(); current.close() }
    }

    @Test fun eofReservesSessionRetirementBeforeAnInactiveCallbackCanReenterSetup() {
        lateinit var engine: CarPlayMediaEngine
        val session = session()
        var attempted = false
        var replacement: Int? = null
        engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                if (!active) {
                    attempted = true
                    replacement = engine.onScreen(session, type, mapOf("streamConnectionID" to 43L))
                }
            }
        })
        try {
            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 42L)))
            val registry = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            val screen = (registry.get(engine) as Map<*, *>).values.single() as ScreenStream
            val listener = ScreenStream::class.java.getDeclaredField("listener").apply { isAccessible = true }
                .get(screen) as ScreenStream.Listener
            listener.onClosed(null)
            assertTrue(attempted)
            assertNull(replacement)
            assertTrue(session.isClosed)
        } finally { engine.onSessionClosed(session); session.close() }
    }

    private fun session(): AirPlaySession {
        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
        )
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, secret)
        return session
    }
}
