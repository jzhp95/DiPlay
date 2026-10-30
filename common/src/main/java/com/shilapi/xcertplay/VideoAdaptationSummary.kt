package com.shilapi.xcertplay

import org.json.JSONObject

internal object VideoAdaptationSummary {
    fun device(value: JSONObject?): String = buildString {
        if (value == null) { append("设备信息尚未读取"); return@buildString }
        fun text(v: JSONObject?, key: String) = v?.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString() ?: "未读取/系统未报告"
        appendLine("设备：${text(value, "manufacturer")} ${text(value, "model")}")
        appendLine("系统：Android ${text(value, "android")} / API ${text(value, "api")}")
        appendLine("固件：${text(value, "firmware")}")
        appendLine("硬件：${text(value, "hardware")}；板卡：${text(value, "board")}")
        appendLine("内存总量：${text(value, "memoryTotalBytes")} B；可用：${text(value, "memoryAvailableBytes")} B")
        appendLine("CPU 核数：${text(value, "availableProcessors")}；ABI：${text(value, "abis")}")
        val screen = value.optJSONObject("screen")
        appendLine("屏幕像素：${text(screen, "realWidth")} × ${text(screen, "realHeight")}")
        appendLine("逻辑区域：${text(screen, "logicalWidth")} × ${text(screen, "logicalHeight")}")
        appendLine("刷新率：${text(screen, "refreshRate")} Hz；DPI：${text(screen, "densityDpi")}")
        appendLine("屏幕模式：${text(screen, "modes")}")
        appendLine("物理 DPI 是系统声明，不代表实测英寸。")
        val gpu = value.optJSONObject("gpu")
        appendLine("GPU：${text(gpu, "vendor")} / ${text(gpu, "renderer")}")
        appendLine("OpenGL：${text(gpu, "version")}")
        val decoders = value.optJSONArray("decoders")
        appendLine("视频解码器：")
        if (decoders == null || decoders.length() == 0) appendLine("系统未报告")
        else for (i in 0 until decoders.length()) {
            val item = decoders.getJSONObject(i)
            appendLine("${text(item, "name")} · ${text(item, "mime")}")
            appendLine("宽 ${text(item, "widthRange")} / 高 ${text(item, "heightRange")} / 帧率 ${text(item, "fpsRange")}")
            appendLine("像素对齐：${text(item, "widthAlignment")} × ${text(item, "heightAlignment")}")
            appendLine("颜色格式：${text(item, "colorFormats")}；Profile/Level：${text(item, "profileLevels")}")
            appendLine("软件类别：${text(item, "softwareOnly")}；硬件加速：${text(item, "hardwareAccelerated")}")
        }
        appendLine("温度：${text(value, "thermal")}")
        appendLine("不可读项：${text(value, "missing")}")
        appendLine("扩展系统与软硬件信息：${text(value, "systemInformation")}")
        appendLine("信息采集尚未完成：${text(value, "collectionIncomplete")}")
    }
    fun latest(state: JSONObject): String = buildString {
        val rows = state.optJSONArray("results")
        val row = rows?.optJSONObject(rows.length() - 1) ?: return@buildString
        val e = row.optJSONObject("evidence") ?: return@buildString
        appendLine("最近完成项目：${row.optString("title")} · ${row.optString("verdict")}")
        appendLine("视频格式：${e.opt("codec")}；解码器：${e.opt("selected_decoder")}")
        appendLine("输出格式（含尺寸/裁剪/颜色）：${e.opt("output_format")}")
        appendLine("输入颜色声明：${e.opt("input_color")}")
        appendLine("输入配置长度：${e.opt("input_csd_size")} B；完整配置在整包报告内。")
        appendLine("请求路径：${e.opt("requested")}")
        appendLine("实际输出路径：${e.opt("actual_output_path")}")
        appendLine("释放确认：${e.opt("teardown")}")
        val window = e.optJSONObject("automatic_window")
        val begin = window?.optJSONObject("begin")
        if (begin != null) {
            appendLine("请求画布：${begin.opt("requestedCanvasWidth")} × ${begin.opt("requestedCanvasHeight")}")
            appendLine("主屏视口：${begin.opt("viewWidth")} × ${begin.opt("viewHeight")}")
            appendLine("Surface 缓冲：${begin.opt("surfaceBufferWidth")} × ${begin.opt("surfaceBufferHeight")}")
            appendLine("传输：${begin.opt("transport")}；温度：${begin.opt("thermal")}")
        }
        appendLine("本段性能：${window?.opt("summary") ?: "标准片段项目；详见解码日志"}")
        appendLine("缺项：${e.opt("missing")}")
    }

}
