package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.VideoSourceColorEvidence
import com.shilapi.xcertplay.media.Yuv420Frame
import org.json.JSONArray
import org.json.JSONObject

data class DecoderPairingResult(
    val sameDecoderInstance: Boolean,
    val sameInputAndPath: Boolean,
    val frameBasis: String,
    val capturePathMatches: Boolean,
    val performanceInstance: String?,
    val captureInstance: String?,
    val rawYuvAvailable: Boolean = false,
    val rawYuvLocation: String? = null,
    val rawYuvSamples: List<Map<String, Any?>> = emptyList(),
) {
    fun applyTo(target: JSONObject): JSONObject = target
        .put("captureDecoderInstance", captureInstance ?: JSONObject.NULL)
        .put("performanceDecoderInstance", performanceInstance ?: JSONObject.NULL)
        .put("sameDecoderInstance", sameDecoderInstance)
        .put("sameInputAndPath", sameInputAndPath)
        .put("frameBasis", frameBasis)
        .put("captureDecoderMatchesPerformance", capturePathMatches)
        .put("rawYuvAvailable", rawYuvAvailable)
        .apply {
            if (rawYuvLocation != null) put("rawYuvLocation", rawYuvLocation)
            if (rawYuvSamples.isNotEmpty()) {
                val array = JSONArray()
                rawYuvSamples.forEach { array.put(JSONObject(it)) }
                put("rawYuvSamples", array)
            }
        }
}

object DecoderPixelPairing {
    /** Shared by diagnostic export and regression tests; patch identity matters, not only count. */
    fun recordEvidenceCompleteness(
        result: JSONObject,
        requiresThreeLayerEvidence: Boolean,
        rawSamples: List<Yuv420Frame.RawSample>,
        expectedPatchIndices: Set<Int>,
        captureFrameId: String,
        traceDropped: Int,
    ) {
        var rawError: String? = null
        if (requiresThreeLayerEvidence) {
            val covered = rawSamples.mapNotNull { it.patchIndex }.toSet()
            rawError = when {
                expectedPatchIndices.isEmpty() -> "missing_reference_patches"
                rawSamples.isEmpty() -> "empty_raw_yuv_samples"
                covered != expectedPatchIndices || rawSamples.any { it.patchIndex == null } ->
                    "patches_incomplete:expected=" + expectedPatchIndices.sorted() + ",covered=" + covered.sorted()
                rawSamples.any { it.frameId != captureFrameId } -> "raw_yuv_frame_id_mismatch"
                else -> null
            }
            if (rawError != null && !result.has("rawYuvError")) result.put("rawYuvError", rawError)
            result.put("threeLayerEvidenceComplete", rawError == null && !result.has("rawYuvError"))
        }
        val complete = result.has("capture") && !result.has("captureError") && traceDropped == 0 &&
            (!requiresThreeLayerEvidence || (rawError == null && !result.has("rawYuvError")))
        result.put("evidenceCompleteness", if (complete) "complete" else "partial")
    }


    val MATCHED_KEYS = listOf(
        "hardware-yuv", "software-yuv", "hardware-yuv-fallback",
        "color-range", "color-standard", "color-transfer",
        "width", "height", "crop-left", "crop-top", "crop-right", "crop-bottom"
    )

    fun evaluate(
        performanceEvidence: VideoSourceColorEvidence?,
        captureEvidence: VideoSourceColorEvidence?,
        replay: Boolean = false,
        rawSamples: List<Yuv420Frame.RawSample> = emptyList(),
        rawYuvLocationOverride: String? = null,
    ): DecoderPairingResult {
        val performanceOutput = performanceEvidence?.output
        val captureOutput = captureEvidence?.output
        val performanceFormat = performanceOutput?.format.orEmpty()
        val captureFormat = captureOutput?.format.orEmpty()

        val decoderMatches = captureOutput?.decoder != null &&
            captureOutput.decoder == performanceOutput?.decoder
        val sourceHashMatches = captureEvidence?.sourceHash != null &&
            captureEvidence.sourceHash == performanceEvidence?.sourceHash
        val formatsMatch = MATCHED_KEYS.all { captureFormat[it] == performanceFormat[it] }

        val capturePathMatches = decoderMatches && sourceHashMatches && formatsMatch

        val captureInst = captureOutput?.instance
        val performanceInst = performanceOutput?.instance
        val sameDecoderInst = captureInst != null && performanceInst != null && captureInst == performanceInst

        val frameBasis = if (replay) "same_source_frozen_first_picture" else "same_input_first_random_access_picture"

        val rawAvailable = rawSamples.isNotEmpty()
        val rawLoc = if (!rawAvailable) null else rawYuvLocationOverride ?: if (captureFormat["software-yuv"] == 1 || captureFormat["hardware-yuv"] == 1) "capture_yuv_instance" else "diagnostic_no_surface_instance"

        return DecoderPairingResult(
            sameDecoderInstance = sameDecoderInst,
            sameInputAndPath = capturePathMatches,
            frameBasis = frameBasis,
            capturePathMatches = capturePathMatches,
            performanceInstance = performanceInst,
            captureInstance = captureInst,
            rawYuvAvailable = rawAvailable,
            rawYuvLocation = rawLoc,
            rawYuvSamples = rawSamples.map { it.toMap() },
        )
    }
}
