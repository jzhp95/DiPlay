package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioManager
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeHfpSinkTest {
    @Test fun ringingIsSuppressedOnStartAndRtpUntilStopWithoutCreatingARenderer() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val logs = mutableListOf<String>()
        val sink = AndroidMediaSink(context = context, nativeHfpCalls = true, onAudioDiagnostic = logs::add)
        val id = AudioStreamId(100, "alert")
        val format = AudioFormat(AudioCodecKind.LPCM, 48000, 1, 100, "alert")
        try {
            manager.mode = AudioManager.MODE_RINGTONE
            sink.onAudioStarted(id, format, 0)
            manager.mode = AudioManager.MODE_NORMAL
            sink.onAudioRtp(id, format, byteArrayOf(), 0)
            val renderers = ReflectionHelpers.getField<Map<*, *>>(sink, "audioRenderers")
            assertTrue(renderers.isEmpty())
            assertEquals(1, logs.count { it.contains("抑制重复") })
            sink.onAudioStopped(id)
            assertFalse(ReflectionHelpers.getField<NativeHfpRingPolicy>(sink, "nativeHfpRing").contains(id))
        } finally { sink.close(); manager.mode = AudioManager.MODE_NORMAL }
    }
}
