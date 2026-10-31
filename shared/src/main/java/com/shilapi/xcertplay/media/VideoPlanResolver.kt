package com.shilapi.xcertplay.media

import java.security.MessageDigest

/** Pure policy. It never queries Android capabilities, views or preferences. */
object VideoPlanResolver {
    fun surface(hardwareAccelerated: Boolean, smooth: Boolean, direct: Boolean, textureUnsafe: Boolean): VideoOutput =
        if (hardwareAccelerated && !smooth && !direct && !textureUnsafe) VideoOutput.TEXTURE else VideoOutput.SURFACE

    fun colorDecision(request: VideoPipelineRequest): VideoColorDecision = when {
        request.color.fromSps -> VideoColorDecision("from_sps", request.color, "explicit_sps_color_requested", "sps_vui_hints")
        request.color.range != null || request.color.standard != null -> VideoColorDecision("manual_override", request.color, "manual_override_requested", "manual_color_settings")
        request.hardwareYuv || request.softwareYuv != null -> VideoColorDecision("app_yuv_correction", request.color, "yuv_color_matrix_resolved", "yuv_color_pipeline")
        else -> VideoColorDecision("decoder_default", request.color, "decoder_default_stream_metadata", "stream_metadata")
    }

    fun resolve(request: VideoPipelineRequest, capabilities: VideoCapabilities = VideoCapabilities()): VideoPipelinePlan {
        require(request.width > 0 && request.height > 0 && request.fps >= 0)
        val hardware = request.hardwareYuv
        val softwareDirect = !hardware && (request.softwareDirect || request.softwareYuv != null)
        val smooth = !hardware && !softwareDirect && request.smooth
        val direct = hardware || softwareDirect || request.direct
        val yuv = request.softwareYuv.takeUnless { hardware }
        val decode = when {
            hardware -> VideoDecodePolicy.HARDWARE_YUV
            softwareDirect || request.forceSoftware -> VideoDecodePolicy.STRICT_SOFTWARE
            else -> VideoDecodePolicy.SYSTEM_DEFAULT
        }
        val output = if (hardware || yuv != null) VideoOutput.APP_YUV else
            surface(capabilities.hardwareAccelerated != false, smooth, direct, capabilities.textureUnsafe)
        val supported = when (decode) {
            VideoDecodePolicy.STRICT_SOFTWARE -> capabilities.softwareAvailable
            VideoDecodePolicy.HARDWARE_YUV -> capabilities.hardwareYuvAvailable
            VideoDecodePolicy.SYSTEM_DEFAULT -> null
        }
        val reason = when {
            supported == false -> "requested_decoder_path_unavailable"
            output == VideoOutput.SURFACE && capabilities.textureUnsafe -> "texture_unsafe_decoder"
            output == VideoOutput.SURFACE && capabilities.hardwareAccelerated == false -> "window_without_hardware_acceleration"
            else -> null
        }
        val colorDec = colorDecision(request)
        val canonical = listOf(
            request.codec, request.width, request.height, request.fps, request.smooth, request.direct,
            request.softwareDirect, request.forceSoftware, request.softwareYuv, request.hardwareYuv, request.purpose,
            request.color, request.pacingDelayMs, capabilities, output, decode, supported, reason, colorDec.policy
        ).joinToString("|")
        val id = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return VideoPipelinePlan(id, request, output, decode, smooth, direct, softwareDirect, yuv, hardware, supported, reason, colorDecision = colorDec)
    }
}
