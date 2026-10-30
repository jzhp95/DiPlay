package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import org.json.JSONArray
import org.json.JSONObject

/** Device enumeration and the independent GPU probe run on the collector worker, outside timed samples. */
internal object VideoAdaptationDevice {
    @Suppress("DEPRECATION")
    fun screen(activity: Activity): JSONObject {
        val display = activity.windowManager.defaultDisplay
        val real = DisplayMetrics(); display.getRealMetrics(real)
        val logical = DisplayMetrics(); display.getMetrics(logical)
        val modes = JSONArray()
        display.supportedModes.forEach { modes.put(JSONObject().put("id", it.modeId)
            .put("width", it.physicalWidth).put("height", it.physicalHeight).put("refreshRate", it.refreshRate)) }
        return JSONObject().put("realWidth", real.widthPixels).put("realHeight", real.heightPixels)
            .put("logicalWidth", logical.widthPixels).put("logicalHeight", logical.heightPixels)
            .put("densityDpi", real.densityDpi).put("reportedXdpi", real.xdpi).put("reportedYdpi", real.ydpi)
            .put("physicalSizeBasis", "system_reported_dpi_not_measured_inches")
            .put("activeMode", display.mode.modeId).put("refreshRate", display.refreshRate).put("modes", modes)
    }
    fun collect(context: Context, screen: JSONObject, progress: (JSONObject) -> Unit = {}): JSONObject {
        val basic = JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("hardware", Build.HARDWARE).put("board", Build.BOARD).put("android", Build.VERSION.RELEASE)
            .put("api", Build.VERSION.SDK_INT).put("screen", screen).put("collectionIncomplete", true)
        progress(JSONObject(basic.toString()))
        val system = runCatching { DecoderSystemInformation.collect(context) }.getOrElse { JSONObject().put("error", it.javaClass.simpleName) }
        basic.put("systemInformation", system); progress(JSONObject(basic.toString()))
        val missing = JSONArray()
        val codecs = JSONArray()
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }.forEach { info ->
                for (mime in info.supportedTypes.filter { it.startsWith("video/", true) }) {
                    val entry = JSONObject().put("name", info.name).put("mime", mime)
                    try {
                        val cap = info.getCapabilitiesForType(mime)
                        val video = cap.videoCapabilities
                        entry.put("maxSupportedInstances", cap.maxSupportedInstances)
                            .put("adaptivePlayback", cap.isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback))
                            .put("colorFormats", JSONArray(cap.colorFormats.toList()))
                            .put("profileLevels", JSONArray(cap.profileLevels.map { JSONObject().put("profile", it.profile).put("level", it.level) }))
                        if (video != null) entry.put("widthRange", video.supportedWidths.toString())
                            .put("heightRange", video.supportedHeights.toString()).put("fpsRange", video.supportedFrameRates.toString())
                            .put("widthAlignment", video.widthAlignment).put("heightAlignment", video.heightAlignment)
                            .put("supportsDisplayAt30", video.areSizeAndRateSupported(screen.getInt("realWidth"), screen.getInt("realHeight"), 30.0))
                            .put("supportsDisplayAt60", video.areSizeAndRateSupported(screen.getInt("realWidth"), screen.getInt("realHeight"), 60.0))
                        if (Build.VERSION.SDK_INT >= 29) {
                            entry.put("softwareOnly", info.isSoftwareOnly).put("hardwareAccelerated", info.isHardwareAccelerated)
                                .put("classificationBasis", "platform")
                        } else {
                            val knownSoftware = info.name.startsWith("OMX.google.", true) || info.name.startsWith("c2.android.", true)
                            entry.put("softwareOnly", if (knownSoftware) true else JSONObject.NULL)
                                .put("hardwareAccelerated", JSONObject.NULL)
                                .put("classificationBasis", "API28_known_software_prefix_only_others_unknown")
                        }
                    } catch (e: Exception) { entry.put("error", e.javaClass.simpleName) }
                    codecs.put(entry)
                }
            }
        }.onFailure { missing.put("codec_enumeration:${it.javaClass.simpleName}") }
        if (codecs.length() == 0) missing.put("no_avc_hevc_capabilities")
        basic.put("decoders", codecs); progress(JSONObject(basic.toString()))
        val gpu = runCatching { gpu() }.getOrElse {
            missing.put("gpu:${it.javaClass.simpleName}"); JSONObject().put("unavailable", it.javaClass.simpleName)
        }
        val memory = ActivityManager.MemoryInfo()
        runCatching { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory) }
        return JSONObject().put("schema", 1).put("collectedAt", System.currentTimeMillis())
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("device", Build.DEVICE)
            .put("hardware", Build.HARDWARE).put("board", Build.BOARD).put("firmware", Build.DISPLAY)
            .put("android", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("screen", screen)
            .put("availableProcessors", Runtime.getRuntime().availableProcessors())
            .put("memoryTotalBytes", memory.totalMem).put("memoryAvailableBytes", memory.availMem)
            .put("collectionIncomplete", false).put("systemInformation", system).put("gpu", gpu).put("decoders", codecs).put("thermal", thermal(context)).put("missing", missing)
    }
    fun thermal(context: Context): JSONObject {
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val temperature = battery?.getIntExtra("temperature", Int.MIN_VALUE) ?: Int.MIN_VALUE
        return JSONObject().put("batteryCelsius", if (temperature in 1..1000) temperature / 10.0 else JSONObject.NULL)
            .put("batteryRawTenthsCelsius", if (temperature != Int.MIN_VALUE) temperature else JSONObject.NULL)
            .put("batteryTemperatureValid", temperature in 1..1000)
            .put("cpuGpuTemperature", "unavailable_without_verified_sensor_mapping")
            .put("thermalStatus", if (Build.VERSION.SDK_INT >= 29)
                runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus }.getOrNull() ?: JSONObject.NULL
                else "unavailable_on_API28")
    }
    private fun gpu(): JSONObject {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        var surface = EGL14.EGL_NO_SURFACE
        var context = EGL14.EGL_NO_CONTEXT
        try {
            val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context))
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            check(!vendor.isNullOrBlank() && !renderer.isNullOrBlank()) { "GPU strings unavailable" }
            return JSONObject().put("vendor", vendor).put("renderer", renderer)
                .put("version", GLES20.glGetString(GLES20.GL_VERSION) ?: JSONObject.NULL)
                .put("extensions", GLES20.glGetString(GLES20.GL_EXTENSIONS)?.take(16_384) ?: JSONObject.NULL)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display); EGL14.eglReleaseThread()
        }
    }
}
