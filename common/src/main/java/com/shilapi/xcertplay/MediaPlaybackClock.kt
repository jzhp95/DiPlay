package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Keep repeated metadata updates from moving the playback position's time anchor. */
internal fun playbackPositionUpdatedAt(
    previous: CarPlayNowPlaying,
    update: CarPlayNowPlaying,
    previousUpdatedAt: Long,
    now: Long,
): Long = if (previous.elapsedMillis != update.elapsedMillis || previous.playing != update.playing) {
    // A status-only resume retains the position, but paused time must not be extrapolated.
    now
} else {
    previousUpdatedAt
}
