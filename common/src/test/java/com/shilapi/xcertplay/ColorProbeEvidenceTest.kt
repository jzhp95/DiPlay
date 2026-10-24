package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ColorProbeEvidenceTest {
    @Test fun widthAndHeightCannotStandInForMissingOutputColorFields() {
        val bytes = RuntimeEnvironment.getApplication().assets.open("color-test/h264_full.csd").use { it.readBytes() }
        val hash = videoConfigurationHash(bytes)
        val output = VideoDecoderColorEvidence(hash, "codec", false, mapOf("width" to 640, "height" to 360),
            generation = 1, active = true, firstFrameRendered = true)
        val source = VideoSourceColorEvidence("H264", bytes, VideoColorMetadata.parse(VideoCodec.H264, bytes), output, hash)
        val report = ColorProbeEvidence.capture(source, true, false, listOf("observation-a.png"), "test", JSONObject())
        val gaps = report.getJSONArray("missing").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertTrue("output_color-range" in gaps)
        assertTrue("output_color-standard" in gaps)
        assertTrue("output_color-transfer" in gaps)
        assertFalse(ColorProbeEvidence.complete(report))
        assertTrue("API28 unrecognized codec names are unknown, not proof of hardware", report.isNull("selected_decoder_software"))
    }

    @Test fun spsRequestIsReportedSeparatelyFromWhatTheDecoderActuallyOutputs() {
        val bytes = RuntimeEnvironment.getApplication().assets.open("color-test/h264_full.csd").use { it.readBytes() }
        val hash = videoConfigurationHash(bytes)
        val output = VideoDecoderColorEvidence(hash, "codec", false, mapOf("color-range" to 2,
            "color-standard" to 1, "color-transfer" to 3), generation = 1, active = true, firstFrameRendered = true)
        val source = VideoSourceColorEvidence("H264", bytes, VideoColorMetadata.parse(VideoCodec.H264, bytes), output, hash)
        val report = ColorProbeEvidence.capture(source, true, false, listOf("observation.png"), "test",
            JSONObject().put("fromSps", true))
        assertEquals(1, report.getJSONObject("requested_color_format").getInt("color-range"))
        assertEquals(2, report.getJSONObject("output_format").getInt("color-range"))
        assertFalse(report.getJSONObject("comparison").getJSONObject("color-range").getBoolean("matches"))
        val normal = ColorProbeEvidence.capture(source, true, false, listOf("observation.png"), "test", JSONObject())
        assertEquals(0, normal.getJSONObject("requested_color_format").length())
    }
}
