package com.shilapi.xcertplay.media

import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.util.ArrayDeque

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [ExperimentalAudioOutputTest.FaultTrack::class])
class ExperimentalAudioOutputTest {
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")

    @Before fun reset() { FaultTrack.results.clear(); FaultTrack.writes.clear(); FaultTrack.released.clear(); FaultTrack.throwWrite = false; FaultTrack.throwPlay = false }
    private fun set(renderer: Any, name: String, value: Any?) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
    private fun get(renderer: Any, name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)
    private fun invoke(renderer: Any, name: String) = type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(renderer)
    private fun write(renderer: Any, data: ByteArray) = type.getDeclaredMethod("writePcm", ByteArray::class.java,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(renderer, data, 0, data.size)
    private fun pcm(vararg values: Int) = values.flatMap { listOf(it.toByte(), (it shr 8).toByte()) }.toByteArray()

    private fun withRenderer(config: ExperimentalAudioConfig = ExperimentalAudioConfig(outputRecovery = true),
        audioType: String = "media", payload: Int = 96, block: (AndroidMediaSink, Any, MutableList<String>) -> Unit) {
        val reports = mutableListOf<String>()
        val sink = AndroidMediaSink(experimentalAudio = config, onAudioDiagnostic = reports::add)
        val create = AndroidMediaSink::class.java.getDeclaredMethod("audioRenderer", AudioStreamId::class.java,
            AudioFormat::class.java).apply { isAccessible = true }
        val renderer = requireNotNull(create.invoke(sink, AudioStreamId(payload, audioType),
            AudioFormat(AudioCodecKind.LPCM, 48_000, 1, payload, audioType)))
        try {
            invoke(renderer, "createTrack")
            assertNotNull("Initial track", get(renderer, "track"))
            set(renderer, "fadeApplied", true)
            block(sink, renderer, reports)
        } finally {
            sink.close()
            invoke(renderer, "release")
            sink.awaitVideoReleased(5000)
        }
    }

    @Test fun deadTrackContinuesOnlyTheUnwrittenSuffixOnAFreshTrack() = withRenderer { _, renderer, reports ->
        val old = get(renderer, "track")
        FaultTrack.results.addAll(listOf(2, AudioTrack.ERROR_DEAD_OBJECT))
        write(renderer, pcm(100, 200, 300))
        assertNotSame(old, get(renderer, "track"))
        assertTrue(old in FaultTrack.released)
        assertEquals(listOf(0 to 2, 2 to -6, 2 to 4), FaultTrack.writes.map { it.offset to it.result })
        assertArrayEquals(pcm(200, 300), FaultTrack.writes.last().accepted)
        assertEquals(2L, get(renderer, "totalWrittenFrames"))
        assertEquals(1, reports.count { it.contains("output recovery ready") })
    }

    @Test fun disabledRecoveryDoesNotReplaceTheTrack() = withRenderer(ExperimentalAudioConfig()) { _, renderer, reports ->
        val old = get(renderer, "track")
        FaultTrack.results.add(AudioTrack.ERROR_DEAD_OBJECT)
        write(renderer, pcm(1, 2))
        assertSame(old, get(renderer, "track"))
        assertTrue(FaultTrack.released.isEmpty())
        assertTrue(reports.none { it.contains("output recovery") })
    }

    @Test fun recoveryStopsAfterTwoReplacementsEvenWhenEveryWriteFails() = withRenderer { _, renderer, reports ->
        repeat(10) { FaultTrack.results.add(AudioTrack.ERROR_DEAD_OBJECT) }
        write(renderer, pcm(1, 2))
        write(renderer, pcm(1, 2))
        assertEquals(2, reports.count { it.contains("output recovery attempt=") })
        assertEquals(1, reports.count { it.contains("output recovery exhausted") })
        assertEquals(4, FaultTrack.writes.size)
    }

    @Test fun badArgumentsAndClosedRendererNeverRebuild() = withRenderer { _, renderer, reports ->
        val old = get(renderer, "track")
        FaultTrack.results.add(AudioTrack.ERROR_BAD_VALUE)
        write(renderer, pcm(1))
        invoke(renderer, "close")
        FaultTrack.results.add(AudioTrack.ERROR_DEAD_OBJECT)
        write(renderer, pcm(1))
        assertSame(old, get(renderer, "track"))
        assertTrue(reports.none { it.contains("output recovery attempt") })
    }

    @Test fun threeZeroWritesTriggerOneRecoveryAndSuccessClearsTheStall() = withRenderer { _, renderer, reports ->
        repeat(3) { FaultTrack.results.add(0) }
        repeat(3) { write(renderer, pcm(100)) }
        assertEquals(1, reports.count { it.contains("output recovery ready") })
        assertEquals(4, FaultTrack.writes.size)
        assertTrue(FaultTrack.writes.last().result > 0)
    }

    @Test fun illegalStateInWriteIsRecovered() = withRenderer { _, renderer, reports ->
        FaultTrack.throwWrite = true
        write(renderer, pcm(100, 200))
        assertEquals(1, reports.count { it.contains("output recovery ready") })
        assertTrue(reports.any { it.contains("reason=write-exception") })
        assertArrayEquals(pcm(100, 200), FaultTrack.writes.single().accepted)
    }

    @Test fun playFailureDoesNotReplayAlreadyAcceptedPcm() = withRenderer { _, renderer, reports ->
        set(renderer, "startThresholdBytes", 2)
        FaultTrack.throwPlay = true
        write(renderer, pcm(100, 200))
        assertEquals(1, FaultTrack.writes.size)
        assertTrue(reports.any { it.contains("reason=playback-exception") })
        assertEquals(0L, get(renderer, "totalWrittenFrames"))
    }

    @Test fun navigationCombinesAttenuationBeforeClippingAndCallsDoNotTouchOtherChannels() {
        val config = ExperimentalAudioConfig(navigationEnabled = true, navigationDb = 12, callEnabled = true, callDb = 6)
        for ((channel, audioType) in listOf(AudioChannel.NAVIGATION to "default", AudioChannel.PHONE to "telephony",
                AudioChannel.MEDIA to "media", AudioChannel.ASSISTANT to "speechrecognition")) {
            FaultTrack.writes.clear()
            withRenderer(config, audioType) { sink, renderer, _ ->
                set(renderer, "mappedChannel", channel)
                sink.setNavigationAttenuationDb(-12)
                write(renderer, pcm(20000, -20000))
                val expected = when (channel) {
                    AudioChannel.PHONE -> pcm(32767, -32768)
                    else -> pcm(20000, -20000)
                }
                assertArrayEquals(channel.name, expected, FaultTrack.writes.single().accepted)
            }
        }
    }

    data class Write(val offset: Int, val result: Int, val accepted: ByteArray)
    @Implements(AudioTrack::class)
    class FaultTrack {
        @RealObject private lateinit var track: AudioTrack
        @Implementation fun getState() = AudioTrack.STATE_INITIALIZED
        @Implementation fun getBufferSizeInFrames() = 2048
        @Implementation fun getPlaybackHeadPosition() = 0
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun getSampleRate() = 48000
        @Implementation fun play() { if (throwPlay) { throwPlay = false; throw IllegalStateException("track died") } }
        @Implementation fun pause() {}
        @Implementation fun flush() {}
        @Implementation fun release() { released.add(track) }
        @Implementation fun write(data: ByteArray, offset: Int, size: Int, mode: Int): Int {
            if (throwWrite) { throwWrite = false; throw IllegalStateException("track died") }
            val count = if (results.isEmpty()) size else results.removeFirst()
            writes.add(Write(offset, count, if (count > 0) data.copyOfRange(offset, offset + count) else byteArrayOf()))
            return count
        }
        companion object {
            val results = ArrayDeque<Int>()
            val writes = mutableListOf<Write>()
            val released = mutableListOf<AudioTrack>()
            var throwWrite = false
            var throwPlay = false
            @Implementation @JvmStatic fun getMinBufferSize(rate: Int, channels: Int, encoding: Int) = 4096
        }
    }
}
