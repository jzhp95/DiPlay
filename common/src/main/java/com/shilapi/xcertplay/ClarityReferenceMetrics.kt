package com.shilapi.xcertplay

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Measures known reference stripes in the decoded bitmap, not panel MTF or subjective sharpness. */
internal object ClarityReferenceMetrics {
    fun measure(bitmap: Bitmap): JSONObject {
        val value = JSONObject().put("bitmapWidth", bitmap.width).put("bitmapHeight", bitmap.height)
            .put("scope", "captured_bitmap_stripe_contrast_may_include_preview_resampling_not_decoder_only_or_physical_panel_pass")
        if (bitmap.width != 1920 || bitmap.height != 1080)
            return value.put("available", false).put("reason", "reference_capture_not_1920x1080")
        val bands = JSONArray()
        for ((band, step) in listOf(1, 2, 4, 8).withIndex()) {
            var light = 0.0; var dark = 0.0; var lightCount = 0; var darkCount = 0
            for (y in listOf(240, 300, 360)) for (x in (band * 480 + 32) until (band * 480 + 448)) {
                val pixel = bitmap.getPixel(x, y)
                val luma = ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3.0
                if (x / step % 2 == 0) { dark += luma; darkCount++ } else { light += luma; lightCount++ }
            }
            val white = light / lightCount; val black = dark / darkCount
            bands.put(JSONObject().put("stripeWidthPixels", step).put("meanWhite", white).put("meanBlack", black)
                .put("contrastCodeValues", abs(white - black)))
        }
        return value.put("available", true).put("bands", bands).put("visualVerdict", "not_automatically_determined")
    }
}
