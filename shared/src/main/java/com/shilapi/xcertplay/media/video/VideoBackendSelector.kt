package com.shilapi.xcertplay.media.video

/**
 * 颜色核验与资格证据。
 */
data class ColorEvidence(
    val backendKind: BackendKind,
    val componentName: String,
    val isQualified: Boolean,
    val qualificationReason: String = ""
)

/**
 * 解码与呈现后端选择结果。
 */
sealed interface SelectionResult {
    data class Selected(
        val backendKind: BackendKind,
        val decoderPreference: DecoderPreference,
        val reason: String
    ) : SelectionResult

    data class Rejected(
        val reason: String
    ) : SelectionResult
}

/**
 * 视频解码后端资格选择器 (VideoBackendSelector)。
 * 严禁“硬解必然最好”的盲目预设；在硬解未经验证或存在发白风险时，
 * 优先回退至“已验证的软件 YUV＋跟随视频”效果基线。
 */
object VideoBackendSelector {

    fun select(
        config: VideoStreamConfig,
        target: OutputTarget,
        policy: VideoRuntimePolicy,
        evidence: List<ColorEvidence> = emptyList()
    ): SelectionResult {
        // 1. 明确的用户策略指定（实验模式）
        policy.preferredBackend?.let { preferred ->
            when (preferred) {
                BackendKind.PLATFORM_SURFACE -> {
                    if (target.surface != null) {
                        return SelectionResult.Selected(
                            BackendKind.PLATFORM_SURFACE,
                            policy.decoderPreference,
                            "POLICY_EXPLICIT_PREFERENCE_SURFACE"
                        )
                    }
                }
                BackendKind.PLATFORM_YUV -> {
                    return SelectionResult.Selected(
                        BackendKind.PLATFORM_YUV,
                        policy.decoderPreference,
                        "POLICY_EXPLICIT_PREFERENCE_YUV"
                    )
                }
                BackendKind.NATIVE_YUV -> {
                    return SelectionResult.Selected(
                        BackendKind.NATIVE_YUV,
                        policy.decoderPreference,
                        "POLICY_EXPLICIT_PREFERENCE_NATIVE"
                    )
                }
            }
        }

        // 2. 合格硬解直出：必须 Surface 有效且具备明确的资格证据 (Qualified)
        val surfaceQualified = evidence.any {
            it.backendKind == BackendKind.PLATFORM_SURFACE && it.isQualified
        }
        if (target.surface != null && surfaceQualified) {
            return SelectionResult.Selected(
                BackendKind.PLATFORM_SURFACE,
                DecoderPreference.HARDWARE,
                "QUALIFIED_HARDWARE_SURFACE"
            )
        }

        // 3. 合格硬解 YUV：必须具备明确的资格证据
        val yuvHardwareQualified = evidence.any {
            it.backendKind == BackendKind.PLATFORM_YUV && it.isQualified
        }
        if (yuvHardwareQualified) {
            return SelectionResult.Selected(
                BackendKind.PLATFORM_YUV,
                DecoderPreference.HARDWARE,
                "QUALIFIED_HARDWARE_YUV"
            )
        }

        // 4. 回退至已确认的软件 YUV 基线 (PlatformSoftwareYuv)
        return SelectionResult.Selected(
            BackendKind.PLATFORM_YUV,
            DecoderPreference.SOFTWARE,
            "VERIFIED_SOFTWARE_YUV_BASELINE"
        )
    }
}
