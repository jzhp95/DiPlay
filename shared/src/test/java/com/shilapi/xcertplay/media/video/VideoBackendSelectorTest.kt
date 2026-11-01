package com.shilapi.xcertplay.media.video

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoBackendSelectorTest {

    private val config = VideoStreamConfig(SessionVideoId("s1"), CodecType.H264, 1280, 720)

    @Test
    fun selectsSurfaceWhenQualifiedAndSurfaceAvailable() {
        val surface = Surface(SurfaceTexture(1))
        val target = OutputTarget(surface = surface)
        val evidence = listOf(
            ColorEvidence(
                backendKind = BackendKind.PLATFORM_SURFACE,
                componentName = "OMX.MTK.VIDEO.DECODER.AVC",
                isQualified = true
            )
        )

        val result = VideoBackendSelector.select(config, target, VideoRuntimePolicy(), evidence)
        assertTrue(result is SelectionResult.Selected)
        val selected = result as SelectionResult.Selected
        assertEquals(BackendKind.PLATFORM_SURFACE, selected.backendKind)
        assertEquals(DecoderPreference.HARDWARE, selected.decoderPreference)
    }

    @Test
    fun fallsBackToSoftwareYuvBaselineWhenHardwareUnverified() {
        val surface = Surface(SurfaceTexture(1))
        val target = OutputTarget(surface = surface)
        // No qualification evidence for hardware
        val evidence = emptyList<ColorEvidence>()

        val result = VideoBackendSelector.select(config, target, VideoRuntimePolicy(), evidence)
        assertTrue(result is SelectionResult.Selected)
        val selected = result as SelectionResult.Selected
        assertEquals(BackendKind.PLATFORM_YUV, selected.backendKind)
        assertEquals(DecoderPreference.SOFTWARE, selected.decoderPreference)
        assertEquals("VERIFIED_SOFTWARE_YUV_BASELINE", selected.reason)
    }

    @Test
    fun policyExplicitPreferenceTakesPrecedence() {
        val surface = Surface(SurfaceTexture(1))
        val target = OutputTarget(surface = surface)
        val policy = VideoRuntimePolicy(preferredBackend = BackendKind.PLATFORM_YUV, decoderPreference = DecoderPreference.AUTO)

        val result = VideoBackendSelector.select(config, target, policy, emptyList())
        assertTrue(result is SelectionResult.Selected)
        val selected = result as SelectionResult.Selected
        assertEquals(BackendKind.PLATFORM_YUV, selected.backendKind)
    }

    @Test
    fun recoveryPolicyDistinguishesErrorSeverityLevels() {
        val r0 = VideoRecoveryPolicy.decideRecovery("minor_glitch")
        assertTrue(r0 is RecoveryAction.R0_DropStale)

        val r1 = VideoRecoveryPolicy.decideRecovery("missing_reference_frame", isReferenceChainBroken = true)
        assertTrue(r1 is RecoveryAction.R1_RequestKeyframe)

        val r2 = VideoRecoveryPolicy.decideRecovery("codec_dequeue_timeout", isCodecUnresponsive = true)
        assertTrue(r2 is RecoveryAction.R2_RecreateCodec)

        val r4 = VideoRecoveryPolicy.decideRecovery("codec_hanging_in_release", isReleaseBlocked = true)
        assertTrue(r4 is RecoveryAction.R4_BlockRelease)
    }
}
