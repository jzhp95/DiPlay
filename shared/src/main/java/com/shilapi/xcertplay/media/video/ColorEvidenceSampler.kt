package com.shilapi.xcertplay.media.video

/**
 * 颜色取证采样请求。
 */
data class SampleRequest(
    val points: List<SamplePoint>,
    val runId: String = "",
    val caseName: String = "",
    val maxSamples: Int = 64
)

/**
 * 生产同帧颜色采样器 (ColorEvidenceSampler)。
 * 严格在生产 EGL 渲染线程持有有效帧租约、且在纹理上传前执行，
 * 直接提取同实例像素样点，绝不通过启动额外 decoder 伪造采样。
 */
object ColorEvidenceSampler {

    fun sampleBeforeUpload(
        frame: DecodedFrameLease,
        decision: ColorDecision,
        request: SampleRequest
    ): SampleResult {
        val memoryFrame = frame as? MemoryDecodedFrameLease
            ?: throw IllegalArgumentException("Frame lease must be MemoryDecodedFrameLease, got ${frame::class.java.simpleName}")

        if (memoryFrame.isReleased()) {
            throw IllegalStateException("Cannot sample color evidence: frame lease has already been released")
        }

        // 限频与点数保护：每帧最多 64 点
        val boundedPoints = request.points.take(request.maxSamples)
        return YuvPlaneReader.sample(memoryFrame, boundedPoints)
    }
}
