package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoRemediationEvidenceTest {

    private fun sampleRow(
        phase: String,
        round: String,
        codec: String,
        collectedAt: Long,
        valid: Boolean = true,
        decoder: String = "c2.android.avc.decoder",
        mtkTuning: String = "false",
        view: String = "SurfaceView",
        path: String = "surface",
    ): JSONObject {
        val begin = JSONObject()
            .put("codec", codec)
            .put("selected_decoder", decoder)
            .put("actual_output_path", path)
            .put("transport", "usb")
            .put("requestedCanvasWidth", 1280)
            .put("requestedCanvasHeight", 720)
            .put("comparisonPicture", "standard")
            .put("troubleshootingContext", JSONObject().put("round", round).put("scene", "map_idle").put("id", "run-1"))
            .put("phoneContext", JSONObject().put("model", "iPhone14,2").put("ios", "16.5"))
            .put("requested", JSONObject()
                .put("fps", 60)
                .put("actualView", view)
                .put("softwareRequired", false)
                .put("softwareYuv", false)
                .put("hardwareYuv", false)
                .put("vendorLowLatency", false)
                .put("mtkTuning", mtkTuning)
                .put("smoothVideo", false)
                .put("directVideoOutput", false)
            )
            .put("output_format", JSONObject().put("width", 1280).put("height", 720))

        return JSONObject()
            .put("phase", phase)
            .put("valid", valid)
            .put("collectedAt", collectedAt)
            .put("begin", begin)
            .put("summary", JSONObject().put("payloadKbps", 4500.0).put("submittedToDisplayFps", 30.0))
    }

    @Test
    fun matchedConditionsReportsMatchedStatusWithoutBestVerdict() {
        val a = sampleRow("static", "A", "H264", collectedAt = 1000L)
        val b = sampleRow("static", "B", "H265", collectedAt = 2000L, decoder = "c2.android.hevc.decoder")

        val input = JSONObject().put("samples", JSONArray().put(a).put(b))
        val report = VideoTroubleshooting.report(input)

        assertEquals(1, report.getInt("videoRemediationSchema"))
        assertEquals("codec", report.getString("experimentType"))
        val comparisons = report.getJSONArray("comparison")
        val staticComp = comparisons.getJSONObject(0)
        assertEquals("static", staticComp.getString("phase"))
        assertEquals("conditions_matched", staticComp.getString("status"))
        assertEquals(0, staticComp.getJSONArray("missingOrDifferent").length())
        // Conclusion must be descriptive, never declaring one variant as optimal
        assertTrue(report.getString("conclusion").contains("matched_conditions_do_not_prove_causality_or_fix"))
    }

    @Test
    fun confoundedMtkAndCodecChangeReportsGapAndBlocksConclusion() {
        // A has mtkTuning=false, B has mtkTuning=true and H265
        val a = sampleRow("static", "A", "H264", collectedAt = 1000L, mtkTuning = "false")
        val b = sampleRow("static", "B", "H265", collectedAt = 2000L, decoder = "c2.android.hevc.decoder", mtkTuning = "true")

        val input = JSONObject().put("samples", JSONArray().put(a).put(b))
        val report = VideoTroubleshooting.report(input)

        val comparisons = report.getJSONArray("comparison")
        val staticComp = comparisons.getJSONObject(0)
        assertEquals("blocked", staticComp.getString("status"))
        val gaps = staticComp.getJSONArray("missingOrDifferent")
        val gapList = (0 until gaps.length()).map { gaps.getString(it) }
        assertTrue("Must report different_mtkTuning", gapList.contains("different_mtkTuning"))
    }

    @Test
    fun mtkTuningExperimentAllowsOnlyMtkChangeWhilePreservingCodec() {
        val a = sampleRow("static", "A", "H264", collectedAt = 1000L, mtkTuning = "false")
        val b = sampleRow("static", "B", "H264", collectedAt = 2000L, mtkTuning = "true")

        val input = JSONObject()
            .put("experimentType", "mtk_tuning")
            .put("samples", JSONArray().put(a).put(b))
        val report = VideoTroubleshooting.report(input)

        assertEquals("mtk_tuning", report.getString("experimentType"))
        assertEquals("mtkTuning", report.getString("changedFactor"))
        val staticComp = report.getJSONArray("comparison").getJSONObject(0)
        assertEquals("conditions_matched", staticComp.getString("status"))
    }

    @Test
    fun missingFieldOrInvalidSampleReportsExplicitGap() {
        // Missing B sample entirely:
        val a = sampleRow("static", "A", "H264", collectedAt = 1000L)
        val input = JSONObject().put("samples", JSONArray().put(a))
        val report = VideoTroubleshooting.report(input)

        val staticComp = report.getJSONArray("comparison").getJSONObject(0)
        assertEquals("blocked", staticComp.getString("status"))
        val gaps = staticComp.getJSONArray("missingOrDifferent")
        val gapList = (0 until gaps.length()).map { gaps.getString(it) }
        assertTrue(gapList.contains("missing_valid_B"))
    }

    @Test
    fun reversedCollectionOrderReportsBNotAfterA() {
        val a = sampleRow("static", "A", "H264", collectedAt = 3000L)
        val b = sampleRow("static", "B", "H265", collectedAt = 1000L, decoder = "c2.android.hevc.decoder")

        val input = JSONObject().put("samples", JSONArray().put(a).put(b))
        val report = VideoTroubleshooting.report(input)

        val staticComp = report.getJSONArray("comparison").getJSONObject(0)
        assertEquals("blocked", staticComp.getString("status"))
        val gaps = staticComp.getJSONArray("missingOrDifferent")
        val gapList = (0 until gaps.length()).map { gaps.getString(it) }
        assertTrue(gapList.contains("B_not_after_A"))
    }
}
