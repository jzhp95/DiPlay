package com.shilapi.xcertplay.media

/**
 * Tracks video cadence independently between sender timestamps (NTP/PTS from screen header)
 * and local receiver arrival timestamps (System.nanoTime).
 *
 * Missing sender timestamps or sender rollbacks NEVER synthesize source intervals from arrival timestamps.
 * A rolling window of recent deltas ensures statistics never freeze during long-running sessions.
 */
internal class VideoCadenceEvidence(private val capacity: Int = 512) {
    private var totalSamples = 0L

    private var lastSenderNs: Long? = null
    private var lastArrivalNs: Long? = null

    // Rolling window for source deltas
    private val sourceDeltas = LongArray(capacity)
    private var sourceDeltaHead = 0
    private var sourceDeltaCount = 0
    private var sourceDeltaSumNs = 0L
    private var totalSourceDeltas = 0L

    private var sourceRepeats = 0L
    private var sourceRollbacks = 0L
    private var sourceGaps = 0L

    // Rolling window for arrival deltas
    private val arrivalDeltas = LongArray(capacity)
    private var arrivalDeltaHead = 0
    private var arrivalDeltaCount = 0
    private var arrivalDeltaSumNs = 0L

    private var arrivalRollbacks = 0L
    private var arrivalGaps = 0L

    // Cadence buckets within the rolling window
    private var cadence16msCount = 0L
    private var cadence33msCount = 0L
    private var cadenceOtherCount = 0L

    @Synchronized
    fun received(senderNs: Long?, arrivalNs: Long) {
        totalSamples++

        lastArrivalNs?.let { prev ->
            val delta = arrivalNs - prev
            if (delta < 0) {
                arrivalRollbacks++
            } else {
                if (arrivalDeltaCount == capacity) {
                    val old = arrivalDeltas[arrivalDeltaHead]
                    arrivalDeltaSumNs -= old
                } else {
                    arrivalDeltaCount++
                }
                arrivalDeltas[arrivalDeltaHead] = delta
                arrivalDeltaSumNs += delta
                arrivalDeltaHead = (arrivalDeltaHead + 1) % capacity
                if (delta > GAP_THRESHOLD_NS) arrivalGaps++
            }
        }
        lastArrivalNs = arrivalNs

        if (senderNs != null && senderNs > 0L) {
            lastSenderNs?.let { prev ->
                val delta = senderNs - prev
                when {
                    delta == 0L -> sourceRepeats++
                    delta < 0L -> sourceRollbacks++
                    else -> {
                        totalSourceDeltas++
                        if (sourceDeltaCount == capacity) {
                            val old = sourceDeltas[sourceDeltaHead]
                            sourceDeltaSumNs -= old
                            if (old in 14_000_000L..19_000_000L) cadence16msCount--
                            else if (old in 29_000_000L..38_000_000L) cadence33msCount--
                            else cadenceOtherCount--
                        } else {
                            sourceDeltaCount++
                        }
                        sourceDeltas[sourceDeltaHead] = delta
                        sourceDeltaSumNs += delta
                        if (delta in 14_000_000L..19_000_000L) cadence16msCount++
                        else if (delta in 29_000_000L..38_000_000L) cadence33msCount++
                        else cadenceOtherCount++
                        sourceDeltaHead = (sourceDeltaHead + 1) % capacity

                        if (delta > GAP_THRESHOLD_NS) sourceGaps++
                    }
                }
            }
            lastSenderNs = senderNs
        }
    }

    @Synchronized
    fun snapshot(): Map<String, Any?> {
        val avgSourceDeltaNs = if (sourceDeltaCount > 0) sourceDeltaSumNs / sourceDeltaCount else null
        val sourceFps = avgSourceDeltaNs?.let { if (it > 0) 1_000_000_000.0 / it else null }

        val avgArrivalDeltaNs = if (arrivalDeltaCount > 0) arrivalDeltaSumNs / arrivalDeltaCount else null
        val arrivalFps = avgArrivalDeltaNs?.let { if (it > 0) 1_000_000_000.0 / it else null }

        return linkedMapOf(
            "totalSamples" to totalSamples,
            "capacityDropped" to 0L,
            "sourceDeltaCount" to sourceDeltaCount.toLong(),
            "totalSourceDeltas" to totalSourceDeltas,
            "sourceDeltaMeanNs" to avgSourceDeltaNs,
            "sourceCadenceFps" to sourceFps,
            "sourceRepeats" to sourceRepeats,
            "sourceRollbacks" to sourceRollbacks,
            "sourceGaps" to sourceGaps,
            "cadenceBucket16ms" to cadence16msCount,
            "cadenceBucket33ms" to cadence33msCount,
            "cadenceBucketOther" to cadenceOtherCount,
            "arrivalDeltaCount" to arrivalDeltaCount.toLong(),
            "arrivalDeltaMeanNs" to avgArrivalDeltaNs,
            "arrivalCadenceFps" to arrivalFps,
            "arrivalRollbacks" to arrivalRollbacks,
            "arrivalGaps" to arrivalGaps,
            "selectedFps" to null,
        )
    }

    @Synchronized
    fun reset() {
        totalSamples = 0L
        lastSenderNs = null
        lastArrivalNs = null
        sourceDeltaHead = 0
        sourceDeltaCount = 0
        sourceDeltaSumNs = 0L
        totalSourceDeltas = 0L
        sourceRepeats = 0L
        sourceRollbacks = 0L
        sourceGaps = 0L
        arrivalDeltaHead = 0
        arrivalDeltaCount = 0
        arrivalDeltaSumNs = 0L
        arrivalRollbacks = 0L
        arrivalGaps = 0L
        cadence16msCount = 0L
        cadence33msCount = 0L
        cadenceOtherCount = 0L
    }

    companion object {
        const val GAP_THRESHOLD_NS = 500_000_000L
    }
}
