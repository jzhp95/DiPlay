package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Color
import com.shilapi.xcertplay.media.VideoColorMetadata
import com.shilapi.xcertplay.media.VideoDecoderColorEvidence
import com.shilapi.xcertplay.media.VideoSourceColorEvidence
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DecoderPixelPairingTest {

    private fun sampleEvidence(
        decoder: String = "c2.android.avc.decoder",
        sourceHash: String = "hash_1234",
        instance: String = "inst_001",
        width: Int = 1920,
        height: Int = 1080,
    ): VideoSourceColorEvidence {
        val format = mapOf(
            "hardware-yuv" to 0,
            "software-yuv" to 0,
            "hardware-yuv-fallback" to 0,
            "color-range" to 1,
            "color-standard" to 1,
            "color-transfer" to 3,
            "width" to width,
            "height" to height,
            "crop-left" to 0,
            "crop-top" to 0,
            "crop-right" to width - 1,
            "crop-bottom" to height - 1,
        )
        val output = VideoDecoderColorEvidence(
            configurationHash = sourceHash,
            decoder = decoder,
            software = true,
            format = format,
            instance = instance,
        )
        return VideoSourceColorEvidence(
            codec = "avc",
            csd = byteArrayOf(1, 2, 3),
            input = VideoColorMetadata.Description(VideoColorMetadata.Status.PARSED),
            output = output,
            sourceHash = sourceHash,
        )
    }

    @Test
    fun productionPairingDistinguishesSameDecoderFromDifferentInstance() {
        val perf = sampleEvidence(instance = "inst_A")
        val captureSame = sampleEvidence(instance = "inst_A")
        val captureDiff = sampleEvidence(instance = "inst_B")

        val resultSame = DecoderPixelPairing.evaluate(perf, captureSame, replay = false)
        assertTrue(resultSame.sameDecoderInstance)
        assertTrue(resultSame.sameInputAndPath)
        assertTrue(resultSame.capturePathMatches)
        assertEquals("same_input_first_random_access_picture", resultSame.frameBasis)

        val resultDiff = DecoderPixelPairing.evaluate(perf, captureDiff, replay = true)
        assertFalse(resultDiff.sameDecoderInstance)
        assertTrue(resultDiff.sameInputAndPath)
        assertTrue(resultDiff.capturePathMatches)
        assertEquals("same_source_frozen_first_picture", resultDiff.frameBasis)

        val json = JSONObject()
        resultDiff.applyTo(json)
        assertFalse(json.getBoolean("sameDecoderInstance"))
        assertTrue(json.getBoolean("sameInputAndPath"))
        assertEquals("inst_A", json.getString("performanceDecoderInstance"))
        assertEquals("inst_B", json.getString("captureDecoderInstance"))
    }

    @Test
    fun differentSourceHashRejectsPixelPairing() {
        val perf = sampleEvidence(sourceHash = "hash_performance")
        val capture = sampleEvidence(sourceHash = "hash_different")

        val result = DecoderPixelPairing.evaluate(perf, capture)
        assertFalse("Different sourceHash must reject path match", result.capturePathMatches)
        assertFalse(result.sameInputAndPath)
    }

    @Test
    fun differentDecoderOrGeometryRejectsPixelPairing() {
        val perf = sampleEvidence(decoder = "OMX.MTK.VIDEO.DECODER.AVC", width = 1920)
        val captureOtherDecoder = sampleEvidence(decoder = "c2.android.avc.decoder", width = 1920)
        val captureOtherRes = sampleEvidence(decoder = "OMX.MTK.VIDEO.DECODER.AVC", width = 1280)

        assertFalse(DecoderPixelPairing.evaluate(perf, captureOtherDecoder).capturePathMatches)
        assertFalse(DecoderPixelPairing.evaluate(perf, captureOtherRes).capturePathMatches)
    }

    @Test
    fun highlightsAnalysisDetectsClippingAndAccurateGrayscale() {
        val context = RuntimeEnvironment.getApplication()
        val ref = context.assets.open("color-test/h1-reference.json").bufferedReader().use { reader ->
            JSONObject(reader.readText())
        }.getJSONObject("files").getJSONObject("h264_light_full")

        fun createTestImage(expandWhite: Boolean): Bitmap {
            val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            val patches = ref.getJSONArray("patches")
            for (i in 0 until patches.length()) {
                var v = patches.getJSONObject(i).getDouble("referenceMean")
                if (expandWhite) v = ((v - 16) * 255 / 219).coerceIn(0.0, 255.0)
                val gray = v.toInt()
                val color = Color.rgb(gray, gray, gray)
                for (y in 0 until 320) {
                    for (x in i * 96 until (i + 1) * 96) {
                        b.setPixel(x, y, color)
                    }
                }
            }
            return b
        }

        val normalImage = createTestImage(expandWhite = false)
        val clippedImage = createTestImage(expandWhite = true)

        val normalMetrics = DecoderDiagnosticsMetrics.highlights(normalImage, ref)
        val clippedMetrics = DecoderDiagnosticsMetrics.highlights(clippedImage, ref)

        assertEquals("not_supported_by_model_comparison", normalMetrics.getString("h1Priority"))
        assertEquals("repeat_limited_expansion_shape_supported", clippedMetrics.getString("h1Priority"))
        assertTrue("Normal image leaves colorPass null (no automated verdict)", normalMetrics.isNull("colorPass"))
    }

    @Test
    fun rawYuvEvidenceAndSampleBindingAreRecordedInPairingAndHighlights() {
        val perf = sampleEvidence(instance = "inst_perf")
        val capture = sampleEvidence(instance = "inst_capture")
        val rawSample = com.shilapi.xcertplay.media.Yuv420Frame.RawSample(
            x = 48, y = 160, yCode = 235, uCode = 128, vCode = 128,
            cropLeft = 0, cropTop = 0, cropRight = 1919, cropBottom = 1079,
            rowStride = 1920, pixelStride = 1,
            patchIndex = 0, nominal = 0, frameId = "test_frame_1", instanceId = "diagnostic_no_surface_instance"
        )

        val pairing = DecoderPixelPairing.evaluate(
            perf, capture, replay = false,
            rawSamples = listOf(rawSample),
            rawYuvLocationOverride = "diagnostic_no_surface_instance"
        )
        assertTrue(pairing.rawYuvAvailable)
        assertEquals("diagnostic_no_surface_instance", pairing.rawYuvLocation)
        assertEquals(1, pairing.rawYuvSamples.size)
        assertEquals(235, pairing.rawYuvSamples[0]["yCode"])

        val json = JSONObject()
        pairing.applyTo(json)
        assertTrue(json.getBoolean("rawYuvAvailable"))
        assertEquals("diagnostic_no_surface_instance", json.getString("rawYuvLocation"))
        assertEquals(1, json.getJSONArray("rawYuvSamples").length())

        val context = RuntimeEnvironment.getApplication()
        val ref = context.assets.open("color-test/h1-reference.json").bufferedReader().use { reader ->
            JSONObject(reader.readText())
        }.getJSONObject("files").getJSONObject("h264_light_full")
        val image = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val highlights = DecoderDiagnosticsMetrics.highlights(image, ref, pairing.rawYuvSamples)
        assertTrue(highlights.getBoolean("rawYuvAvailable"))
        assertEquals("source_reference -> raw_yuv_sample -> screenshot_rgb", highlights.getString("responsibleLayer"))
        val firstPatch = highlights.getJSONArray("patches").getJSONObject(0)
        assertTrue(firstPatch.has("rawYuv"))
        assertEquals(235, firstPatch.getJSONObject("rawYuv").getInt("yCode"))
    }

    @Test
    fun productionCompletenessExportsPartialForMissingPatchesOrWrongFrame() {
        val frameId = "h264_light_full:first_random_access_picture"
        fun sample(index: Int, frame: String = frameId) =
            com.shilapi.xcertplay.media.Yuv420Frame.RawSample(
                x = 48, y = 160, yCode = 235, uCode = 128, vCode = 128,
                patchIndex = index, nominal = 16, frameId = frame, instanceId = "decoder",
            )
        val cases = listOf(
            emptyList(), listOf(sample(0)),
            listOf(sample(0), sample(2)), // Same count, wrong patch identity.
            listOf(sample(0), sample(1, "wrong_frame_id")),
        )
        for (samples in cases) {
            val result = JSONObject().put("capture", "captures/frame.png")
            DecoderPixelPairing.recordEvidenceCompleteness(result, true, samples, setOf(0, 1), frameId, 0)
            assertEquals("partial", result.getString("evidenceCompleteness"))
            assertFalse(result.getBoolean("threeLayerEvidenceComplete"))
            assertTrue(result.has("rawYuvError"))
        }
        val result = JSONObject().put("capture", "captures/frame.png")
        DecoderPixelPairing.recordEvidenceCompleteness(
            result, true, listOf(sample(0), sample(1)), setOf(0, 1), frameId, 0,
        )
        assertEquals("complete", result.getString("evidenceCompleteness"))
        assertTrue(result.getBoolean("threeLayerEvidenceComplete"))
        assertFalse(result.has("rawYuvError"))
        for (error in listOf("rawYuvError", "captureError")) {
            val failed = JSONObject().put("capture", "captures/frame.png").put(error, "failed")
            DecoderPixelPairing.recordEvidenceCompleteness(
                failed, true, listOf(sample(0), sample(1)), setOf(0, 1), frameId, 0,
            )
            assertEquals("partial", failed.getString("evidenceCompleteness"))
        }
        val missingReference = JSONObject().put("capture", "captures/frame.png")
        DecoderPixelPairing.recordEvidenceCompleteness(
            missingReference, true, listOf(sample(0)), emptySet(), frameId, 0,
        )
        assertEquals("partial", missingReference.getString("evidenceCompleteness"))
        val droppedTrace = JSONObject().put("capture", "captures/frame.png")
        DecoderPixelPairing.recordEvidenceCompleteness(
            droppedTrace, true, listOf(sample(0), sample(1)), setOf(0, 1), frameId, 1,
        )
        assertEquals("partial", droppedTrace.getString("evidenceCompleteness"))
    }

    @Test
    fun noSurfaceDecoderConfigConversionAndSpsAnnexBFormatting() {
        val context = RuntimeEnvironment.getApplication()
        val csdBytes = context.assets.open("color-test/h264_limited.csd").readBytes()
        val startCode = byteArrayOf(0, 0, 0, 1)

        val (sps, pps) = com.shilapi.xcertplay.media.MediaCodecSupport.avcParameterSets(csdBytes)
        val csdList = listOfNotNull(
            sps.takeIf { it.isNotEmpty() }?.let { startCode + it },
            pps.takeIf { it.isNotEmpty() }?.let { startCode + it },
        )
        assertEquals(2, csdList.size)
        assertTrue("csd-0 starts with Annex-B start code", csdList[0].take(4).toByteArray().contentEquals(startCode))
        assertTrue("csd-1 starts with Annex-B start code", csdList[1].take(4).toByteArray().contentEquals(startCode))

        // Length-prefixed NALU converted to Annex-B
        val fakeNalu = byteArrayOf(0x65, 0x01, 0x02)
        val lengthPrefixed = byteArrayOf(0, 0, 0, 3) + fakeNalu
        val annexB = com.shilapi.xcertplay.media.MediaCodecSupport.toAnnexB(lengthPrefixed)
        assertEquals(7, annexB.size)
        assertTrue(annexB.take(4).toByteArray().contentEquals(startCode))
        assertArrayEquals(fakeNalu, annexB.takeLast(3).toByteArray())
    }
}
