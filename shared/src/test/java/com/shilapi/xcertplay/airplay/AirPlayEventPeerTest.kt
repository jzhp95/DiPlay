package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayEventPeerTest {
    @org.junit.Before fun needsIpv6Loopback() {
        org.junit.Assume.assumeTrue("no IPv6 loopback", runCatching {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("::1")).use { true }
        }.getOrDefault(false))
    }

    private val logs = CopyOnWriteArrayList<String>()

    private fun openEvent(session: AirPlaySession): Int {
        val method = AirPlaySession::class.java.getDeclaredMethod("openEvent")
        method.isAccessible = true
        return method.invoke(session) as Int
    }

    /** A session whose control peer is 127.0.0.1. */
    private fun withSession(block: (AirPlaySession) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { control ->
            val client = Socket(InetAddress.getByName("127.0.0.1"), control.localPort)
            val accepted = control.accept()
            val session = AirPlaySession(
                socket = client,
                config = AirPlayConfig(
                    deviceName = "test",
                    deviceId = "02:00:00:00:00:02",
                    btMac = "02:00:00:00:00:01",
                    sourceVersion = "1.0",
                    main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
                ),
                identity = AirPlayIdentity.generate(),
                pairings = PairingStore(),
                mfi = null,
                listener = object : AirPlaySessionListener {
                    override fun onDebugLog(message: String) { logs += message }
                },
                media = object : AirPlayMediaHandler {},
            )
            try { block(session) } finally { session.close(); accepted.close() }
        }
    }

    @Test
    fun aStrangerCannotTakeTheEventSlot() = withSession { session ->
        val port = openEvent(session)
        val stranger = Socket(InetAddress.getByName("::1"), port)
        try {
            val eof = try { stranger.getInputStream().read() } catch (_: java.net.SocketException) { -1 }
            org.junit.Assert.assertEquals("stranger connection should be closed by server immediately", -1, eof)
        } finally {
            stranger.close()
        }

        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && logs.none { it.contains("airplay event rejected sender outside the control session") }) {
            Thread.sleep(10)
        }
        assertTrue("rejection log was not recorded: $logs",
            logs.any { it.contains("airplay event rejected sender outside the control session") })
        assertTrue("stranger was accepted: $logs", logs.none { it.contains("accepted from /0:0:0:0:0:0:0:1") })

        var controlSocket: Socket? = null
        var lastError: Exception? = null
        val deadlineConnect = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadlineConnect) {
            try {
                controlSocket = Socket(InetAddress.getByName("127.0.0.1"), port)
                break
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(50)
            }
        }
        if (controlSocket == null) {
            fail("control peer could not connect after stranger was rejected: $lastError")
        }
        controlSocket.use { Thread.sleep(300) }
        assertTrue("control peer was not accepted: $logs", logs.any { it.contains("accepted from /127.0.0.1") })
    }

    @Test
    fun theControlPeerIsAccepted() = withSession { session ->
        val port = openEvent(session)
        Socket(InetAddress.getByName("127.0.0.1"), port).use { Thread.sleep(500) }
        assertTrue("control peer was not accepted: $logs", logs.any { it.contains("accepted from /127.0.0.1") })
    }
}
