package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelContractResolverTest {

    private val target = RenderTargetDescription()

    @Test
    fun rejectsHighBitDepthAsUnsupported() {
        val source = SourceColorDeclaration(bitDepth = 10)
        val output = DecoderOutputDescription(bitDepth = 10)
        val policy = VideoRuntimePolicy()

        val decision = PixelContractResolver.resolve(source, output, target, policy)
        assertTrue(decision is ColorDecision.Unsupported)
        val unsupported = decision as ColorDecision.Unsupported
        assertTrue(unsupported.reason.contains("Bit depth > 8"))
    }

    @Test
    fun manualPolicyOverridesSourceContract() {
        val source = SourceColorDeclaration(range = ColorRange.FULL, matrix = ColorMatrix.BT709)
        val output = DecoderOutputDescription()
        val policy = VideoRuntimePolicy(colorPolicy = ColorPolicy.FORCE_LIMITED)

        val decision = PixelContractResolver.resolve(source, output, target, policy)
        assertTrue(decision is ColorDecision.Accepted)
        val accepted = decision as ColorDecision.Accepted
        assertEquals(ColorRange.LIMITED, accepted.contract.effectiveRange)
        assertEquals("MANUAL_POLICY_FORCE_LIMITED", accepted.contract.evidenceBasis)
        assertEquals(Confidence.LOW, accepted.contract.confidence)
    }

    @Test
    fun resolvesCarPlayH264Matrix6AndHevcMatrix1Faithfully() {
        // CarPlay H.264 VUI: range=Limited, matrix=BT601 (code 6)
        val h264Source = SourceColorDeclaration(range = ColorRange.LIMITED, matrix = ColorMatrix.BT601)
        val output = DecoderOutputDescription(actualComponent = "c2.android.avc.decoder")
        val policy = VideoRuntimePolicy()

        val h264Decision = PixelContractResolver.resolve(h264Source, output, target, policy)
        assertTrue(h264Decision is ColorDecision.Accepted)
        val h264Contract = (h264Decision as ColorDecision.Accepted).contract
        assertEquals(ColorRange.LIMITED, h264Contract.effectiveRange)
        assertEquals(ColorMatrix.BT601, h264Contract.effectiveMatrix)

        // CarPlay HEVC VUI: range=Limited, matrix=BT709 (code 1)
        val hevcSource = SourceColorDeclaration(range = ColorRange.LIMITED, matrix = ColorMatrix.BT709)
        val hevcDecision = PixelContractResolver.resolve(hevcSource, output, target, policy)
        assertTrue(hevcDecision is ColorDecision.Accepted)
        val hevcContract = (hevcDecision as ColorDecision.Accepted).contract
        assertEquals(ColorMatrix.BT709, hevcContract.effectiveMatrix)
    }

    @Test
    fun handlesConflictBetweenSourceAndDecoderAsUnverifiedWithFallback() {
        val source = SourceColorDeclaration(range = ColorRange.FULL, matrix = ColorMatrix.BT709)
        val output = DecoderOutputDescription(
            reportedRange = ColorRange.LIMITED, // Conflict!
            actualComponent = "OMX.MTK.VIDEO.DECODER.AVC"
        )
        val policy = VideoRuntimePolicy()

        val decision = PixelContractResolver.resolve(source, output, target, policy)
        assertTrue(decision is ColorDecision.Unverified)
        val unverified = decision as ColorDecision.Unverified
        assertTrue(unverified.reason.contains("Range conflict"))
        assertEquals(ColorRange.LIMITED, unverified.fallbackContract.effectiveRange)
        assertEquals(Confidence.LOW, unverified.fallbackContract.confidence)
    }

    @Test
    fun usesVerifiedQualificationWhenApplicabilityKeyMatches() {
        val source = SourceColorDeclaration(
            range = ColorRange.LIMITED,
            matrix = ColorMatrix.BT601,
            origin = "SPS",
            bitDepth = 8
        )
        val output = DecoderOutputDescription(
            actualComponent = "OMX.MTK.VIDEO.DECODER.AVC",
            bitDepth = 8
        )
        val applicabilityKey = "SPS:OMX.MTK.VIDEO.DECODER.AVC:LIMITED:BT601:8"

        val verifiedContract = ResolvedPixelContract(
            effectiveRange = ColorRange.LIMITED,
            effectiveMatrix = ColorMatrix.BT601,
            evidenceBasis = "GROUND_TRUTH_HARDWARE_AUDIT",
            confidence = Confidence.VERIFIED,
            applicabilityKey = applicabilityKey
        )

        val qualification = ColorQualification(
            deviceModel = "Geely-Kunlun-2026",
            verifiedContracts = mapOf(applicabilityKey to verifiedContract)
        )

        val decision = PixelContractResolver.resolve(
            source,
            output,
            target,
            VideoRuntimePolicy(),
            qualification
        )

        assertTrue(decision is ColorDecision.Accepted)
        val accepted = decision as ColorDecision.Accepted
        assertEquals(Confidence.VERIFIED, accepted.contract.confidence)
        assertEquals("GROUND_TRUTH_HARDWARE_AUDIT", accepted.contract.evidenceBasis)
    }
}
