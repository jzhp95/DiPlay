package com.shilapi.xcertplay.media

import java.security.MessageDigest

/** Immutable snapshots; hashes tie a decoder format to the configuration it actually consumed. */
data class VideoDecoderColorEvidence(val configurationHash: String, val decoder: String,
    val software: Boolean, val format: Map<String, Int?>, val generation: Long = 0,
    val active: Boolean = false, val firstFrameRendered: Boolean = false, val instance: String = "",
    val formatDescription: String = "")
fun videoConfigurationHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
data class VideoSourceColorEvidence(val codec: String, val csd: ByteArray,
    val input: VideoColorMetadata.Description, val output: VideoDecoderColorEvidence?, val sourceHash: String)

/** Each decoder owns a ledger; old workers have no shared slot in which to overwrite a replacement. */
internal class VideoColorEvidenceState(private val changed:(VideoDecoderColorEvidence)->Unit={}) {
    private val instance = java.util.UUID.randomUUID().toString()
    @Volatile private var value = VideoDecoderColorEvidence("", "", false, emptyMap(), instance = instance)
    fun snapshot(): VideoDecoderColorEvidence = value
    @Synchronized fun begin(hash: String): Long {
        value = VideoDecoderColorEvidence(hash, "", false, emptyMap(), value.generation + 1, instance = instance)
        runCatching{changed(value)}
        return value.generation
    }
    @Synchronized fun configured(generation: Long, name: String, software: Boolean) {
        if (generation == value.generation) {value = value.copy(decoder = name, software = software, active = true);runCatching{changed(value)}}
    }
    @Synchronized fun format(generation: Long, format: Map<String, Int?>, description: String = "") {
        if (generation == value.generation) {value = value.copy(format = format.toMap(), formatDescription = description.take(4096));runCatching{changed(value)}}
    }
    @Synchronized fun rendered(generation: Long) {
        if (generation == value.generation && value.active && !value.firstFrameRendered) {value = value.copy(firstFrameRendered = true);runCatching{changed(value)}}
    }
    @Synchronized fun invalidate(generation: Long) {
        if (generation == value.generation) value = value.copy(decoder = "", software = false,
            format = emptyMap(), active = false, firstFrameRendered = false, formatDescription = "")
    }
}
