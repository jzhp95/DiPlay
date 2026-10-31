package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoCadenceEvidenceTest {

    @Test
    fun regular60fpsIntervalsAccumulateIn16msBucket() {
        val evidence = VideoCadenceEvidence()
        val interval = 16_666_667L
        var time = 1_000_000_000L
        for (i in 0 until 60) {
            evidence.received(senderNs = time, arrivalNs = time + 2_000_000L)
            time += interval
        }
        val snap = evidence.snapshot()
        assertEquals(60L, snap["totalSamples"])
        assertEquals(59L, snap["sourceDeltaCount"])
        assertEquals(59L, snap["cadenceBucket16ms"])
        assertEquals(0L, snap["cadenceBucket33ms"])
        val sourceFps = (snap["sourceCadenceFps"] as Double)
        assertTrue(sourceFps in 59.0..61.0)
        assertNull(snap["selectedFps"])
    }

    @Test
    fun regular30fpsIntervalsWith60fpsMaxFpsDeclarationShows30fpsCadenceAndNullSelectedFps() {
        val evidence = VideoCadenceEvidence()
        val interval = 33_333_333L
        var time = 1_000_000_000L
        for (i in 0 until 30) {
            evidence.received(senderNs = time, arrivalNs = time + 1_000_000L)
            time += interval
        }
        val snap = evidence.snapshot()
        assertEquals(30L, snap["totalSamples"])
        assertEquals(29L, snap["sourceDeltaCount"])
        assertEquals(29L, snap["cadenceBucket33ms"])
        assertEquals(0L, snap["cadenceBucket16ms"])
        val sourceFps = (snap["sourceCadenceFps"] as Double)
        assertTrue("Expected ~30fps source cadence, got $sourceFps", sourceFps in 29.5..30.5)
        assertNull("selectedFps must remain null", snap["selectedFps"])
    }

    @Test
    fun missingSenderNsNeverFabricatesSourceDeltaFromArrival() {
        val evidence = VideoCadenceEvidence()
        var arrival = 1_000_000_000L
        for (i in 0 until 10) {
            evidence.received(senderNs = null, arrivalNs = arrival)
            arrival += 16_666_667L
        }
        val snap = evidence.snapshot()
        assertEquals(10L, snap["totalSamples"])
        assertEquals(0L, snap["sourceDeltaCount"])
        assertNull(snap["sourceDeltaMeanNs"])
        assertNull(snap["sourceCadenceFps"])
        assertEquals(9L, snap["arrivalDeltaCount"])
        assertNotNull(snap["arrivalCadenceFps"])
    }

    @Test
    fun senderClockRollbackIsRecordedAndDoesNotCreateNegativeDelta() {
        val evidence = VideoCadenceEvidence()
        evidence.received(senderNs = 1_000_000_000L, arrivalNs = 1_000_000_000L)
        evidence.received(senderNs = 1_033_333_333L, arrivalNs = 1_033_333_333L)
        // Clock rolls back:
        evidence.received(senderNs = 500_000_000L, arrivalNs = 1_066_666_666L)
        evidence.received(senderNs = 533_333_333L, arrivalNs = 1_100_000_000L)

        val snap = evidence.snapshot()
        assertEquals(4L, snap["totalSamples"])
        assertEquals(2L, snap["sourceDeltaCount"])
        assertEquals(1L, snap["sourceRollbacks"])
        val mean = snap["sourceDeltaMeanNs"] as Long
        assertTrue("Delta mean must be positive", mean > 0)
    }

    @Test
    fun rollingWindowContinuesUpdatingBeyondCapacity() {
        val capacity = 5
        val evidence = VideoCadenceEvidence(capacity = capacity)
        for (i in 0 until 10) {
            evidence.received(senderNs = i * 16_666_667L + 1, arrivalNs = i * 16_666_667L + 1)
        }
        val snap = evidence.snapshot()
        assertEquals(10L, snap["totalSamples"])
        assertEquals(0L, snap["capacityDropped"])
        assertEquals(5L, snap["sourceDeltaCount"])
        assertEquals(9L, snap["totalSourceDeltas"])
    }

    @Test
    fun resetClearsAllAccumulators() {
        val evidence = VideoCadenceEvidence()
        evidence.received(senderNs = 1_000_000L, arrivalNs = 1_000_000L)
        evidence.received(senderNs = 2_000_000L, arrivalNs = 2_000_000L)
        evidence.reset()
        val snap = evidence.snapshot()
        assertEquals(0L, snap["totalSamples"])
        assertEquals(0L, snap["sourceDeltaCount"])
        assertNull(snap["sourceCadenceFps"])
    }
}
