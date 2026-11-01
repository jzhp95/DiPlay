package com.shilapi.xcertplay.media.video

/**
 * 颜色决策结果。
 */
sealed interface ColorDecision {
    /** 已接受并决议的像素契约与色彩转换 */
    data class Accepted(
        val contract: ResolvedPixelContract,
        val transform: VideoColorTransform
    ) : ColorDecision

    /** 未经核验或存在冲突的组合，提供降级兜底方案与未核验原因 */
    data class Unverified(
        val reason: String,
        val fallbackContract: ResolvedPixelContract,
        val fallbackTransform: VideoColorTransform
    ) : ColorDecision

    /** 管线明确不支持的色彩配置（如 >8bit 高位深或 HDR） */
    data class Unsupported(
        val reason: String
    ) : ColorDecision
}

/**
 * 针对特定设备与组件的色彩资格档案。
 */
data class ColorQualification(
    val deviceModel: String = "",
    val verifiedContracts: Map<String, ResolvedPixelContract> = emptyMap()
)

/**
 * 像素契约决策器 (PixelContractResolver)。
 * 严格按照 Spec §13 决策顺序处理源声明、输出标签、资格档案与策略覆盖。
 */
object PixelContractResolver {

    fun resolve(
        source: SourceColorDeclaration,
        output: DecoderOutputDescription,
        target: RenderTargetDescription,
        policy: VideoRuntimePolicy,
        qualification: ColorQualification? = null
    ): ColorDecision {
        // 1. 高位深 / HDR 保护：本阶段 8-bit SDR 管线明确拒绝
        if (source.bitDepth > 8 || output.bitDepth > 8) {
            return ColorDecision.Unsupported(
                "Bit depth > 8 (source=${source.bitDepth}, output=${output.bitDepth}) is unsupported in SDR pipeline"
            )
        }

        // 2. 构造适用性资格键
        val applicabilityKey = "${source.origin}:${output.actualComponent}:${source.range}:${source.matrix}:${source.bitDepth}"

        // 3. 手动策略覆盖（受控实验组，不作为默认修复）
        when (policy.colorPolicy) {
            ColorPolicy.FORCE_LIMITED -> {
                val contract = ResolvedPixelContract(
                    effectiveRange = ColorRange.LIMITED,
                    effectiveMatrix = if (source.matrix != ColorMatrix.UNKNOWN) source.matrix else ColorMatrix.BT601,
                    effectiveTransfer = if (source.transfer != ColorTransfer.UNKNOWN) source.transfer else ColorTransfer.SRGB,
                    effectivePrimaries = if (source.primaries != ColorPrimaries.UNKNOWN) source.primaries else ColorPrimaries.BT709,
                    evidenceBasis = "MANUAL_POLICY_FORCE_LIMITED",
                    confidence = Confidence.LOW,
                    applicabilityKey = applicabilityKey,
                    conflictReasons = emptyList()
                )
                return ColorDecision.Accepted(contract, VideoColorTransform(contract.effectiveRange, contract.effectiveMatrix))
            }
            ColorPolicy.FORCE_FULL -> {
                val contract = ResolvedPixelContract(
                    effectiveRange = ColorRange.FULL,
                    effectiveMatrix = if (source.matrix != ColorMatrix.UNKNOWN) source.matrix else ColorMatrix.BT601,
                    effectiveTransfer = if (source.transfer != ColorTransfer.UNKNOWN) source.transfer else ColorTransfer.SRGB,
                    effectivePrimaries = if (source.primaries != ColorPrimaries.UNKNOWN) source.primaries else ColorPrimaries.BT709,
                    evidenceBasis = "MANUAL_POLICY_FORCE_FULL",
                    confidence = Confidence.LOW,
                    applicabilityKey = applicabilityKey,
                    conflictReasons = emptyList()
                )
                return ColorDecision.Accepted(contract, VideoColorTransform(contract.effectiveRange, contract.effectiveMatrix))
            }
            ColorPolicy.NATIVE_PASSTHROUGH,
            ColorPolicy.FOLLOW_CONTRACT -> {
                // 继续标准决议
            }
        }

        // 4. 优先检查已核验的资格档案
        if (qualification != null) {
            val verified = qualification.verifiedContracts[applicabilityKey]
            if (verified != null) {
                return ColorDecision.Accepted(
                    verified.copy(confidence = Confidence.VERIFIED),
                    VideoColorTransform(verified.effectiveRange, verified.effectiveMatrix)
                )
            }
        }

        // 5. 检查源端与输出端的冲突
        val conflicts = mutableListOf<String>()
        if (source.range != ColorRange.UNKNOWN && output.reportedRange != ColorRange.UNKNOWN && source.range != output.reportedRange) {
            conflicts.add("Range conflict: source declares ${source.range} but decoder reports ${output.reportedRange}")
        }
        if (source.matrix != ColorMatrix.UNKNOWN && output.reportedStandard != ColorMatrix.UNKNOWN && source.matrix != output.reportedStandard) {
            conflicts.add("Matrix conflict: source declares ${source.matrix} but decoder reports ${output.reportedStandard}")
        }

        if (conflicts.isNotEmpty()) {
            val fallback = ResolvedPixelContract(
                effectiveRange = ColorRange.LIMITED,
                effectiveMatrix = if (source.matrix != ColorMatrix.UNKNOWN) source.matrix else ColorMatrix.BT601,
                effectiveTransfer = ColorTransfer.SRGB,
                effectivePrimaries = ColorPrimaries.BT709,
                evidenceBasis = "CONFLICT_FALLBACK",
                confidence = Confidence.LOW,
                applicabilityKey = applicabilityKey,
                conflictReasons = conflicts
            )
            return ColorDecision.Unverified(
                reason = "Conflicts detected: ${conflicts.joinToString("; ")}",
                fallbackContract = fallback,
                fallbackTransform = VideoColorTransform(fallback.effectiveRange, fallback.effectiveMatrix)
            )
        }

        // 6. 无冲突标准决议：源声明优先，缺失则使用解码器输出报告，最终降级至默认 Limited + BT.601
        val effRange = when {
            source.range != ColorRange.UNKNOWN -> source.range
            output.reportedRange != ColorRange.UNKNOWN -> output.reportedRange
            else -> ColorRange.LIMITED
        }

        val effMatrix = when {
            source.matrix != ColorMatrix.UNKNOWN -> source.matrix
            output.reportedStandard != ColorMatrix.UNKNOWN -> output.reportedStandard
            else -> ColorMatrix.BT601
        }

        val effTransfer = when {
            source.transfer != ColorTransfer.UNKNOWN -> source.transfer
            output.reportedTransfer != ColorTransfer.UNKNOWN -> output.reportedTransfer
            else -> ColorTransfer.SRGB
        }

        val effPrimaries = when {
            source.primaries != ColorPrimaries.UNKNOWN -> source.primaries
            else -> ColorPrimaries.BT709
        }

        val isFullyResolved = source.range != ColorRange.UNKNOWN && source.matrix != ColorMatrix.UNKNOWN
        val confidence = if (isFullyResolved) Confidence.HIGH else Confidence.MEDIUM
        val basis = if (source.range != ColorRange.UNKNOWN) "SOURCE_SPS_VUI" else "DECODER_OUTPUT_INFERENCE"

        val contract = ResolvedPixelContract(
            effectiveRange = effRange,
            effectiveMatrix = effMatrix,
            effectiveTransfer = effTransfer,
            effectivePrimaries = effPrimaries,
            evidenceBasis = basis,
            confidence = confidence,
            applicabilityKey = applicabilityKey,
            conflictReasons = emptyList()
        )

        return ColorDecision.Accepted(contract, VideoColorTransform(contract.effectiveRange, contract.effectiveMatrix))
    }
}
