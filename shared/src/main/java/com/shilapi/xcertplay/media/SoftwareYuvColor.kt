package com.shilapi.xcertplay.media

import android.media.MediaFormat

/** Main-screen-only experiment. Zero follows metadata; manual choices affect rendering, not decoding. */
data class SoftwareYuvConfig(val range: Int = 0, val matrix: Int = 0) {
    init { require(range in 0..2); require(matrix in 0..2) }
}

internal data class YuvColorTransform(
    val fullRange: Boolean, val bt709: Boolean, val rangeSource: String, val matrixSource: String,
) {
    val offset = floatArrayOf(if (fullRange) 0f else 16f / 255f, 128f / 255f, 128f / 255f)
    private val kr = if (bt709) 0.2126f else 0.299f
    private val kb = if (bt709) 0.0722f else 0.114f
    private val kg = 1f - kr - kb
    private val ys = if (fullRange) 1f else 255f / 219f
    private val cs = if (fullRange) 1f else 255f / 224f
    /** Column-major, shared by the GLES shader and reference-vector tests. */
    val coefficients = floatArrayOf(
        ys, ys, ys,
        0f, -2f * kb * (1f - kb) / kg * cs, 2f * (1f - kb) * cs,
        2f * (1f - kr) * cs, -2f * kr * (1f - kr) / kg * cs, 0f,
    )
    val description get() = "range=${if (fullRange) "full" else "limited"}($rangeSource) " +
        "matrix=${if (bt709) "BT709" else "BT601"}($matrixSource)"
}

internal object SoftwareYuvColor {
    fun resolve(config: SoftwareYuvConfig, source: VideoColorMetadata.Description?, output: MediaFormat?): YuvColorTransform {
        fun field(key: String): Int? = runCatching {
            if (output?.containsKey(key) == true) output.getInteger(key) else null
        }.getOrNull()
        val declared = source?.takeIf { it.status == VideoColorMetadata.Status.PARSED }
        require(declared?.transfer !in listOf(16, 18) &&
            field(MediaFormat.KEY_COLOR_TRANSFER) !in listOf(6, 7)) { "HDR transfer is not supported by the SDR YUV renderer" }
        require(declared?.primaries !in listOf(9, 10, 11, 12) &&
            field(MediaFormat.KEY_COLOR_STANDARD) != 6) { "wide-gamut YUV is not supported" }
        require(declared?.matrix == null || declared.matrix in listOf(1, 2, 5, 6)) {
            "unsupported declared YUV matrix ${declared?.matrix}"
        }
        val decoderRange = field(MediaFormat.KEY_COLOR_RANGE)?.takeIf { it in 1..2 }
        val decoderStandard = field(MediaFormat.KEY_COLOR_STANDARD)?.takeIf { it in listOf(1, 2, 4) }
        val full = when {
            config.range != 0 -> config.range == 1
            declared?.fullRange != null -> declared.fullRange
            decoderRange != null -> decoderRange == 1
            else -> false
        }
        val rangeSource = when {
            config.range != 0 -> "manual"
            declared?.fullRange != null -> "SPS"
            decoderRange != null -> "decoder"
            else -> "assumed-limited"
        }
        val spsMatrix = declared?.matrix?.takeIf { it in listOf(1, 5, 6) }
        val bt709 = when {
            config.matrix != 0 -> config.matrix == 1
            spsMatrix != null -> spsMatrix == 1
            decoderStandard != null -> decoderStandard == 1
            else -> true
        }
        val matrixSource = when {
            config.matrix != 0 -> "manual"
            spsMatrix != null -> "SPS"
            decoderStandard != null -> "decoder"
            else -> "assumed-BT709"
        }
        return YuvColorTransform(full, bt709, rangeSource, matrixSource)
    }
}
