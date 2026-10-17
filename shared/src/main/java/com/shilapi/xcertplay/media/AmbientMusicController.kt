package com.shilapi.xcertplay.media

import android.content.Context
import java.util.concurrent.CompletableFuture

/** Compatibility API for shared audio sinks. This build does not control vehicle lamps. */
@Suppress("UNUSED_PARAMETER")
object AmbientMusicController {
    private val phonePlayback = AmbientPhonePlaybackState()
    fun openSink(context: Context?): Long = 0L
    internal fun openRenderer(sink: Long, envelope: AmbientMusicEnvelope,
        playback: () -> Pair<Int, Boolean>): Long = 0L
    internal fun wantsPcm(token: Long): Boolean = false
    fun claimPlaybackOwner(owner: Any) { phonePlayback.claim(owner) }
    fun albumColorChanged(owner: Any, color: Int?) = Unit
    fun phonePlaybackChanged(owner: Any, playing: Boolean): Boolean = phonePlayback.update(owner, playing)
    fun releasePlaybackOwner(owner: Any): Boolean = phonePlayback.release(owner)
    fun checkSupport(context: Context): CompletableFuture<Boolean> = CompletableFuture.completedFuture(false)
    fun settingsChanged(context: Context) = Unit
    internal fun closeRenderer(token: Long) = Unit
    fun closeSink(token: Long) = Unit
}
