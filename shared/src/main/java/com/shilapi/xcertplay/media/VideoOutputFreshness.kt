package com.shilapi.xcertplay.media

/**
 * Pure policy for deciding whether a decoded output frame is stale and safe to discard
 * from display presentation without breaking the decoder's compressed reference chain.
 */
object VideoOutputFreshness {
    const val DEFAULT_MAX_OUTPUT_AGE_NS: Long = 100_000_000L // 100ms threshold

    fun shouldDiscardDecodedOutput(
        ageNs: Long,
        newerOutputAvailable: Boolean,
        firstFrame: Boolean,
        diagnostic: Boolean,
        explicitlyPaced: Boolean,
        maxAgeNs: Long = DEFAULT_MAX_OUTPUT_AGE_NS
    ): Boolean = when {
        firstFrame -> false
        diagnostic -> false
        explicitlyPaced -> false
        !newerOutputAvailable -> false
        ageNs > maxAgeNs -> true
        else -> false
    }
}
