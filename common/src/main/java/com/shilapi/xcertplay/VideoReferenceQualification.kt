package com.shilapi.xcertplay

import org.json.JSONObject

/**
 * 参考基准与颜色核验资格判断结果。
 */
data class ReferenceQualificationResult(
    val isCalibrated: Boolean,
    val referenceBasis: String,
    val uncalibratedReason: String? = null
)

/**
 * 诊断参考基准资格判定器 (VideoReferenceQualification)。
 * 严禁把未校准的参考阈值当做自动颜色通过门禁；
 * 发现包含 uncalibrated pass thresholds 标识时，保持真实数值并不虚构结论。
 */
object VideoReferenceQualification {

    fun evaluate(referenceJson: JSONObject?): ReferenceQualificationResult {
        if (referenceJson == null) {
            return ReferenceQualificationResult(
                isCalibrated = false,
                referenceBasis = "UNKNOWN",
                uncalibratedReason = "No reference JSON provided"
            )
        }

        val basis = referenceJson.optString("basis", "DEFAULT_REFERENCE")
        val status = referenceJson.optString("calibrationStatus", "")

        val isExplicitlyUncalibrated = status.contains("uncalibrated", ignoreCase = true) ||
                referenceJson.optBoolean("uncalibratedPassThresholds", false) ||
                basis.contains("uncalibrated", ignoreCase = true)

        return if (isExplicitlyUncalibrated) {
            ReferenceQualificationResult(
                isCalibrated = false,
                referenceBasis = basis,
                uncalibratedReason = "Reference explicitly specifies uncalibrated pass thresholds"
            )
        } else {
            ReferenceQualificationResult(
                isCalibrated = true,
                referenceBasis = basis,
                uncalibratedReason = null
            )
        }
    }
}
