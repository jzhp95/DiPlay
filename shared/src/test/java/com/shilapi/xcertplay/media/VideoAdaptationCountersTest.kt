package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoAdaptationCountersTest {
    @Test fun receiveByteTotalsCountActualMainVideoPayloadsWithoutReencoding() {
        val sink = AndroidMediaSink()
        try {
            sink.onVideoFrame(110, ByteArray(256))
            sink.onVideoFrame(110, ByteArray(512))
            val counters = requireNotNull(sink.liveVideoCounters())
            assertEquals(2L, counters.received)
            assertEquals(768L, counters.receivedBytes)
        } finally { sink.close(); assertTrue(sink.awaitVideoReleased(5000)) }
    }
}
