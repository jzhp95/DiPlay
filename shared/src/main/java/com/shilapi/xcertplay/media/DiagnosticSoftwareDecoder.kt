package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.os.Build

/** API28 has no isSoftwareOnly; only known platform software prefixes are accepted there. */
data class SoftwareVideoCandidate(val name: String, val types: List<String>, val softwareReported: Boolean?)
fun isKnownPlatformSoftwareVideoDecoder(name: String): Boolean =
    listOf("OMX.google.", "c2.android.", "c2.google.").any { name.startsWith(it, ignoreCase = true) }
fun diagnosticSoftwareDecoderName(candidates: List<SoftwareVideoCandidate>, mime: String): String? =
    candidates.firstOrNull { item -> item.types.any { it.equals(mime, true) } &&
        (item.softwareReported ?: isKnownPlatformSoftwareVideoDecoder(item.name)) }?.name
fun diagnosticSoftwareCandidates(): List<SoftwareVideoCandidate> = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.map {
        SoftwareVideoCandidate(it.name, it.supportedTypes.toList(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.isSoftwareOnly else null)
    }
}.getOrDefault(emptyList())
internal fun diagnosticVideoDecoderAttempts(forceSoftware: Boolean, selectedSoftware: String?, normal: List<DecoderAttempt>): List<DecoderAttempt> =
    if (!forceSoftware) normal else selectedSoftware?.let { listOf(DecoderAttempt(it, tuned = false)) }.orEmpty()
