package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject

/** Descriptive A/B evidence only; neither causality nor a calibrated picture-quality verdict. */
internal object VideoTroubleshooting {
    const val REFERENCE_ID = "diplay-rgb-v1"
    private fun codec(row: JSONObject) = row.optJSONObject("begin")?.optString("codec").orEmpty()
    private fun condition(row: JSONObject): Map<String, String> {
        val b = row.optJSONObject("begin") ?: JSONObject()
        val r = b.optJSONObject("requested") ?: JSONObject()
        val format = b.optJSONObject("output_format") ?: JSONObject()
        val phone = b.optJSONObject("phoneContext") ?: JSONObject()
        val context = b.optJSONObject("troubleshootingContext") ?: JSONObject()
        fun value(obj: JSONObject, key: String) = if (obj.has(key) && !obj.isNull(key)) obj.get(key).toString() else ""
        return linkedMapOf(
            "scene" to value(context, "scene"), "phoneModel" to value(phone, "model"), "phoneIos" to value(phone, "ios"),
            "canvasWidth" to value(b, "requestedCanvasWidth"), "canvasHeight" to value(b, "requestedCanvasHeight"),
            "codedWidth" to value(format, "width"), "codedHeight" to value(format, "height"),
            "fpsSetting" to value(r, "fps"), "transport" to value(b, "transport"),
            "view" to value(r, "actualView"), "path" to value(b, "actual_output_path"),
            "picture" to value(b, "comparisonPicture"),
            "software" to value(r, "softwareRequired"), "softwareYuv" to value(r, "softwareYuv"),
            "hardwareYuv" to value(r, "hardwareYuv"),
            "lowLatency" to value(r, "vendorLowLatency"), "mtkTuning" to value(r, "mtkTuning"),
            "smooth" to value(r, "smoothVideo"), "direct" to value(r, "directVideoOutput"),
            "yuvRange" to if (r.optBoolean("softwareYuv")) value(r,"softwareYuvRange") else "unused",
            "yuvMatrix" to if (r.optBoolean("softwareYuv")) value(r,"softwareYuvMatrix") else "unused")
    }
    fun report(value: JSONObject): JSONObject {
        val rows = value.optJSONArray("samples") ?: JSONArray()
        val comparisons = JSONArray()
        for (phase in listOf("static", "dynamic")) {
            fun latest(round: String): JSONObject? = (0 until rows.length()).reversed().mapNotNull { rows.optJSONObject(it) }
                .firstOrNull { it.optString("phase") == phase &&
                    it.optJSONObject("begin")?.optJSONObject("troubleshootingContext")?.optString("round") == round }
            val a = latest("A"); val b = latest("B")
            val gaps = JSONArray()
            if (a == null) gaps.put("missing_valid_A")
            if (b == null) gaps.put("missing_valid_B")
            if (a != null && b != null) {
                for ((label, row) in listOf("A" to a, "B" to b)) {
                    if (!row.optBoolean("valid")) gaps.put(label + "_sample_invalid")
                    val active = value.optJSONObject("troubleshootingContext")
                    if (active?.optString("round") == label &&
                        active.optString("id") != row.optJSONObject("begin")?.optJSONObject("troubleshootingContext")?.optString("id"))
                        gaps.put(label + "_active_round_not_sampled")
                }
                val ac = condition(a); val bc = condition(b)
                for ((key, v) in ac) {
                    if (v.isBlank() || bc[key].isNullOrBlank()) gaps.put("unreported_" + key)
                    else if (v != bc[key]) gaps.put("different_" + key)
                }
                if (codec(a) != "H264") gaps.put("A_actual_codec_not_H264")
                if (codec(b) != "H265") gaps.put("B_actual_codec_not_HEVC")
                for ((label, row) in listOf("A" to a, "B" to b)) {
                    if (row.optJSONObject("begin")?.optString("selected_decoder").isNullOrBlank()) gaps.put(label + "_decoder_missing")
                }
                if (b.optLong("collectedAt") <= a.optLong("collectedAt")) gaps.put("B_not_after_A")
            }
            comparisons.put(JSONObject().put("phase", phase).put("status", if (gaps.length() == 0) "conditions_matched" else "blocked")
                .put("missingOrDifferent", gaps).put("A", summary(a)).put("B", summary(b)))
        }
        return JSONObject().put("schema", 1).put("comparison", comparisons)
            .put("colorReference", JSONObject().put("id", REFERENCE_ID).put("sourcePresentation", "manual_attestation_only")
                .put("physicalScreenPhoto", "not_attached").put("calibratedColorVerdict", JSONObject.NULL))
            .put("touchToPhotonMs", JSONObject.NULL).put("phoneProcessingAndNetworkRoundTrip", "not_measured")
            .put("conclusion", "descriptive_metrics_only; matched_conditions_do_not_prove_causality_or_fix")
    }
    private fun summary(row: JSONObject?): Any = row?.let {
        val begin = it.optJSONObject("begin") ?: JSONObject()
        JSONObject().put("sampleId", it.opt("sampleId") ?: JSONObject.NULL).put("codec", codec(it))
            .put("decoder", begin.opt("selected_decoder") ?: JSONObject.NULL)
            .put("software", begin.opt("selected_decoder_software") ?: JSONObject.NULL)
            .put("context", begin.optJSONObject("troubleshootingContext") ?: JSONObject.NULL)
            .put("metrics", it.optJSONObject("summary") ?: JSONObject.NULL)
            .put("touch", it.optJSONObject("touch") ?: JSONObject.NULL)
            .put("captureFiles", it.optJSONArray("captures") ?: JSONArray())
    } ?: JSONObject.NULL


