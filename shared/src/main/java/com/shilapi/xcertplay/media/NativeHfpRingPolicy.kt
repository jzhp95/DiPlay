package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioStreamId

/** Only a duplicate ring alert is suppressed; music, Siri and call-audio fallback stay available. */
internal class NativeHfpRingPolicy(private val enabled: Boolean) {
    private val suppressed = mutableSetOf<AudioStreamId>()
    @Synchronized fun suppress(id: AudioStreamId, systemRinging: Boolean): Boolean {
        if (!enabled || id.type != 100 || !id.audioType.equals("alert", ignoreCase = true)) return false
        if (systemRinging) suppressed.add(id)
        return id in suppressed
    }
    @Synchronized fun contains(id: AudioStreamId) = id in suppressed
    @Synchronized fun stop(id: AudioStreamId) { suppressed.remove(id) }
    @Synchronized fun clear() { suppressed.clear() }
}
