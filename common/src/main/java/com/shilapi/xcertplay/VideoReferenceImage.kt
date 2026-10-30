package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Small, fixed sRGB reference. Source presentation is never inferred from sharing this file. */
internal object VideoReferenceImage {
    private val grays = intArrayOf(0, 16, 32, 64, 128, 192, 235, 255)
    private val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.WHITE, Color.BLACK)
    fun bytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { isAntiAlias = false }
            for (i in 0..7) {
                paint.color = Color.rgb(grays[i], grays[i], grays[i]); canvas.drawRect(i * 120f, 0f, (i + 1) * 120f, 270f, paint)
                paint.color = colors[i]; canvas.drawRect(i * 120f, 270f, (i + 1) * 120f, 540f, paint)
            }
            return ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
    fun manifest(bytes: ByteArray): JSONObject {
        val patches = JSONArray()
        for (row in 0..1) for (i in 0..7) {
            val rgb = if (row == 0) Color.rgb(grays[i], grays[i], grays[i]) else colors[i]
            patches.put(JSONObject().put("rect", JSONArray(listOf(i * 120, row * 270, 120, 270)))
                .put("rgb", JSONArray(listOf(Color.red(rgb), Color.green(rgb), Color.blue(rgb)))))
        }
        return JSONObject().put("id", VideoTroubleshooting.REFERENCE_ID).put("width", 960).put("height", 540)
            .put("colorSpace", "sRGB").put("patches", patches)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("scope", "reference_file_pixels_only; source_app_color_management_and_physical_panel_unverified")
    }
    fun file(context: Context): File {
        val dir = File(context.filesDir, "diagnostic-reports").apply { mkdirs() }
        return File(dir, "diplay-rgb-v1.png").apply { writeBytes(bytes()) }
    }
}
