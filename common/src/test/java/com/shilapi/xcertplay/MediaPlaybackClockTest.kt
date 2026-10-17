package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPlaybackClockTest {
    @Test fun resumeAtTheSamePositionExcludesTimeSpentPaused() {
        val paused = CarPlayNowPlaying(elapsedMillis = 30_000L, playing = false)
        val resumed = paused.copy(playing = true)
        val resumeTime = 90_000L
        val updatedAt = playbackPositionUpdatedAt(paused, resumed, 30_000L, resumeTime)

        // Android extrapolates PLAYING from its position and elapsed-realtime anchor.
        val displayedPosition = resumed.elapsedMillis!! + (resumeTime + 1_000L - updatedAt)
        assertEquals(31_000L, displayedPosition)
    }

    @Test fun pausingAtTheSamePositionReanchorsTheStoppedClock() {
        val playing = CarPlayNowPlaying(elapsedMillis = 30_000L, playing = true)
        assertEquals(40_000L, playbackPositionUpdatedAt(playing, playing.copy(playing = false), 30_000L, 40_000L))
    }

    @Test fun repeatedMetadataDoesNotResetThePlayingClock() {
        val playing = CarPlayNowPlaying(title = "Song", elapsedMillis = 30_000L, playing = true)
        assertEquals(30_000L, playbackPositionUpdatedAt(playing, playing.copy(artist = "Artist"), 30_000L, 31_000L))
        assertEquals(30_000L, playbackPositionUpdatedAt(playing, playing, 30_000L, 32_000L))
    }

    @Test fun aNewPositionReanchorsEvenWithoutAPlaybackStateChange() {
        for (playing in listOf(false, true)) {
            val previous = CarPlayNowPlaying(elapsedMillis = 30_000L, playing = playing)
            assertEquals(50_000L, playbackPositionUpdatedAt(previous, previous.copy(elapsedMillis = 10_000L), 30_000L, 50_000L))
        }
    }
}
