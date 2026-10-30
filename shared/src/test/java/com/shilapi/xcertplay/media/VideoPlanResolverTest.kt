package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoPlanResolverTest {
    @Test fun ordinaryDirectSmoothAndSoftwarePoliciesPreserveTheirExistingPriority() {
        data class Case(val request:VideoPipelineRequest,val output:VideoOutput,val smooth:Boolean,val direct:Boolean,val software:Boolean)
        val cases=listOf(
            Case(VideoPipelineRequest(),VideoOutput.TEXTURE,false,false,false),
            Case(VideoPipelineRequest(smooth=true),VideoOutput.SURFACE,true,false,false),
            Case(VideoPipelineRequest(direct=true),VideoOutput.SURFACE,false,true,false),
            Case(VideoPipelineRequest(smooth=true,softwareDirect=true),VideoOutput.SURFACE,false,true,true),
            Case(VideoPipelineRequest(smooth=true,softwareYuv=SoftwareYuvConfig()),VideoOutput.APP_YUV,false,true,true),
            Case(VideoPipelineRequest(smooth=true,softwareDirect=true,softwareYuv=SoftwareYuvConfig(),hardwareYuv=true),VideoOutput.APP_YUV,false,true,false))
        cases.forEach{case->
            val plan=VideoPlanResolver.resolve(case.request)
            assertEquals(case.output,plan.output);assertEquals(case.smooth,plan.smooth)
            assertEquals(case.direct,plan.direct);assertEquals(case.software,plan.softwareDirect)
        }
    }
    @Test fun unsafeTextureAndUnacceleratedWindowChooseCompositorSurface() {
        assertEquals(VideoOutput.SURFACE,VideoPlanResolver.resolve(VideoPipelineRequest(),VideoCapabilities(hardwareAccelerated=false)).output)
        assertEquals(VideoOutput.SURFACE,VideoPlanResolver.resolve(VideoPipelineRequest(),VideoCapabilities(hardwareAccelerated=true,textureUnsafe=true)).output)
    }
    @Test fun strictSoftwareUnavailableIsReportedWithoutSilentlySelectingHardware() {
        val plan=VideoPlanResolver.resolve(VideoPipelineRequest(forceSoftware=true,purpose=VideoPurpose.DIAGNOSTIC),VideoCapabilities(softwareAvailable=false))
        assertEquals(false,plan.supported);assertEquals(VideoDecodePolicy.STRICT_SOFTWARE,plan.decode)
        assertEquals("requested_decoder_path_unavailable",plan.reason)
    }
    @Test fun sameRequestIsStableAndLiveVersusDiagnosticKeepsPublicPolicyEqual() {
        val request=VideoPipelineRequest(width=1920,height=1080,direct=true,softwareDirect=true)
        val first=VideoPlanResolver.resolve(request);val again=VideoPlanResolver.resolve(request)
        val diagnostic=VideoPlanResolver.resolve(request.copy(purpose=VideoPurpose.DIAGNOSTIC))
        assertEquals(first.planId,again.planId)
        assertEquals(first.output,diagnostic.output);assertEquals(first.decode,diagnostic.decode)
        assertNotEquals(first.planId,diagnostic.planId)
        assertNull(first.supported)
    }
}
