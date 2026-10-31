package com.shilapi.xcertplay.airplay

import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AirPlaySessionCloseTest {
    // Restoring media-before-socket close makes this hang: the media owner cannot
    // leave its callback until Socket.close unblocks its command write.
    @Test fun closeUnblocksAnEventWriterBeforeWaitingForMediaRetirement() {
        val event = BlockedEventSocket()
        val mediaLock = Any()
        val ended = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val session = session(object : AirPlayMediaHandler {
            override fun onSessionClosed(session: AirPlaySession) { synchronized(mediaLock) {} }
        }, object : AirPlaySessionListener {
            override fun onSessionEnded(session: AirPlaySession) { ended.incrementAndGet() }
        })
        wire(session, event)
        val writer = worker(failure) { synchronized(mediaLock) { session.sendCommand(mapOf("type" to "forceKeyFrame")) } }
        var closer: Thread? = null
        try {
            assertTrue("Event write did not start", event.writing.await(5, TimeUnit.SECONDS))
            closer = worker(failure) { session.close() }
            closer.join(3000)
            assertFalse("Close waited on media before unblocking the event socket", closer.isAlive)
            writer.join(3000)
            assertFalse("The old command writer is still alive", writer.isAlive)
            assertTrue(event.closed.count == 0L)
            assertEquals(1, ended.get())
            session.close()
            assertEquals(1, ended.get())
            failure.get()?.let { throw AssertionError("Worker failed", it) }
        } finally {
            event.close()
            writer.join(3000)
            closer?.join(3000)
            session.close()
        }
    }

    // A write failure must not call media cleanup while retaining eventWriteLock.
    // Here a media callback already owns its lock and needs eventWriteLock to exit.
    @Test fun failedWriteReleasesTheWriteLockBeforeClosingMedia() {
        val event = BlockedEventSocket()
        val mediaLock = Any()
        val mediaEntered = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val session = session(object : AirPlayMediaHandler {
            override fun onSessionClosed(session: AirPlaySession) {
                cleanupEntered.countDown()
                synchronized(mediaLock) {}
            }
        })
        wire(session, event)
        val writer = worker(failure) { session.sendCommand(mapOf("type" to "hidSendReport")) }
        var callback: Thread? = null
        try {
            assertTrue(event.writing.await(5, TimeUnit.SECONDS))
            callback = worker(failure) {
                synchronized(mediaLock) {
                    mediaEntered.countDown()
                    session.sendCommand(mapOf("type" to "forceKeyFrame"))
                }
            }
            assertTrue(mediaEntered.await(5, TimeUnit.SECONDS))
            event.close() // Forces the first write to throw, as on a disconnected phone.
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            writer.join(3000)
            callback.join(3000)
            assertFalse("Write failure closed media while holding eventWriteLock", writer.isAlive)
            assertFalse("Media callback cannot leave its event command", callback.isAlive)
            assertTrue(session.isClosed)
            failure.get()?.let { throw AssertionError("Worker failed", it) }
        } finally {
            event.close()
            writer.join(3000)
            callback?.join(3000)
            session.close()
        }
    }

    @Test fun closeUnblocksAvailabilityWriteBeforeWaitingForAvailabilityState() {
        val event = BlockedEventSocket()
        val failure = AtomicReference<Throwable>()
        val session = session()
        wire(session, event)
        val availability = field(session, "videoPlaybackAvailability") as VideoPlaybackAvailability
        availability.setFeatureEnabled(true)
        availability.setEventReady(true)
        val writer = worker(failure) { session.setVideoPlaybackAllowed(true) }
        var closer: Thread? = null
        try {
            assertTrue(event.writing.await(5, TimeUnit.SECONDS))
            closer = worker(failure) { session.close() }
            closer.join(3000)
            writer.join(3000)
            assertFalse("Close waited for availability before closing its socket", closer.isAlive)
            assertFalse(writer.isAlive)
            failure.get()?.let { throw AssertionError("Worker failed", it) }
        } finally {
            event.close()
            writer.join(3000)
            closer?.join(3000)
            session.close()
        }
    }

    @Test fun eventAcceptedDuringCloseCannotReattachToTheRetiredSession() {
        val accepted = CountDownLatch(1)
        val resumeAccept = CountDownLatch(1)
        val eventClosed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val event = object : Socket() {
            override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
            override fun setSoLinger(on: Boolean, linger: Int) {}
            override fun setTcpNoDelay(on: Boolean) {}
            override fun getTcpNoDelay() = true
            override fun close() { eventClosed.countDown() }
        }
        val server = object : ServerSocket() { override fun accept(): Socket = event }
        val session = session(listener = object : AirPlaySessionListener {
            override fun onDebugLog(message: String) {
                if (message.startsWith("airplay event connection accepted")) {
                    accepted.countDown()
                    check(resumeAccept.await(10, TimeUnit.SECONDS))
                }
            }
        })
        val method = AirPlaySession::class.java.getDeclaredMethod("acceptEvent", ServerSocket::class.java)
            .apply { isAccessible = true }
        val acceptor = worker(failure) { method.invoke(session, server) }
        try {
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            session.close()
            resumeAccept.countDown()
            acceptor.join(3000)
            assertFalse(acceptor.isAlive)
            assertTrue("Late accepted socket was leaked after close", eventClosed.count == 0L)
            assertNull("A retired session retained its event socket", field(session, "eventSocket"))
            assertNull(field(session, "eventCipher"))
            failure.get()?.let { throw AssertionError("Worker failed", it) }
        } finally {
            resumeAccept.countDown()
            event.close()
            server.close()
            acceptor.join(3000)
            session.close()
        }
    }

    private fun session(media: AirPlayMediaHandler = object : AirPlayMediaHandler {},
        listener: AirPlaySessionListener = object : AirPlaySessionListener {}) = AirPlaySession(
        object : Socket() {
            override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
        }, AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
            main = AirPlayDisplayConfig(800, 480)), AirPlayIdentity.generate(), PairingStore(),
        null, listener, media)

    private fun field(session: AirPlaySession, name: String): Any? =
        AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }.get(session)

    private fun wire(session: AirPlaySession, event: Socket) {
        for ((name, value) in listOf("eventSocket" to event,
            "eventCipher" to ControlCipher(ByteArray(32), ByteArray(32)))) {
            AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
    }

    private fun worker(failure: AtomicReference<Throwable>, action: () -> Unit) =
        Thread({ try { action() } catch (error: Throwable) { failure.compareAndSet(null, error) } },
            "session-close-regression").apply { isDaemon = true; start() }

    private class BlockedEventSocket : Socket() {
        val writing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) = block()
            override fun write(bytes: ByteArray, offset: Int, length: Int) = block()
            private fun block() {
                writing.countDown()
                check(closed.await(10, TimeUnit.SECONDS)) { "Test did not close event socket" }
                throw IOException("event socket closed during write")
            }
        }
        override fun close() { closed.countDown() }
    }
}
