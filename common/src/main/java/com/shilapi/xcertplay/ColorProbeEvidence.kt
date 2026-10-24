package com.shilapi.xcertplay

import android.content.Context
import android.util.Base64
import com.shilapi.xcertplay.media.VideoColorMetadata
import com.shilapi.xcertplay.media.VideoSourceColorEvidence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object ColorProbeEvidence {
    fun directory(context: Context) = File(context.filesDir, "color-test-captures-v2").apply { mkdirs() }
    fun capture(source: VideoSourceColorEvidence?, rendered: Boolean, forceSoftware: Boolean,
        files: List<String>, sourceKind: String, requested: JSONObject, observed: Boolean = true): JSONObject {
        val input = source?.input
        val output = source?.output
        val completeCsd = source != null && source.csd.size <= 65536
        val currentRendered = rendered && output?.active == true && output.firstFrameRendered
        val missing = ColorEvidenceCompleteness.check(observed, currentRendered, completeCsd,
            input?.status == VideoColorMetadata.Status.PARSED || input?.status == VideoColorMetadata.Status.NOT_DECLARED,
            !output?.decoder.isNullOrEmpty(), !output?.format.isNullOrEmpty(), files.isNotEmpty(),
            forceSoftware, output?.software == true).toMutableList()
        if (!output?.format.isNullOrEmpty()) listOf("color-range", "color-standard", "color-transfer").forEach {
            if (output?.format?.get(it) == null) missing.add("output_$it")
        }
        val expected = input?.let(VideoColorMetadata::androidHints).orEmpty()
        return JSONObject().apply {
            put("schema", 2); put("source_kind", sourceKind); put("rendered", currentRendered)
            put("requested", requested)
            put("sps_color_hints", JSONObject(expected))
            put("requested_color_format", JSONObject().apply {
                if (requested.optBoolean("fromSps")) expected.forEach { (key, value) -> put(key, value) }
                if (!requested.isNull("range") && requested.has("range")) put("color-range", requested.getInt("range"))
                if (!requested.isNull("standard") && requested.has("standard")) put("color-standard", requested.getInt("standard"))
            }); put("captures", JSONArray(files)); put("missing", JSONArray(missing))
            put("codec", source?.codec ?: JSONObject.NULL)
            put("configuration_sha256", source?.sourceHash ?: JSONObject.NULL)
            put("input_csd_size", source?.csd?.size ?: 0)
            put("input_csd_complete", completeCsd)
            put("input_csd_base64", source?.let { Base64.encodeToString(it.csd.take(65536).toByteArray(), Base64.NO_WRAP) } ?: JSONObject.NULL)
            put("input_color", JSONObject().apply {
                put("status", input?.status?.name ?: "MISSING"); put("detail", input?.detail ?: "no source configuration")
                put("full_range", input?.fullRange ?: JSONObject.NULL); put("primaries", input?.primaries ?: JSONObject.NULL)
                put("transfer", input?.transfer ?: JSONObject.NULL); put("matrix", input?.matrix ?: JSONObject.NULL)
            })
            put("selected_decoder", output?.decoder ?: JSONObject.NULL)
            put("selected_decoder_software", output?.software?.let { software ->
                if (android.os.Build.VERSION.SDK_INT < 29 && !software) JSONObject.NULL else software
            } ?: JSONObject.NULL)
            put("software_detection_basis", if (android.os.Build.VERSION.SDK_INT < 29) "known_platform_name_prefix_only; unmatched_is_unknown" else "platform_property_with_name_fallback")
            put("output_generation", output?.generation ?: JSONObject.NULL)
            put("output_instance", output?.instance ?: JSONObject.NULL)
            put("decoder_active", output?.active ?: false)
            put("output_configuration_sha256", output?.configurationHash ?: JSONObject.NULL)
            put("output_format_description", output?.formatDescription ?: JSONObject.NULL)
            put("platform_unreported", JSONArray(missing.filter { it.startsWith("output_color-") }))
            put("output_format", JSONObject().apply { output?.format?.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } })
            put("comparison", JSONObject().apply {
                put("mapping_is_inferred", true)
                expected.forEach { (key, want) -> put(key, JSONObject().apply {
                    val got = output?.format?.get(key)
                    put("expected_from_sps", want ?: JSONObject.NULL); put("reported_by_decoder", got ?: JSONObject.NULL)
                    put("matches", if (want != null && got != null) want == got else JSONObject.NULL)
                }) }
            })
            val mime = when (source?.codec) { "H264" -> "video/avc"; "H265" -> "video/hevc"; else -> null }
            if (forceSoftware && mime != null && output?.decoder.isNullOrEmpty() &&
                com.shilapi.xcertplay.media.diagnosticSoftwareDecoderName(com.shilapi.xcertplay.media.diagnosticSoftwareCandidates(), mime) == null)
                put("unsupported_reason", "no_confirmed_platform_software_decoder")
            put("limitations", "参数请求不保证被车机采纳；SPS声明缺失保持未知。截图不测量背光、物理面板或最终显示后处理。")
        }
    }
    fun complete(evidence: JSONObject) = evidence.optJSONArray("missing")?.length() == 0
    fun captures(evidence: JSONObject): List<String> = evidence.optJSONArray("captures")?.let { a ->
        (0 until a.length()).map { a.getString(it) }.filter { File(it).name == it && it.startsWith("observation-") }
    }.orEmpty()
}
