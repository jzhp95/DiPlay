package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputFreshnessPolicyTest {

    @Test
    fun singleStaleCandidateWithoutNewerCandidateMustDisplay() {
        val frame = testFrame()
        val candidate = FrameCandidate(
            frame = frame,
            arrivalMonoNs = 1_000_000_000L // 1.0s
        )
        // Current time is 1.2s -> age is 200ms (> 100ms maxAllowedAgeMs)
        // BUT hasNewerReadyCandidate = false
        val context = PresentationContext(
            currentMonoNs = 1_200_000_000L,
            hasPendingBacklog = true,
            hasNewerReadyCandidate = false,
            maxAllowedAgeMs = 100L
        )

        val decision = OutputFreshnessPolicy.decide(candidate, context)
        assertTrue("Single candidate must be displayed even if older than 100ms", decision is PresentationDecision.Display)
    }

    @Test
    fun staleCandidateWithNewerCandidateIsDropped() {
        val frame = testFrame()
        val candidate = FrameCandidate(
            frame = frame,
            arrivalMonoNs = 1_000_000_000L
        )
        // Current time is 1.15s (age 150ms > 100ms) AND hasNewerReadyCandidate = true
        val context = PresentationContext(
            currentMonoNs = 1_150_000_000L,
            hasPendingBacklog = true,
            hasNewerReadyCandidate = true,
            maxAllowedAgeMs = 100L
        )

        val decision = OutputFreshnessPolicy.decide(candidate, context)
        assertTrue(decision is PresentationDecision.DropStale)
        val drop = decision as PresentationDecision.DropStale
        assertEquals(150L, drop.ageMs)
    }

    @Test
    fun firstFrameAndResumingFrameAreAlwaysProtected() {
        val frame1 = testFrame()
        val firstCandidate = FrameCandidate(
            frame = frame1,
            arrivalMonoNs = 1_000_000_000L,
            isFirstFrame = true
        )
        val context = PresentationContext(
            currentMonoNs = 2_000_000_000L, // 1000ms old
            hasNewerReadyCandidate = true
        )
        assertTrue(OutputFreshnessPolicy.decide(firstCandidate, context) is PresentationDecision.Display)

        val frame2 = testFrame()
        val resumingCandidate = FrameCandidate(
            frame = frame2,
            arrivalMonoNs = 1_000_000_000L,
            isResumingFrame = true
        )
        assertTrue(OutputFreshnessPolicy.decide(resumingCandidate, context) is PresentationDecision.Display)
    }

    @Test
    fun diagnosticHoldTakesPrecedence() {
        val frame = testFrame()
        val candidate = FrameCandidate(frame = frame, arrivalMonoNs = 1_000_000_000L)
        val context = PresentationContext(
            currentMonoNs = 1_010_000_000L,
            isDiagnosticHold = true
        )

        val decision = OutputFreshnessPolicy.decide(candidate, context)
        assertTrue(decision is PresentationDecision.Hold)
    }

    @Test
    fun pacingDelayCalculatesTargetTime() {
        val frame = testFrame()
        val candidate = FrameCandidate(frame = frame, arrivalMonoNs = 1_000_000_000L)
        val context = PresentationContext(
            currentMonoNs = 1_010_000_000L,
            pacingDelayMs = 25L
        )

        val decision = OutputFreshnessPolicy.decide(candidate, context)
        assertTrue(decision is PresentationDecision.Display)
        val display = decision as PresentationDecision.Display
        assertEquals(1_025_000_000L, display.targetTimeNs)
    }
}
