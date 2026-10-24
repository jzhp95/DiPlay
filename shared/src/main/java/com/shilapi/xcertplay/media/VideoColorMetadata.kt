package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec

/** Bounded SPS/VUI reader. Missing declarations stay unknown; this does not decode picture pixels. */
object VideoColorMetadata {
    enum class Status { PARSED, NOT_DECLARED, MALFORMED, UNSUPPORTED }
    data class Description(val status: Status, val fullRange: Boolean? = null,
        val primaries: Int? = null, val transfer: Int? = null, val matrix: Int? = null,
        val detail: String = "")
    fun parse(codec: VideoCodec, csd: ByteArray): Description = try {
        require(csd.size in 7..262144) { "configuration size" }
        if (codec == VideoCodec.H264) {
            require(csd[0].toInt() == 1) { "AVC configuration version" }
            val sps = MediaCodecSupport.avcParameterSets(csd).first
            require(sps.isNotEmpty() && sps[0].toInt() and 31 == 7) { "AVC SPS missing" }
            avc(Bits(rbsp(sps, 1)))
        } else {
            require(csd.size >= 23 && csd[0].toInt() == 1) { "HEVC configuration header" }
            var p = 23
            var sps: ByteArray? = null
            repeat(csd[22].toInt() and 255) {
                require(p + 3 <= csd.size) { "HEVC array header" }
                val type = csd[p++].toInt() and 63
                val count = u16(csd, p); p += 2
                require(count <= 1024) { "HEVC array count" }
                repeat(count) {
                    val length = u16(csd, p); p += 2
                    require(length > 0 && p + length <= csd.size) { "HEVC NAL size" }
                    if (type == 33 && sps == null) sps = csd.copyOfRange(p, p + length)
                    p += length
                }
            }
            val nal = requireNotNull(sps) { "HEVC SPS missing" }
            require(nal.size > 2 && (nal[0].toInt() shr 1) and 63 == 33) { "HEVC SPS type" }
            hevc(Bits(rbsp(nal, 2)))
        }
    } catch (error: UnsupportedOperationException) {
        Description(Status.UNSUPPORTED, detail = error.message.orEmpty())
    } catch (error: IllegalArgumentException) {
        Description(Status.MALFORMED, detail = error.message.orEmpty())
    } catch (_: IndexOutOfBoundsException) {
        Description(Status.MALFORMED, detail = "truncated configuration")
    }
    private fun avc(b: Bits): Description {
        val profile = b.read(8); b.skip(16); b.ue(31)
        if (profile in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            val chroma = b.ue(3)
            if (chroma == 3) b.skip(1)
            b.ue(6); b.ue(6); b.skip(1)
            if (b.flag()) repeat(if (chroma == 3) 12 else 8) { i ->
                if (b.flag()) {
                    var last = 8; var next = 8
                    repeat(if (i < 6) 16 else 64) {
                        if (next != 0) next = (last + b.se() + 256) and 255
                        if (next != 0) last = next
                    }
                }
            }
        } else if (profile !in listOf(66, 77, 88)) throw UnsupportedOperationException("AVC profile $profile")
        b.ue(12)
        when (b.ue(2)) {
            0 -> b.ue(12)
            1 -> { b.skip(1); b.se(); b.se(); repeat(b.ue(255)) { b.se() } }
        }
        b.ue(32); b.skip(1); b.ue(65535); b.ue(65535)
        if (!b.flag()) b.skip(1)
        b.skip(1)
        if (b.flag()) repeat(4) { b.ue(65535) }
        return if (b.flag()) vui(b) else Description(Status.NOT_DECLARED, detail = "vui_parameters_present_flag=0")
    }
    private fun hevc(b: Bits): Description {
        b.skip(4)
        val layers = b.read(3); require(layers <= 6) { "HEVC sublayers" }; b.skip(1)
        b.skip(96) // general profile/tier/compatibility/constraint/level
        val profiles = BooleanArray(layers); val levels = BooleanArray(layers)
        repeat(layers) { profiles[it] = b.flag(); levels[it] = b.flag() }
        if (layers > 0) b.skip((8 - layers) * 2)
        repeat(layers) { if (profiles[it]) b.skip(88); if (levels[it]) b.skip(8) }
        b.ue(15)
        if (b.ue(3) == 3) b.skip(1)
        b.ue(65535); b.ue(65535)
        if (b.flag()) repeat(4) { b.ue(65535) }
        b.ue(8); b.ue(8)
        val pocBits = b.ue(12) + 4
        val ordering = b.flag()
        for (i in (if (ordering) 0 else layers)..layers) { b.ue(16); b.ue(16); b.ue(65535) }
        repeat(6) { b.ue(32) }
        if (b.flag() && b.flag()) for (size in 0..3) {
            var matrix = 0
            while (matrix < 6) {
                if (!b.flag()) b.ue(63) else {
                    if (size > 1) b.se()
                    repeat(minOf(64, 1 shl (4 + size * 2))) { b.se() }
                }
                matrix += if (size == 3) 3 else 1
            }
        }
        b.skip(2) // amp_enabled, sample_adaptive_offset_enabled
        if (b.flag()) { b.skip(8); b.ue(32); b.ue(32); b.skip(1) }
        val shortSets = b.ue(64)
        val deltas = IntArray(shortSets)
        repeat(shortSets) { i ->
            if (i > 0 && b.flag()) {
                b.skip(1); b.ue(65535)
                var count = 0
                repeat(deltas[i - 1] + 1) {
                    val used = b.flag()
                    val useDelta = used || b.flag()
                    if (useDelta) count++
                }
                require(count <= 64) { "HEVC reference delta count" }; deltas[i] = count
            } else {
                val negative = b.ue(32); val positive = b.ue(32)
                repeat(negative + positive) { b.ue(65535); b.skip(1) }
                deltas[i] = negative + positive
            }
        }
        if (b.flag()) repeat(b.ue(32)) { b.skip(pocBits); b.skip(1) }
        b.skip(2) // temporal_mvp, strong_intra_smoothing
        return if (b.flag()) vui(b) else Description(Status.NOT_DECLARED, detail = "vui_parameters_present_flag=0")
    }
    private fun vui(b: Bits): Description {
        if (b.flag() && b.read(8) == 255) b.skip(32)
        if (b.flag()) b.skip(1)
        if (!b.flag()) return Description(Status.NOT_DECLARED, detail = "video_signal_type_present_flag=0")
        b.skip(3)
        val full = b.flag()
        return if (b.flag()) Description(Status.PARSED, full, b.read(8), b.read(8), b.read(8))
            else Description(Status.PARSED, fullRange = full, detail = "colour_description_present_flag=0")
    }
    private fun rbsp(nal: ByteArray, header: Int): ByteArray {
        val out = ByteArray(nal.size - header)
        var at = 0; var zeros = 0
        for (i in header until nal.size) {
            val value = nal[i].toInt() and 255
            if (zeros >= 2 && value == 3) {
                require(i + 1 < nal.size && nal[i + 1].toInt() and 255 <= 3) { "invalid escape" }
                zeros = 0; continue
            }
            out[at++] = nal[i]
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.copyOf(at)
    }
    private fun u16(bytes: ByteArray, p: Int): Int {
        require(p >= 0 && p + 2 <= bytes.size) { "truncated length" }
        return ((bytes[p].toInt() and 255) shl 8) or (bytes[p + 1].toInt() and 255)
    }
    private class Bits(private val bytes: ByteArray) {
        private var at = 0
        fun read(count: Int): Int {
            require(count in 0..30 && count <= bytes.size * 8 - at) { "truncated SPS" }
            var value = 0
            repeat(count) { value = (value shl 1) or ((bytes[at / 8].toInt() ushr (7 - at++ % 8)) and 1) }
            return value
        }
        fun flag() = read(1) != 0
        fun skip(count: Int) { require(count >= 0 && count <= bytes.size * 8 - at) { "truncated SPS" }; at += count }
        fun ue(max: Int = Int.MAX_VALUE): Int {
            var zeros = 0
            while (!flag()) { zeros++; require(zeros <= 29) { "unbounded Exp-Golomb" } }
            val value = (1 shl zeros) - 1 + read(zeros)
            require(value <= max) { "SPS field out of bounds" }
            return value
        }
        fun se(): Int { val value = ue(1 shl 20); return if (value and 1 != 0) (value + 1) / 2 else -value / 2 }
    }
}
