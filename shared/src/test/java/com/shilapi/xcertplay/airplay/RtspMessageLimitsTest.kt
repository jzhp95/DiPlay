package com.shilapi.xcertplay.airplay

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class RtspMessageLimitsTest {
    @Test fun unterminatedOrCompletedOversizeHeadersAreRejected() {
        val header = ByteArray(64 * 1024 + 1) { 'A'.code.toByte() }
        assertThrows(IOException::class.java) { RtspMessage.parseMessages(header) }
        assertThrows(IOException::class.java) { RtspMessage.parseMessages(header + "\r\n\r\n".toByteArray()) }
    }

    @Test fun invalidOrExcessiveContentLengthsAreRejectedBeforeReceivingTheBody() {
        for (length in listOf("8388609", "2147483647", "9223372036854775808", "-1", "bad", "+1")) {
            assertThrows("Content-Length=$length", IOException::class.java) {
                RtspMessage.parseMessages("POST /pair-setup RTSP/1.0\r\nContent-Length: $length\r\n\r\n".toByteArray())
            }
        }
        val boundary = RtspMessage.parseMessages("POST /x RTSP/1.0\r\nContent-Length: 8388608\r\n\r\n".toByteArray())
        assertTrue(boundary.messages.isEmpty())
        assertTrue(boundary.rest.isNotEmpty())
    }

    @Test fun conflictingLengthHeadersAreRejected() {
        assertThrows(IOException::class.java) {
            RtspMessage.parseMessages("POST /x RTSP/1.0\r\nContent-Length: 2\r\ncontent-length: 3\r\n\r\nabc".toByteArray())
        }
    }

    @Test fun fragmentedLargeArtworkAndPipelinedRequestsRemainValid() {
        val body = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val head = "SET_PARAMETER /artwork RTSP/1.0\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray()
        val prefix = head + body.copyOfRange(0, body.size / 2)
        val partial = RtspMessage.parseMessages(prefix)
        assertTrue(partial.messages.isEmpty())
        assertArrayEquals(prefix, partial.rest)
        val next = "GET /info RTSP/1.0\r\n\r\n".toByteArray()
        val parsed = RtspMessage.parseMessages(partial.rest + body.copyOfRange(body.size / 2, body.size) + next)
        assertEquals(2, parsed.messages.size)
        assertArrayEquals(body, parsed.messages[0].body)
        assertEquals("/info", parsed.messages[1].path)
        assertTrue(parsed.rest.isEmpty())
    }

    @Test fun protocolViolationClosesOnlyTheOffendingControlConnection() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                val session = AirPlaySession(server.accept(),
                    AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(800, 480)),
                    AirPlayIdentity.generate(), PairingStore(), null,
                    object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
                try {
                    session.start()
                    client.soTimeout = 2_000
                    client.getOutputStream().write(ByteArray(64 * 1024 + 1) { 'A'.code.toByte() })
                    client.getOutputStream().flush()
                    assertEquals("Oversize unpaired header must close control socket", -1, client.getInputStream().read())
                } finally { session.close() }
            }
        }
    }
}
