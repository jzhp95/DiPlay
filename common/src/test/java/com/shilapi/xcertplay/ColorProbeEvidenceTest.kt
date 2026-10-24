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
}
