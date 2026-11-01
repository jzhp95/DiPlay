package com.shilapi.xcertplay.media.video

/**
 * 视频像素色彩变换器。
 * 封装 8-bit SDR YUV 到 RGB 空间的数学归一化、范围重映射与色彩空间转换矩阵。
 * 输出供 CPU 软件参考计算与 GLES2 Shader Uniform 使用的统一系数。
 */
class VideoColorTransform(
    val range: ColorRange,
    val matrix: ColorMatrix
) {
    // 亮度与色度归一化参数
    val yOffset: Float
    val yCoeff: Float
    val cOffset: Float
    val cCoeff: Float

    // 3x3 色彩转换矩阵 (Column-major 供 OpenGL ES 2.0 uniformMatrix3fv 直接使用)
    val colorMatrixColumnMajor: FloatArray

    // 3x3 色彩转换矩阵 (Row-major 供 CPU 计算)
    val colorMatrixRowMajor: FloatArray

    init {
        when (range) {
            ColorRange.FULL -> {
                yOffset = 0.0f
                yCoeff = 1.0f
                cOffset = 128.0f / 255.0f
                cCoeff = 1.0f
            }
            ColorRange.LIMITED,
            ColorRange.UNKNOWN -> {
                // Limited: Y in [16, 235] -> span 219; Cb/Cr in [16, 240] -> span 224
                yOffset = 16.0f / 255.0f
                yCoeff = 255.0f / 219.0f
                cOffset = 128.0f / 255.0f
                cCoeff = 255.0f / 224.0f
            }
        }

        // 依据 ITU-R 标准定义 YUV -> RGB 矩阵系数
        // R = Y + CrCoeff * Cr
        // G = Y - CbCoeffG * Cb - CrCoeffG * Cr
        // B = Y + CbCoeffB * Cb
        val (crR, cbG, crG, cbB) = when (matrix) {
            ColorMatrix.BT709 -> Quad(1.5748f, 0.187324f, 0.468124f, 1.8556f)
            ColorMatrix.BT2020 -> Quad(1.4746f, 0.164553f, 0.571353f, 1.8814f)
            ColorMatrix.BT601,
            ColorMatrix.UNKNOWN -> Quad(1.402f, 0.344136f, 0.714136f, 1.772f)
        }

        colorMatrixRowMajor = floatArrayOf(
            1.0f,  0.0f,  crR,
            1.0f, -cbG,  -crG,
            1.0f,  cbB,   0.0f
        )

        // OpenGL ES mat3 是 column-major
        colorMatrixColumnMajor = floatArrayOf(
            1.0f,  1.0f, 1.0f,
            0.0f, -cbG,  cbB,
            crR,  -crG,  0.0f
        )
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    /**
     * 将原始 8-bit YUV 整数码值 (0~255) 转换为经过归一化与色彩矩阵处理的 RGB 浮点分量 [0.0f, 1.0f]。
     */
    fun transformYuv8(y: Int, u: Int, v: Int): FloatArray {
        val yIn = y.toFloat() / 255.0f
        val uIn = u.toFloat() / 255.0f
        val vIn = v.toFloat() / 255.0f

        val yNorm = (yIn - yOffset) * yCoeff
        val uNorm = (uIn - cOffset) * cCoeff
        val vNorm = (vIn - cOffset) * cCoeff

        val r = colorMatrixRowMajor[0] * yNorm + colorMatrixRowMajor[1] * uNorm + colorMatrixRowMajor[2] * vNorm
        val g = colorMatrixRowMajor[3] * yNorm + colorMatrixRowMajor[4] * uNorm + colorMatrixRowMajor[5] * vNorm
        val b = colorMatrixRowMajor[6] * yNorm + colorMatrixRowMajor[7] * uNorm + colorMatrixRowMajor[8] * vNorm

        return floatArrayOf(
            clamp(r, 0.0f, 1.0f),
            clamp(g, 0.0f, 1.0f),
            clamp(b, 0.0f, 1.0f)
        )
    }

    private fun clamp(v: Float, min: Float, max: Float): Float =
        if (v < min) min else if (v > max) max else v
}
