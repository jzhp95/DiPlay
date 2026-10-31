package com.shilapi.xcertplay

import android.graphics.Bitmap
import com.shilapi.xcertplay.media.VideoDiagnosticTrace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil

internal object DecoderDiagnosticsMetrics {
    fun distribution(values: List<Double>): JSONObject {
        if (values.isEmpty()) return JSONObject().put("samples", 0)
        val s = values.sorted()
        fun p(q: Double) = s[(ceil(q * s.size).toInt() - 1).coerceIn(s.indices)]
        return JSONObject().put("samples", s.size).put("mean", s.average()).put("p50", p(.5))
            .put("p95", p(.95)).put("p99", p(.99)).put("max", s.last()).put("unit", "ms")
    }
    fun trace(snapshot: VideoDiagnosticTrace.Snapshot, file: File): JSONObject {
        val submits = snapshot.events.filter { it.stage == "codec_submit" }.associateBy { it.instance to it.frame }
        val codec = ArrayList<Double>(); val app = ArrayList<Double>(); val input = ArrayList<Double>()
        val copy = ArrayList<Double>(); val draw = ArrayList<Double>(); val surface = ArrayList<Double>()
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out ->
            out.appendLine("decoder\tstage\tframe_pts_us\tlocal_ns\ta\tb\tc")
            snapshot.events.forEach { e ->
                out.appendLine("${e.instance}\t${e.stage}\t${e.frame}\t${e.nanos}\t${e.a}\t${e.b}\t${e.c}")
                when (e.stage) {
                    "codec_submit" -> { if (e.a > 0 && e.b >= e.a) app.add((e.b-e.a)/1e6); if (e.b > 0) input.add((e.nanos-e.b)/1e6) }
                    "codec_output" -> submits[e.instance to e.frame]?.let { if (e.nanos >= it.nanos) codec.add((e.nanos-it.nanos)/1e6) }
                    "yuv_draw" -> { copy.add(e.a/1e6); draw.add(e.b/1e6) }
                    "surface_release" -> if (e.a > 0) surface.add((e.nanos-e.a)/1e6)
                }
            }
        }
        return JSONObject().put("codecQueueToOutput", distribution(codec)).put("applicationWait", distribution(app))
            .put("inputAcquireAndCopy", distribution(input)).put("yuvCopy", distribution(copy)).put("yuvDrawAndSwap", distribution(draw))
            .put("surfaceReleaseCall", distribution(surface)).put("traceEvents", snapshot.events.size).put("traceDropped", snapshot.dropped)
            .put("scope", "local_app_boundaries; codec_includes_buffering; no_panel_or_complete_touch_latency")
    }
    fun highlights(bitmap: Bitmap, reference: JSONObject, rawSamples: List<Map<String, Any?>> = emptyList()): JSONObject {
        if (bitmap.width != 1920 || bitmap.height != 1080) return JSONObject().put("available", false).put("reason", "capture_not_native_reference_geometry")
        val rows = JSONArray(); val refs = reference.getJSONArray("patches")
        var correct = 0.0; var expanded = 0.0; var whiteCount = 0; var highCount = 0
        for (i in 0 until refs.length()) {
            val p = refs.getJSONObject(i); val rect = p.getJSONArray("rect")
            var sum = 0.0; var count = 0; var clipped = 0
            for (y in rect.getInt(1) until rect.getInt(1)+rect.getInt(3) step 8)
                for (x in rect.getInt(0) until rect.getInt(0)+rect.getInt(2) step 4) {
                    val rgb = bitmap.getPixel(x,y)
                    val value = ((rgb ushr 16 and 255)+(rgb ushr 8 and 255)+(rgb and 255))/3.0
                    sum += value; count++; if (value >= 254.5) clipped++
                }
            val mean = sum/count; val expected = p.getDouble("referenceMean")
            val hypothesis = ((expected-16)*255/219).coerceIn(0.0,255.0)
            if (p.getInt("nominal") >= 224) { correct += abs(mean-expected); expanded += abs(mean-hypothesis); highCount++; whiteCount += clipped }
            val patchObj = JSONObject().put("nominal", p.getInt("nominal")).put("referenceMean", expected).put("observedMean", mean)
                .put("whiteFraction", clipped.toDouble()/count).put("adjacentDelta", if (i>0) mean-rows.getJSONObject(i-1).getDouble("observedMean") else JSONObject.NULL)
            val matchingRaw = rawSamples.firstOrNull { (it["nominal"] == p.getInt("nominal")) || (it["patchIndex"] == i) }
            if (matchingRaw != null) {
                patchObj.put("rawYuv", JSONObject()
                    .put("yCode", matchingRaw["yCode"])
                    .put("uCode", matchingRaw["uCode"])
                    .put("vCode", matchingRaw["vCode"])
                    .put("crop", matchingRaw["crop"])
                    .put("frameId", matchingRaw["frameId"] ?: JSONObject.NULL)
                    .put("instanceId", matchingRaw["instanceId"] ?: JSONObject.NULL)
                )
            }
            rows.put(patchObj)
        }
        val local=JSONArray()
        for(i in 0 until 8){
            fun mean(y:Int):Double{var total=0.0;var n=0
                for(x in i*240+32 until i*240+208 step 8){val p=bitmap.getPixel(x,y);total+=((p ushr 16 and 255)+(p ushr 8 and 255)+(p and 255))/3.0;n++};return total/n}
            val a=mean(360);val b=mean(440)
            local.put(JSONObject().put("region",i).put("foregroundMean",a).put("backgroundMean",b).put("localContrastCodes",abs(a-b)))
        }
        return JSONObject().put("contentLocalContrast",local).put("available", true).put("patches", rows)
            .put("correctModelMeanAbsoluteError", correct/highCount).put("expandedLimitedModelMeanAbsoluteError", expanded/highCount)
            .put("h1Priority", if (expanded+highCount*2 < correct) "repeat_limited_expansion_shape_supported" else "not_supported_by_model_comparison")
            .put("nearWhiteClippedSampleCount", whiteCount).put("thresholdCalibration", "unvalidated_numeric_hypothesis_only")
            .put("mergedGroupVerdict", "requires_calibrated_tolerance; raw_adjacent_deltas_provided")
            .put("colorPass", JSONObject.NULL)
            .put("responsibleLayer", if (rawSamples.isNotEmpty()) "source_reference -> raw_yuv_sample -> screenshot_rgb" else "not_localized_by_screenshot_alone")
            .put("rawYuvAvailable", rawSamples.isNotEmpty())
    }
}
