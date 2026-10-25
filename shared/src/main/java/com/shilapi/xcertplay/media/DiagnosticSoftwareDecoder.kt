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

/** Only codecs that explicitly advertise CPU-readable flexible YUV are eligible for the experiment. */
fun softwareYuvDecoderName(mime: String): String? = runCatching {
    val capable = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } &&
            runCatching { android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in
                info.getCapabilitiesForType(mime).colorFormats }.getOrDefault(false)
    }.map { it.name }.toSet()
    diagnosticSoftwareDecoderName(diagnosticSoftwareCandidates().filter { it.name in capable }, mime)
}.getOrNull()

/** Explicit MTK selection: advertised flexible YUV is only eligibility, not proof of usable Images. */
fun mtkYuvDecoderName(mime: String): String? = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
        !info.isEncoder && info.name.startsWith("OMX.MTK.VIDEO.DECODER.", true) &&
            info.supportedTypes.any { it.equals(mime, true) } &&
            android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in
                info.getCapabilitiesForType(mime).colorFormats
    }?.name
}.getOrNull()
