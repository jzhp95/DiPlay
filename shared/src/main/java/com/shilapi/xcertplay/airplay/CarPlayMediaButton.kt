package com.shilapi.xcertplay.airplay

import android.view.KeyEvent

/**
 * Hardware media keys → CarPlay media HID presses (indices into [AirPlayHid]'s media report).
 *
 * Hardware play and pause keys both map to the toggle: BYD picks PLAY or PAUSE from its own idea of
 * the play state, and a wrong guess would make the button do nothing. Explicit play and pause
 * commands from media controllers use [PLAY] and [PAUSE].
 */
object CarPlayMediaButton {
    const val PLAY = 1
    const val PAUSE = 2
    const val PLAY_PAUSE = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    /** Flyme reference APK fallback for KEYCODE_R_VOICE_ASSIST. */
    const val KEYCODE_FLYME_VOICE_ASSIST = 200231

    /**
     * Whether [keyCode] is a voice key that opens Siri. The BYD wheel sends each press as an
     * instant down/up pair, so a long press arrives as its own key rather than as a held one.
     */
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

    /**
     * The CarPlay press the CarPlay screen itself should claim for [keyCode], or null to let the key
     * go on to the car.
     *
     * BYD hands the steering wheel's next/previous keys to whichever window is in front, and DiPlay's
     * CarPlay screen is that window whenever the driver is looking at it. Claiming them here is what
     * makes the wheel change tracks without the accessibility service, which a head unit with no ADB
     * cannot turn on.
     *
     * Only next and previous are claimed. Play/pause is left alone: BYD acts on it itself, and the
     * wheel key service owns it for DiLink 3. Nothing is claimed outside a CarPlay session, or during
     * a call, where the car's own call handling is what the driver expects from these keys.
     */
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
