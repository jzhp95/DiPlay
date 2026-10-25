package com.shilapi.xcertplay.media

import kotlin.math.pow
import kotlin.math.roundToInt

/** Immutable snapshot for one connection; disabled controls retain their saved dB value. */
data class ExperimentalAudioConfig(
    val microphoneEnabled: Boolean = false,
    val microphoneDb: Int = 0,
    val navigationEnabled: Boolean = false,
    val navigationDb: Int = 0,
    val callEnabled: Boolean = false,
    val callDb: Int = 0,
    val outputRecovery: Boolean = false,
) {
    val microphoneGainDb: Int get() = if (microphoneEnabled) clamp(microphoneDb) else 0
    fun playbackGainDb(isNavigation: Boolean, isCall: Boolean, attenuationDb: Int): Int = when {
        isNavigation -> NavigationAudioGain.clamp(attenuationDb) + if (navigationEnabled) clamp(navigationDb) else 0
        isCall && callEnabled -> clamp(callDb)
        else -> 0
    }
    companion object {
        const val MIN_DB = -12
        const val MAX_DB = 12
        fun clamp(db: Int): Int = db.coerceIn(MIN_DB, MAX_DB)
    }
}

/** PCM16 little endian only, after decoding/AEC and before packet encoding/track writes. */
internal object Pcm16Gain {
    fun scale(data: ByteArray, offset: Int, length: Int, db: Int) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length)
        if (db == 0) return
        // Navigation's existing -20 dB attenuation may combine with the new -12 dB control.
        val gain = 10.0.pow(db.coerceIn(-32, 12) / 20.0)
        val end = offset + (length and 1.inv())
        for (at in offset until end step 2) {
            val sample = (data[at].toInt() and 0xff) or (data[at + 1].toInt() shl 8)
            val scaled = (sample * gain).roundToInt().coerceIn(-32768, 32767)
            data[at] = scaled.toByte()
            data[at + 1] = (scaled shr 8).toByte()
        }
    }
}

/** Lifetime budget per renderer, never replenished by short successful writes. */
internal class AudioOutputRecovery {
    var attempts = 0
        private set
    private var zeroWrites = 0
    fun successfulWrite() { zeroWrites = 0 }
    fun shouldRebuild(code: Int, enabled: Boolean, running: Boolean): Boolean {
        if (!enabled || !running) return false
        if (code == 0) {
            zeroWrites++
            if (zeroWrites < 3) return false
        } else {
            zeroWrites = 0
            // Android AudioTrack: DEAD_OBJECT (-6), INVALID_OPERATION (-3).
            // Bad arguments (-2) and unknown errors must not trigger a rebuild loop.
            if (code != -6 && code != -3) return false
        }
        if (attempts >= 2) return false
        attempts++
        zeroWrites = 0
        return true
    }
}
