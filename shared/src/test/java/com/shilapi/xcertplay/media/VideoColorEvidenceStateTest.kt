package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoColorEvidenceStateTest {
    @Test fun lateConfigurationDetailsCannotEraseAnAlreadyReportedFormat() {
        val state = VideoColorEvidenceState()
        val generation = state.begin("source-a")
        state.format(generation, mapOf("color-range" to 1))
        state.configured(generation, "software", true)
        state.rendered(generation)
        assertEquals(1, state.snapshot().format["color-range"])
        assertTrue(state.snapshot().firstFrameRendered)
    }
    @Test fun reconfigurationAndFailureCannotReuseOldSuccessOrLateEvents() {
        val state = VideoColorEvidenceState()
        val old = state.begin("same-source")
        state.configured(old, "old", false); state.format(old, mapOf("color-range" to 2)); state.rendered(old)
        val current = state.begin("same-source")
        state.format(old, mapOf("color-range" to 1)); state.rendered(old)
        assertFalse(state.snapshot().firstFrameRendered)
        assertTrue(state.snapshot().format.isEmpty())
        state.configured(current, "new", false); state.rendered(current)
        state.invalidate(current)
        assertFalse(state.snapshot().active)
        assertFalse(state.snapshot().firstFrameRendered)
        assertEquals("", state.snapshot().decoder)
    }
    @Test fun replacementDecodersHaveDifferentEvidenceIdentitiesEvenForIdenticalCsd() {
        val first = VideoColorEvidenceState(); val second = VideoColorEvidenceState()
        first.begin("same"); second.begin("same")
        assertNotEquals(first.snapshot().instance, second.snapshot().instance)
    }
}
