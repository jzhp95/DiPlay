package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class ColorEvidenceCompletenessTest {
    @Test fun aVerdictAloneDoesNotTurnMissingEvidenceIntoACompleteCapture() {
        val gaps = ColorEvidenceCompleteness.check(observed = true, rendered = false,
            sourceRecorded = false, sourceReadable = false, decoderRecorded = false,
            outputRecorded = false, captureRecorded = false, softwareRequired = false, softwareVerified = false)
        assertTrue("first_frame" in gaps)
        assertTrue("input_csd" in gaps)
        assertTrue("output_format" in gaps)
        assertTrue("capture" in gaps)
    }
    @Test fun softwareTrialsNeedProofOfTheActualSelectedSoftwareDecoder() {
        val gaps = ColorEvidenceCompleteness.check(true, true, true, true, true, true, true, true, false)
        assertEquals(listOf("software_decoder"), gaps)
        assertTrue(ColorEvidenceCompleteness.check(true, true, true, true, true, true, true, false, false).isEmpty())
    }
}
