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
class VideoTroubleshootingTest {
    private fun row(round: String, codec: String, at: Long): JSONObject {
        val req = JSONObject().put("fps", 60).put("actualView", "TextureView").put("softwareRequired", false)
            .put("softwareYuv", false).put("hardwareYuv", false).put("vendorLowLatency", false).put("mtkTuning", false)
            .put("smoothVideo",false).put("directVideoOutput",false)
        val begin = JSONObject().put("codec", codec).put("selected_decoder", "OMX.MTK." + codec)
            .put("requested", req).put("requestedCanvasWidth", 1920).put("requestedCanvasHeight", 1080)
            .put("output_format", JSONObject().put("width",1920).put("height",1080))
            .put("phoneContext",JSONObject().put("model","same-phone").put("ios","same-ios"))
            .put("transport","wireless").put("actual_output_path","surface_worker_output").put("comparisonPicture","brightness=0")
            .put("troubleshootingContext",JSONObject().put("round",round).put("id",round).put("scene","same-map"))
        return JSONObject().put("phase","dynamic").put("valid",true).put("collectedAt",at)
            .put("sampleId",round).put("begin",begin).put("summary",JSONObject().put("receivedFps",30))
    }
    private fun value(vararg rows: JSONObject) = JSONObject().put("samples",JSONArray(rows.toList()))
    private fun comparison(value: JSONObject) = VideoTroubleshooting.report(value).getJSONArray("comparison").getJSONObject(1)
    @Test fun sameConditionsAndActualH264HevcProduceDescriptiveComparison() {
        val result = VideoTroubleshooting.report(value(row("A","H264",1),row("B","H265",2)))
        assertEquals("conditions_matched",result.getJSONArray("comparison").getJSONObject(1).getString("status"))
        assertTrue(result.isNull("touchToPhotonMs"))
        assertTrue(result.getJSONObject("colorReference").isNull("calibratedColorVerdict"))
    }
    @Test fun requestedHevcCannotSubstituteForActualHevc() {
        val a=row("A","H264",1);val b=row("B","H264",2)
        b.getJSONObject("begin").getJSONObject("requested").put("hevcRequested",true)
        val result=comparison(value(a,b))
        assertEquals("blocked",result.getString("status"))
        assertTrue(result.getJSONArray("missingOrDifferent").toString().contains("B_actual_codec_not_HEVC"))
    }
    @Test fun differentGeometryOrUnknownPhoneBlocksComparison() {
        val a=row("A","H264",1);val b=row("B","H265",2)
        b.getJSONObject("begin").getJSONObject("output_format").put("width",1280)
        b.getJSONObject("begin").getJSONObject("phoneContext").remove("model")
        val gaps=comparison(value(a,b)).getJSONArray("missingOrDifferent").toString()
        assertTrue(gaps.contains("different_codedWidth"));assertTrue(gaps.contains("unreported_phoneModel"))
    }
    @Test fun failedLatestTrialDoesNotResurrectOldSuccessfulTrial() {
        val a=row("A","H264",1);val old=row("B","H265",2);val failed=row("B","H265",3).put("valid",false)
        assertTrue(comparison(value(a,old,failed)).getJSONArray("missingOrDifferent").toString().contains("B_sample_invalid"))
    }
    @Test fun selectingNewRoundCannotPresentPreviousRoundAsCurrent() {
        val data=value(row("A","H264",1),row("B","H265",2))
            .put("troubleshootingContext",JSONObject().put("round","B").put("id","new-round"))
        assertTrue(comparison(data).getJSONArray("missingOrDifferent").toString().contains("B_active_round_not_sampled"))
    }
    @Test fun outputAndPictureChangesAreNotAttributedToCodec() {
        val a=row("A","H264",1);val b=row("B","H265",2)
        b.getJSONObject("begin").put("actual_output_path","software_yuv_app_rgb").put("comparisonPicture","brightness=20")
        val gaps=comparison(value(a,b)).getJSONArray("missingOrDifferent").toString()
        assertTrue(gaps.contains("different_path"));assertTrue(gaps.contains("different_picture"))
    }

    @Test fun softwareYuvColorConversionsMustMatchButUnusedControlsDoNotBlockHardware() {
        val a=row("A","H264",1);val b=row("B","H265",2)
        for (r in listOf(a,b)) r.getJSONObject("begin").getJSONObject("requested")
            .put("softwareYuv",true).put("softwareYuvRange",1).put("softwareYuvMatrix",1)
        b.getJSONObject("begin").getJSONObject("requested").put("softwareYuvRange",2).put("softwareYuvMatrix",2)
        val gaps=comparison(value(a,b)).getJSONArray("missingOrDifferent").toString()
        assertTrue(gaps.contains("different_yuvRange"));assertTrue(gaps.contains("different_yuvMatrix"))
        for (r in listOf(a,b)) r.getJSONObject("begin").getJSONObject("requested").put("softwareYuv",false)
        assertEquals("conditions_matched",comparison(value(a,b)).getString("status"))
    }
}
