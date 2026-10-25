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
class VideoAdaptationSummaryTest {
    @Test fun hardwareAndActualOutputAreVisibleWithoutOpeningAnArchive() {
        val device = JSONObject().put("manufacturer", "fixture").put("model", "headunit")
            .put("screen", JSONObject().put("realWidth", 1920).put("realHeight", 1080).put("refreshRate", 60))
            .put("gpu", JSONObject().put("renderer", "test-gpu")).put("decoders", JSONArray().put(
                JSONObject().put("name", "OMX.fixture.decoder").put("mime", "video/avc").put("widthRange", "[64, 3840]")))
        val text = VideoAdaptationSummary.device(device)
        assertTrue(text.contains("1920 × 1080")); assertTrue(text.contains("test-gpu"))
        assertTrue(text.contains("OMX.fixture.decoder")); assertTrue(text.contains("系统未报告"))
        val state = JSONObject().put("results", JSONArray().put(JSONObject().put("title", "当前主屏")
            .put("evidence", JSONObject().put("selected_decoder", "actual-decoder")
                .put("output_format", JSONObject().put("width", 1280).put("height", 720)))))
        assertTrue(VideoAdaptationSummary.latest(state).contains("actual-decoder"))
        assertTrue(VideoAdaptationSummary.latest(state).contains("1280"))
    }
}
