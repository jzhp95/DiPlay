package com.shilapi.xcertplay.media

/**
 * Diagnostic and audit record of a single sustained video backlog recovery episode.
 */
data class BacklogEpisode(
    val episodeId: Long,
    val startNs: Long,
    val initialAgeNs: Long,
    val initialSpanNs: Long,
    val initialDepth: Int,
    var peakAgeNs: Long,
    var peakSpanNs: Long,
    var peakDepth: Int,
    var durationNs: Long = 0L,
    var action: String? = null,
    var endReason: String? = null,
)

/** Recover a growing input backlog, while allowing delayed bursts and static tail frames to finish. */
internal class VideoBacklogRecovery(
    private val maxHistory: Int = 16,
) {
    private var nextEpisodeId = 0L
    private var activeEpisode: BacklogEpisode? = null
    private var lastTriggeredEpisode: BacklogEpisode? = null
    private val episodeHistory = ArrayDeque<BacklogEpisode>()
    private var episodeStartNs: Long? = null
    private var initialSpanNs = 0L
    private var initialPendingFrames = 0
    private var lastObservedNs: Long? = null

    fun currentEpisode(): BacklogEpisode? = activeEpisode ?: lastTriggeredEpisode

    fun recentEpisodes(): List<BacklogEpisode> = synchronized(episodeHistory) {
        episodeHistory.toList()
    }

    private fun recordEpisode(episode: BacklogEpisode) {
        synchronized(episodeHistory) {
            if (episodeHistory.size >= maxHistory) episodeHistory.removeFirst()
            episodeHistory.addLast(episode)
        }
    }

    fun reset(reason: String? = null) {
        activeEpisode?.let { ep ->
            if (ep.endReason == null) {
                ep.endReason = reason ?: "cleared"
                lastObservedNs?.let { ep.durationNs = (it - ep.startNs).coerceAtLeast(0L) }
                recordEpisode(ep)
            }
        }
        activeEpisode = null
        lastTriggeredEpisode = null
        episodeStartNs = null
        initialSpanNs = 0L
        initialPendingFrames = 0
        lastObservedNs = null
    }

    fun observe(
        nowNs: Long,
        currentReceivedNs: Long,
        pendingFrames: Int,
        newestPendingReceivedNs: Long?,
    ): Boolean {
        if (pendingFrames <= 0 || newestPendingReceivedNs == null ||
            currentReceivedNs > nowNs || newestPendingReceivedNs > nowNs ||
            newestPendingReceivedNs < currentReceivedNs ||
            lastObservedNs?.let { nowNs < it } == true
        ) {
            reset("invalid_or_empty")
            return false
        }
        lastObservedNs = nowNs
        val ageNs = nowNs - currentReceivedNs
        val spanNs = newestPendingReceivedNs - currentReceivedNs
        if (ageNs <= LOW_AGE_NS || spanNs <= LOW_SPAN_NS) {
            reset("low_watermark")
            return false
        }
        if (ageNs >= HARD_AGE_NS && spanNs >= SOFT_SPAN_NS) {
            val ep = activeEpisode ?: BacklogEpisode(
                episodeId = ++nextEpisodeId,
                startNs = nowNs,
                initialAgeNs = ageNs,
                initialSpanNs = spanNs,
                initialDepth = pendingFrames,
                peakAgeNs = ageNs,
                peakSpanNs = spanNs,
                peakDepth = pendingFrames,
            )
            ep.peakAgeNs = maxOf(ep.peakAgeNs, ageNs)
            ep.peakSpanNs = maxOf(ep.peakSpanNs, spanNs)
            ep.peakDepth = maxOf(ep.peakDepth, pendingFrames)
            ep.action = "trigger_recovery"
            ep.endReason = "hard_limit"
            ep.durationNs = (nowNs - ep.startNs).coerceAtLeast(0L)
            lastTriggeredEpisode = ep
            recordEpisode(ep)
            activeEpisode = null
            episodeStartNs = null
            initialSpanNs = 0L
            initialPendingFrames = 0
            return true
        }
        if (ageNs <= SOFT_AGE_NS || spanNs < SOFT_SPAN_NS) return false
        val start = episodeStartNs
        if (start == null) {
            episodeStartNs = nowNs
            initialSpanNs = spanNs
            initialPendingFrames = pendingFrames
            activeEpisode = BacklogEpisode(
                episodeId = ++nextEpisodeId,
                startNs = nowNs,
                initialAgeNs = ageNs,
                initialSpanNs = spanNs,
                initialDepth = pendingFrames,
                peakAgeNs = ageNs,
                peakSpanNs = spanNs,
                peakDepth = pendingFrames,
            )
            return false
        }

        activeEpisode?.let { ep ->
            ep.peakAgeNs = maxOf(ep.peakAgeNs, ageNs)
            ep.peakSpanNs = maxOf(ep.peakSpanNs, spanNs)
            ep.peakDepth = maxOf(ep.peakDepth, pendingFrames)
        }

        val growing = spanNs - initialSpanNs >= SPAN_GROWTH_NS ||
            pendingFrames - initialPendingFrames >= FRAME_GROWTH
        if (nowNs - start >= GRACE_NS && growing) {
            activeEpisode?.let { ep ->
                ep.action = "trigger_recovery"
                ep.endReason = "sustained_growth"
                ep.durationNs = (nowNs - ep.startNs).coerceAtLeast(0L)
                lastTriggeredEpisode = ep
                recordEpisode(ep)
            }
            activeEpisode = null
            episodeStartNs = null
            initialSpanNs = 0L
            initialPendingFrames = 0
            return true
        }
        return false
    }

    private companion object {
        const val SOFT_AGE_NS = 250_000_000L
        const val SOFT_SPAN_NS = 100_000_000L
        const val LOW_AGE_NS = 150_000_000L
        const val LOW_SPAN_NS = 50_000_000L
        const val HARD_AGE_NS = 1_500_000_000L
        const val GRACE_NS = 750_000_000L
        const val SPAN_GROWTH_NS = 100_000_000L
        const val FRAME_GROWTH = 4
    }
}
