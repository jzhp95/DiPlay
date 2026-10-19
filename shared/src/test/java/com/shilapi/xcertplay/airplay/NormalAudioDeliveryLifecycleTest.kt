package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.net.SocketAddress
import java.net.InetSocketAddress
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class NormalAudioDeliveryLifecycleTest {
    @Test fun teardownWaitsForEnteredDeliveryBeforeFinalSinkStop() = exerciseRetirement("teardown")
    @Test fun sessionCloseWaitsForEnteredDeliveryBeforeFinalSinkStop() = exerciseRetirement("close")
    @Test fun replacementWaitsForEnteredDeliveryBeforeFinalSinkStop() = exerciseRetirement("replace")

    private fun exerciseRetirement(kind: String) {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val retired = CountDownLatch(1); val attempting = CountDownLatch(1)
        val stopped = AtomicBoolean(false); val stale = AtomicBoolean(false)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
                entered.countDown()
                while (resume.count > 0) { try { resume.await() } catch (_: InterruptedException) {} }
            }
            override fun onAudioStopped(id: AudioStreamId) { stopped.set(true) }
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { if (stopped.get()) stale.set(true) }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { if (stopped.get()) stale.set(true) }
        }, microphoneEnabled = true)
        val live = session(); val replacement = session()
        var retireThread: Thread? = null
        try {
            val ports = engine.onAudio(live, 100, setup())!!
            val audio = audio(engine, live)
            send(audio, ports["dataPort"] as Int)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            retireThread = Thread {
                attempting.countDown()
                try {
                    when (kind) {
                        "teardown" -> engine.onTeardown(live, 100)
                        "close" -> engine.onSessionClosed(live)
                        else -> engine.onAudio(replacement, 100, setup())
                    }
                } finally { retired.countDown() }
            }.apply { start() }
            assertTrue(attempting.await(1, TimeUnit.SECONDS))
            val stoppedDuringDelivery = retired.await(100, TimeUnit.MILLISECONDS)
            resume.countDown()
            assertTrue(retired.await(3, TimeUnit.SECONDS))
            rxThread(audio).join(1_000)
            assertFalse("Retirement must await an entered sink callback", stoppedDuringDelivery)
            assertFalse("Retired RTP/microphone callback reached a stopped sink", stale.get())
        } finally {
            resume.countDown(); retireThread?.join(3_000)
            engine.onSessionClosed(live); engine.onSessionClosed(replacement); live.close(); replacement.close()
        }
    }

    @Test fun reentrantTeardownInFirstCallbackCannotStartMicrophoneOrDeliverRtp() {
        lateinit var engine: CarPlayMediaEngine
        val live = session(); val entered = CountDownLatch(1)
        val microphone = AtomicInteger(); val rtpPackets = AtomicInteger()
        engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
                engine.onTeardown(live, 100)
                entered.countDown()
            }
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { microphone.incrementAndGet() }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { rtpPackets.incrementAndGet() }
        }, microphoneEnabled = true)
        try {
            val ports = engine.onAudio(live, 100, setup())!!
            val audio = audio(engine, live)
            send(audio, ports["dataPort"] as Int)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            rxThread(audio).join(1_000)
            assertEquals(0, microphone.get())
            assertEquals(0, rtpPackets.get())
        } finally { engine.onSessionClosed(live); live.close() }
    }

    @Test fun cancelledSilentStartCannotStartMicrophoneAfterSessionClose() {
        val started = CountDownLatch(1)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { started.countDown() }
        }, microphoneEnabled = true)
        val live = session()
        try {
            engine.onAudio(live, 100, setup() + ("audioType" to "default"))
            engine.onSessionClosed(live)
            assertFalse(started.await(700, TimeUnit.MILLISECONDS))
        } finally { engine.onSessionClosed(live); live.close() }
    }

    private fun audio(engine: CarPlayMediaEngine, session: AirPlaySession): AudioStream {
        val streams = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }.get(engine) as Map<*, *>
        return streams[CarPlayMediaEngine.StreamKey(session, 100, "telephony")] as AudioStream
    }
    private fun rxThread(audio: AudioStream) = AudioStream::class.java.getDeclaredField("dataThread").apply { isAccessible = true }.get(audio) as Thread
    private fun send(audio: AudioStream, port: Int) {
        val key = AudioStream::class.java.getDeclaredField("key").apply { isAccessible = true }.get(audio) as ByteArray
        val header = ByteBuffer.allocate(12).apply { put(0x80.toByte()); put(0x60.toByte()); putShort(1); putInt(1); putInt(0) }.array()
        val wire = header + AirPlayCrypto.chachaSeal(key, ByteArray(12), byteArrayOf(0x21), header.copyOfRange(4, 12)) + ByteArray(8)
        DatagramSocket().use { it.send(DatagramPacket(wire, wire.size, InetAddress.getLoopbackAddress(), port)) }
    }
    private fun setup(): Map<String, Any?> = mapOf("audioType" to "telephony", "audioFormat" to 0x8000L, "streamConnectionID" to 42L, "input" to true, "dataPort" to 3333)
    private fun session(): AirPlaySession {
        val session = AirPlaySession(object : Socket() { override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress("127.0.0.1", 1234) },
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }.set(session.pairVerify, ByteArray(32).also(SecureRandom()::nextBytes))
        return session
    }
}
