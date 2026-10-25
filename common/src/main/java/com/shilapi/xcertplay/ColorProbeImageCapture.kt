package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Raw decoded texture/buffer and, for TextureView only, a separate filtered window capture. */
internal object ColorProbeImageCapture {
    fun capture(activity: Activity, texture: TextureView?, surface: SurfaceView?, nativeSize: Boolean = false,
        directory: File? = null, done: (List<String>, JSONObject) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val names = mutableListOf<String>()
        val status = JSONObject()
        val prefix = "observation-${UUID.randomUUID()}"
        var finished = false
        fun finish() { if (!finished) { finished = true; done(names.toList(), status) } }
        fun save(bitmap: Bitmap, suffix: String): Boolean {
            return try {
                if (finished) return false
                val file = File(directory ?: ColorProbeEvidence.directory(activity), "$prefix-$suffix.png")
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                names.add(file.name); status.put(suffix, file.name); true
            } catch (error: Exception) { status.put("${suffix}_error", error.javaClass.simpleName); false }
            finally { bitmap.recycle() }
        }
        main.postDelayed({ status.put("timeout", true); finish() }, 3_000)
        if (texture != null && texture.width > 0 && texture.height > 0) {
            val (w, h) = captureSize(texture.width, texture.height, nativeSize)
            status.put("sourceViewWidth", texture.width).put("sourceViewHeight", texture.height)
                .put("captureWidth", w).put("captureHeight", h).put("scaled", w != texture.width || h != texture.height)
            runCatching { texture.getBitmap(w, h) }.getOrNull()?.let { save(it, "decoded-texture") }
            val visible = Rect()
            if (!texture.getGlobalVisibleRect(visible) || visible.width() != texture.width || visible.height() != texture.height) {
                status.put("composited_window_error", "preview_not_fully_visible"); finish(); return
            }
            val location = IntArray(2); texture.getLocationInWindow(location)
            val rect = Rect(location[0], location[1], location[0] + texture.width, location[1] + texture.height)
            val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(activity.window, rect, image, { result ->
                    if (result == PixelCopy.SUCCESS) save(image, "composited-window")
                    else { image.recycle(); status.put("composited_window_error", result) }
                    finish()
                }, main)
            } catch (error: Exception) { image.recycle(); status.put("composited_window_error", error.javaClass.simpleName); finish() }
        } else if (surface != null && surface.holder.surface.isValid && surface.width > 0 && surface.height > 0) {
            val (w, h) = captureSize(surface.width, surface.height, nativeSize)
            status.put("sourceViewWidth", surface.width).put("sourceViewHeight", surface.height)
                .put("captureWidth", w).put("captureHeight", h).put("scaled", w != surface.width || h != surface.height)
            val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            status.put("surface_capture_limit", "buffer only; does not measure hardware-composer or panel postprocessing")
            try {
                PixelCopy.request(surface, image, { result ->
                    if (result == PixelCopy.SUCCESS) save(image, "surface-buffer")
                    else { image.recycle(); status.put("surface_buffer_error", result) }
                    finish()
                }, main)
            } catch (error: Exception) { image.recycle(); status.put("surface_buffer_error", error.javaClass.simpleName); finish() }
        } else { status.put("error", "no_ready_video_view"); finish() }
    }
    fun captureSize(width: Int, height: Int, nativeSize: Boolean): Pair<Int, Int> {
        require(width > 0 && height > 0)
        if (!nativeSize) return 640 to (640L * height / width).toInt().coerceIn(1, 720)
        val scale = minOf(1.0, 3840.0 / width, 2160.0 / height)
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

}