    private fun reason(code: String): String {
        val fields = mapOf("scene" to "共同场景", "phoneModel" to "手机型号", "phoneIos" to "手机系统",
            "canvasWidth" to "请求宽度", "canvasHeight" to "请求高度", "codedWidth" to "编码宽度",
            "codedHeight" to "编码高度", "fpsSetting" to "帧率设置", "transport" to "连接方式",
            "view" to "显示组件", "path" to "输出路径", "picture" to "画面调节",
            "software" to "软解选择", "softwareYuv" to "软件 YUV", "hardwareYuv" to "硬件 YUV",
            "lowLatency" to "低延迟设置", "mtkTuning" to "MTK 调节", "smooth" to "流畅视频",
            "direct" to "直接输出", "yuvRange" to "YUV 范围", "yuvMatrix" to "YUV 色彩矩阵")
        if (code.startsWith("unreported_")) return (fields[code.removePrefix("unreported_")] ?: "条件") + "未报告"
        if (code.startsWith("different_")) return (fields[code.removePrefix("different_")] ?: "条件") + "不同"
        return when(code) {
            "missing_valid_A" -> "尚无基线 A"; "missing_valid_B" -> "尚无对照 B"
            "A_actual_codec_not_H264" -> "A 未实际使用 H.264"
            "B_actual_codec_not_HEVC" -> "B 未实际使用 HEVC"
            "A_sample_invalid" -> "A 采样中断或无效"; "B_sample_invalid" -> "B 采样中断或无效"
            "A_active_round_not_sampled" -> "新一轮 A 尚未采集"; "B_active_round_not_sampled" -> "新一轮 B 尚未采集"
            "A_decoder_missing" -> "A 解码器未知"; "B_decoder_missing" -> "B 解码器未知"
            "B_not_after_A" -> "请先采 A 再采 B"
            else -> "待核对采集条件"
        }
    }

    fun text(value: JSONObject): String {
        val report = report(value)
        return buildString {
            appendLine("HEVC 分轮对照：A 关闭 HEVC，B 开启 HEVC；每轮重连，保持场景、手机、分辨率及输出设置一致。")
            val comparisons = report.getJSONArray("comparison")
            for (i in 0 until comparisons.length()) {
                val item = comparisons.getJSONObject(i)
                appendLine((if (item.getString("phase") == "static") "静止" else "拖图") + "：" +
                    if (item.getString("status") == "conditions_matched") "条件已核对，可查看指标；尚无优劣结论" else "待补齐：" + item.getJSONArray("missingOrDifferent").let { gaps ->
                        (0 until gaps.length()).joinToString("；") { reason(gaps.getString(it)) }
                    })
                for (round in listOf("A", "B")) item.optJSONObject(round)?.let {
                    val m = it.optJSONObject("metrics")
                    fun metric(key: String) = m?.opt(key)?.takeUnless { v -> v == JSONObject.NULL }
                        ?.let { v -> if (v is Number) String.format(java.util.Locale.ROOT, "%.2f", v.toDouble()) else v.toString() } ?: "未报告"
                    appendLine("$round 实际编码=${it.optString("codec")} 解码器=${it.optString("decoder")}；视频 kbps=${metric("payloadKbps")} 提交 fps=${metric("submittedToDisplayFps")} 排队至输出 ms=${metric("queueToOutputMillis")}")
                }
            }
            append("色彩缺项：真实源参考图呈现待核实、屏幕实拍未附、无校准判色。跟手性缺项：手机确认及对应返回帧、网络往返、面板显示时刻、外部高速录像。")
        }
    }
}
