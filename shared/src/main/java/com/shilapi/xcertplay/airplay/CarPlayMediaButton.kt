package com.shilapi.xcertplay.airplay

import android.view.KeyEvent

object CarPlayMediaButton {
    const val PLAY = 1
    const val PAUSE = 2
    const val PLAY_PAUSE = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    /** Flyme reference APK fallback for KEYCODE_R_VOICE_ASSIST. */
    const val KEYCODE_FLYME_VOICE_ASSIST = 200231

    fun isFlymeVoiceKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KEYCODE_FLYME_VOICE_ASSIST

    fun opensSiri(keyCode: Int): Boolean = isFlymeVoiceKey(keyCode)

    /** The CarPlay press for [keyCode], or null when the key is not a media key CarPlay handles. */
    fun forKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK -> PLAY_PAUSE
        else -> null
    }

    fun forCarPlayWindow(
        keyCode: Int,
        sessionActive: Boolean,
        inCall: Boolean,
    ): Int? {
        if (!sessionActive || inCall) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
            else -> null
        }
    }
}
