package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoSpsColorHintsTest {
    private fun hints(primaries: Int?, matrix: Int?) = VideoColorMetadata.androidHints(
        VideoColorMetadata.Description(VideoColorMetadata.Status.PARSED, true, primaries, 1, matrix))
    @Test fun standardPreservesBothPrimariesAndMatrixRatherThanGuessingFromMatrixAlone() {
        for ((primaries, matrix, expected) in listOf(Triple(1, 1, 1), Triple(5, 5, 2),
            Triple(5, 6, 2), Triple(6, 5, 4), Triple(6, 6, 4), Triple(9, 9, 6))) {
            assertEquals("$primaries/$matrix", expected, hints(primaries, matrix)["color-standard"])
        }
    }
    @Test fun unspecifiedConflictingAndUnsupportedConstantLuminanceStandardsStayUnset() {
        for ((primaries, matrix) in listOf(null to 1, 2 to 1, 1 to 6, 9 to 10, 9 to 1, 1 to null)) {
            val values = hints(primaries, matrix)
            assertFalse("$primaries/$matrix", values.containsKey("color-standard"))
            assertEquals(1, values["color-range"])
            assertEquals(3, values["color-transfer"])
        }
    }
}
