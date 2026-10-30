package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A scheduler owns only media work; touch/Siri/phone dispatch stays on its existing executor. */
internal interface MediaCommandScheduler : Closeable {
    fun post(delayMillis: Long = 0, action: () -> Unit)
}
internal class SerialMediaCommandScheduler : MediaCommandScheduler {
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "carplay-media-command").apply { isDaemon = true }
    }
    override fun post(delayMillis: Long, action: () -> Unit) { worker.schedule(Runnable { action() }, delayMillis, TimeUnit.MILLISECONDS) }
    override fun close() { worker.shutdownNow() }
}

/** Queue acceptance is not iPhone acknowledgement. The snapshot is retained real iPhone state. */
internal class MediaCommandQueue<S : Any>(
    private val current: () -> S?,
    private val usable: (S) -> Boolean,
    private val snapshot: () -> CarPlayNowPlaying,
    private val send: (S, Int) -> Boolean,
    private val report: (String) -> Unit = {},
    private val scheduler: MediaCommandScheduler = SerialMediaCommandScheduler(),
    private val nanoTime: () -> Long = System::nanoTime,
) : Closeable {
    @Volatile private var closed = false
    private val intent = AtomicLong()
    // Worker-owned: a consecutive skip inherits the first skip's play intent across transient pauses.
    private var resumeSession: S? = null
    private var resumeWanted = false
    private var explicitSession: S? = null
    private var pausedByUser = false
    private var pauseConfirmed = false
    private fun clearPauseIntent() { explicitSession = null; pausedByUser = false; pauseConfirmed = false }
    /** iPhone transitions, ordered on this queue, can confirm pause and a later external resume. */
    fun observePlayback(playing: Boolean) {
        val session = current() ?: return
        if (closed) return
        try { scheduler.post {
            if (!valid(session) || explicitSession !== session || !pausedByUser) return@post
            if (!playing) pauseConfirmed = true else if (pauseConfirmed) clearPauseIntent()
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
    private fun clearResume() { resumeSession = null; resumeWanted = false }
    private fun valid(session: S) = !closed && current() === session && usable(session)
    fun enqueue(index: Int): Boolean {
        val session = current()?.takeIf(::valid) ?: return false
        val ticket = intent.incrementAndGet() // cancels old resume tasks at acceptance, before dispatch
        val queuedAt = nanoTime()
        return try {
            scheduler.post {
                if (!valid(session)) { report("media stale index=$index dropped"); return@post }
                val before = snapshot()
                if (explicitSession !== session) clearPauseIntent()
                when (index) {
                    CarPlayMediaButton.PAUSE -> {
                        explicitSession = session; pausedByUser = true; pauseConfirmed = !before.playing
                    }
                    CarPlayMediaButton.PLAY -> clearPauseIntent()
                    CarPlayMediaButton.PLAY_PAUSE -> {
                        val willPause = if (pausedByUser) false else before.playing
                        clearPauseIntent()
                        if (willPause) { explicitSession = session; pausedByUser = true; pauseConfirmed = !before.playing }
                    }
                }
                val trackChange = index == CarPlayMediaButton.NEXT || index == CarPlayMediaButton.PREVIOUS
                val wantsResume = trackChange && !pausedByUser && (before.playing || (resumeSession === session && resumeWanted))
                if (!trackChange) clearResume()
                try {
                    val written = send(session, index)
                    report("media index=$index waitMs=${(nanoTime()-queuedAt)/1_000_000} written=$written (not phone acknowledgement)")
                    if (written && wantsResume) {
                        resumeSession = session; resumeWanted = true
                        scheduler.post(700) { resume(session, ticket, before, 0) }
                    } else if (trackChange) clearResume()
                } catch (error: Exception) { report("media index=$index failed=${error.javaClass.simpleName}") }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) { false }
    }
    private fun resume(session: S, ticket: Long, before: CarPlayNowPlaying, poll: Int) {
        if (!valid(session) || intent.get() != ticket) return
        val now = snapshot()
        val unknown = before.title.isNullOrBlank() && before.artist.isNullOrBlank() ||
            now.title.isNullOrBlank() && now.artist.isNullOrBlank()
        val changed = before.title != now.title || before.artist != now.artist
        if (!unknown && !changed && poll < 10) {
            scheduler.post(300) { resume(session, ticket, before, poll + 1) }
        } else if (!now.playing) {
            try { report("media resume written=${send(session, CarPlayMediaButton.PLAY)} poll=$poll (not phone acknowledgement)") }
            catch (error: Exception) { report("media resume failed=${error.javaClass.simpleName}") }
            clearResume()
        } else clearResume()
    }
    override fun close() { closed = true; intent.incrementAndGet(); scheduler.close() }
}
