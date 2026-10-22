package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler

/** One owned focus request; request and abandon always use the same platform object. */
class AudioFocusRequestHandle(
    val gain: Int,
    val attributes: AudioAttributes,
    listener: AudioManager.OnAudioFocusChangeListener,
    handler: Handler,
) {
    private val platformRequest = AudioFocusRequest.Builder(gain)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(listener, handler)
        .build()

    fun request(manager: AudioManager): Int = manager.requestAudioFocus(platformRequest)
    fun abandon(manager: AudioManager): Int = manager.abandonAudioFocusRequest(platformRequest)
}
