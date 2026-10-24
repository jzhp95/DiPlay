package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSoftwareDecoderTest {
    @Test fun android9UsesKnownPlatformSoftwareNamesAndNeverGuessesAVendorDecoder() {
        val candidates = listOf(
            SoftwareVideoCandidate("OMX.vendor.avc.decoder", listOf("video/avc"), null),
            SoftwareVideoCandidate("OMX.google.h264.decoder", listOf("video/avc"), null),
            SoftwareVideoCandidate("OMX.google.hevc.decoder", listOf("video/hevc"), null))
        assertEquals("OMX.google.h264.decoder", diagnosticSoftwareDecoderName(candidates, "video/avc"))
        assertEquals("OMX.google.hevc.decoder", diagnosticSoftwareDecoderName(candidates, "video/hevc"))
        assertNull(diagnosticSoftwareDecoderName(candidates.take(1), "video/avc"))
    }
    @Test fun explicitSoftwareTrialCannotFallBackToDefaultHardware() {
        assertEquals(emptyList<DecoderAttempt>(), diagnosticVideoDecoderAttempts(true, null, emptyList()))
        val attempts = diagnosticVideoDecoderAttempts(true, "OMX.google.h264.decoder", listOf(DecoderAttempt(null, true)))
        assertEquals(1, attempts.size)
        assertEquals("OMX.google.h264.decoder", attempts.single().codecName)
        assertFalse(attempts.single().tuned)
    }
    @Test fun normalSessionsKeepTheirExistingFallbackPlan() {
        val normal = listOf(DecoderAttempt(null, true), DecoderAttempt(null, false))
        assertEquals(normal, diagnosticVideoDecoderAttempts(false, null, normal))
    }
}
