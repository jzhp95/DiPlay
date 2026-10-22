package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioFocusRequestHandleTest {
    private val manager get() =
        RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val changes = mutableListOf<Int>()
    private val request = AudioFocusRequestHandle(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build(),
        { change -> changes += change },
        Handler(Looper.getMainLooper()),
    )


    @Config(sdk = [28])
    @Test fun android9UsesTheSameAudioFocusRequestForAcquireAndAbandon() {
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, request.request(manager))
        val sent = shadowOf(manager).lastAudioFocusRequest.audioFocusRequest
        assertNotNull(sent)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, sent.focusGain)

        request.abandon(manager)
        assertSame(sent, shadowOf(manager).lastAbandonedAudioFocusRequest)
    }
}
