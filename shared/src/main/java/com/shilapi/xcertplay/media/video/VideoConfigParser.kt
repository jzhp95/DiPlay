package com.shilapi.xcertplay.media.video

import java.nio.ByteBuffer

/**
 * 纯标准视频参数集与色彩配置解析器（支持 H.264 avcC/Annex-B 与 HEVC hvcC/Annex-B）。
 * 提取 SPS 尺寸与 VUI 色彩声明，生成不可变的 VideoStreamConfig。
 */
object VideoConfigParser {

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    fun parse(
        codec: CodecType,
        codecData: ByteArray,
        streamId: SessionVideoId
    ): VideoStreamConfig {
        require(codecData.isNotEmpty()) { "codecData cannot be empty" }

        val parameterSets: List<ByteArray>
        var width = 1280
        var height = 720
        var colorDeclaration = SourceColorDeclaration()

        when (codec) {
            CodecType.H264 -> {
                parameterSets = extractAvcParameterSets(codecData)
                val sps = parameterSets.firstOrNull { it.size > 4 && (it[4].toInt() and 0x1f) == 7 }
                if (sps != null) {
                    val parsed = parseAvcSps(sps, 4)
                    width = parsed.width
                    height = parsed.height
                    colorDeclaration = parsed.color
                }
            }
            CodecType.HEVC -> {
                parameterSets = extractHevcParameterSets(codecData)
                val sps = parameterSets.firstOrNull { it.size > 4 && ((it[4].toInt() shr 1) and 0x3f) == 33 }
                if (sps != null) {
                    val parsed = parseHevcSps(sps, 4)
                    width = parsed.width
                    height = parsed.height
                    colorDeclaration = parsed.color
                }
            }
            CodecType.UNKNOWN -> {
                parameterSets = listOf(codecData.clone())
            }
        }

        return VideoStreamConfig(
            streamId = streamId,
            codec = codec,
            width = width,
            height = height,
            framing = SourceFraming.ANNEX_B,
            colorDeclaration = colorDeclaration,
            csdData = parameterSets
        )
    }

    private data class SpsResult(val width: Int, val height: Int, val color: SourceColorDeclaration)

    private fun extractAvcParameterSets(data: ByteArray): List<ByteArray> {
        if (data.startsWithStartCode()) {
            return splitAnnexB(data)
        }
        // avcC format
        if (data.size >= 7 && data[0].toInt() == 1) {
            val list = mutableListOf<ByteArray>()
            var offset = 5
            val numSps = data[offset++].toInt() and 0x1f
            for (i in 0 until numSps) {
                if (offset + 2 > data.size) break
                val len = readU16Be(data, offset)
                offset += 2
                if (offset + len > data.size) break
                list.add(START_CODE + data.copyOfRange(offset, offset + len))
                offset += len
            }
            if (offset < data.size) {
                val numPps = data[offset++].toInt() and 0xff
                for (i in 0 until numPps) {
                    if (offset + 2 > data.size) break
                    val len = readU16Be(data, offset)
                    offset += 2
                    if (offset + len > data.size) break
                    list.add(START_CODE + data.copyOfRange(offset, offset + len))
                    offset += len
                }
            }
            if (list.isNotEmpty()) return list
        }
        return listOf(START_CODE + data)
    }

    private fun extractHevcParameterSets(data: ByteArray): List<ByteArray> {
        if (data.startsWithStartCode()) {
            return splitAnnexB(data)
        }
        // hvcC format
        if (data.size >= 23 && data[0].toInt() == 1) {
            val list = mutableListOf<ByteArray>()
            var p = 22
            val numArrays = data[p++].toInt() and 0xff
            for (i in 0 until numArrays) {
                if (p + 3 > data.size) break
                p++ // nal_unit_type
                val numNalus = readU16Be(data, p)
                p += 2
                for (j in 0 until numNalus) {
                    if (p + 2 > data.size) break
                    val len = readU16Be(data, p)
                    p += 2
                    if (p + len > data.size) break
                    list.add(START_CODE + data.copyOfRange(p, p + len))
                    p += len
                }
            }
            if (list.isNotEmpty()) return list
        }
        return listOf(START_CODE + data)
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (start >= 0) {
                    list.add(data.copyOfRange(start, i))
                }
                start = i
                i += 3
            } else if (i + 4 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                if (start >= 0) {
                    list.add(data.copyOfRange(start, i))
                }
                start = i
                i += 4
            } else {
                i++
            }
        }
        if (start >= 0 && start < data.size) {
            list.add(data.copyOfRange(start, data.size))
        }
        return if (list.isNotEmpty()) list else listOf(data.clone())
    }

    private fun parseAvcSps(data: ByteArray, startOffset: Int): SpsResult {
        return try {
            val rbsp = unescapeRbsp(data, startOffset + 1) // skip NAL header byte
            val bits = BitReader(rbsp)
            val profile = bits.read(8)
            bits.skip(16) // constraint flags + level_idc
            bits.readUe() // seq_parameter_set_id

            var chromaFormatIdc = 1
            if (profile in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                chromaFormatIdc = bits.readUe()
                if (chromaFormatIdc == 3) bits.skip(1)
                bits.readUe() // bit_depth_luma_minus8
                bits.readUe() // bit_depth_chroma_minus8
                bits.skip(1) // qpprime_y_zero_transform_bypass_flag
                if (bits.readFlag()) { // seq_scaling_matrix_present_flag
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until count) {
                        if (bits.readFlag()) {
                            skipScalingList(bits, if (i < 6) 16 else 64)
                        }
                    }
                }
            }

            bits.readUe() // log2_max_frame_num_minus4
            val picOrderCntType = bits.readUe()
            if (picOrderCntType == 0) {
                bits.readUe() // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                bits.skip(1)
                bits.readSe(); bits.readSe()
                val numRefFramesInPicOrderCntCycle = bits.readUe()
                for (i in 0 until numRefFramesInPicOrderCntCycle) bits.readSe()
            }
            bits.readUe() // max_num_ref_frames
            bits.skip(1) // gaps_in_frame_num_value_allowed_flag

            val picWidthInMbsMinus1 = bits.readUe()
            val picHeightInMapUnitsMinus1 = bits.readUe()
            val frameMbsOnlyFlag = bits.readFlag()
            if (!frameMbsOnlyFlag) bits.skip(1) // mb_adaptive_frame_field_flag
            bits.skip(1) // direct_8x8_inference_flag

            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            if (bits.readFlag()) { // frame_cropping_flag
                cropLeft = bits.readUe()
                cropRight = bits.readUe()
                cropTop = bits.readUe()
                cropBottom = bits.readUe()
            }

            val cropUnitX = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
            val cropUnitY = (if (frameMbsOnlyFlag) 1 else 2) * (if (chromaFormatIdc == 1) 2 else 1)
            val width = (picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * cropUnitX
            val height = (2 - if (frameMbsOnlyFlag) 1 else 0) * (picHeightInMapUnitsMinus1 + 1) * 16 - (cropTop + cropBottom) * cropUnitY

            var color = SourceColorDeclaration()
            if (bits.readFlag()) { // vui_parameters_present_flag
                color = parseVui(bits)
            }

            SpsResult(width.coerceAtLeast(1), height.coerceAtLeast(1), color)
        } catch (_: Exception) {
            SpsResult(1280, 720, SourceColorDeclaration(parseStatus = "FALLBACK"))
        }
    }

    private fun parseHevcSps(data: ByteArray, startOffset: Int): SpsResult {
        return try {
            val rbsp = unescapeRbsp(data, startOffset + 2) // skip 2-byte HEVC NAL header
            val bits = BitReader(rbsp)
            bits.skip(4) // sps_video_parameter_set_id
            val maxSubLayersMinus1 = bits.read(3)
            bits.skip(1) // sps_temporal_id_nesting_flag

            // profile_tier_level
            bits.skip(96)
            val subLayerProfilePresent = BooleanArray(maxSubLayersMinus1)
            val subLayerLevelPresent = BooleanArray(maxSubLayersMinus1)
            for (i in 0 until maxSubLayersMinus1) {
                subLayerProfilePresent[i] = bits.readFlag()
                subLayerLevelPresent[i] = bits.readFlag()
            }
            if (maxSubLayersMinus1 > 0) bits.skip((8 - maxSubLayersMinus1) * 2)
            for (i in 0 until maxSubLayersMinus1) {
                if (subLayerProfilePresent[i]) bits.skip(88)
                if (subLayerLevelPresent[i]) bits.skip(8)
            }

            bits.readUe() // sps_seq_parameter_set_id
            val chromaFormatIdc = bits.readUe()
            if (chromaFormatIdc == 3) bits.skip(1)

            val picWidthInLumaSamples = bits.readUe()
            val picHeightInLumaSamples = bits.readUe()

            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            if (bits.readFlag()) { // conformance_window_flag
                cropLeft = bits.readUe()
                cropRight = bits.readUe()
                cropTop = bits.readUe()
                cropBottom = bits.readUe()
            }

            val subWidthC = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
            val subHeightC = if (chromaFormatIdc == 1) 2 else 1
            val width = picWidthInLumaSamples - (cropLeft + cropRight) * subWidthC
            val height = picHeightInLumaSamples - (cropTop + cropBottom) * subHeightC

            bits.readUe() // bit_depth_luma_minus8
            bits.readUe() // bit_depth_chroma_minus8
            bits.readUe() // log2_max_pic_order_cnt_lsb_minus4

            // Skip sub_layer_ordering_info
            val subLayerOrderingInfoPresent = bits.readFlag()
            val startI = if (subLayerOrderingInfoPresent) 0 else maxSubLayersMinus1
            for (i in startI..maxSubLayersMinus1) {
                bits.readUe() // max_dec_pic_buffering_minus1
                bits.readUe() // max_num_reorder_pics
                bits.readUe() // max_latency_increase_plus1
            }

            bits.readUe(); bits.readUe(); bits.readUe(); bits.readUe(); bits.readUe(); bits.readUe() // log2 params
            bits.skip(1); bits.skip(1) // scaling_list_enabled_flag etc.
            if (bits.readFlag()) { // sps_scaling_list_data_present_flag
                skipHevcScalingList(bits)
            }
            bits.skip(1); bits.skip(1) // amp_enabled_flag, sample_adaptive_offset_enabled_flag
            if (bits.readFlag()) { // pcm_enabled_flag
                bits.skip(8); bits.readUe(); bits.readUe(); bits.skip(1)
            }
            val numShortTermRefPicSets = bits.readUe()
            var numNegativePics = 0; var numPositivePics = 0
            for (i in 0 until numShortTermRefPicSets) {
                if (i != 0 && bits.readFlag()) {
                    bits.skip(1); bits.readUe()
                    // delta rps
                    continue
                }
                numNegativePics = bits.readUe()
                numPositivePics = bits.readUe()
                for (j in 0 until numNegativePics) { bits.readUe(); bits.skip(1) }
                for (j in 0 until numPositivePics) { bits.readUe(); bits.skip(1) }
            }
            if (bits.readFlag()) { // long_term_ref_pics_present_flag
                val numLongTerm = bits.readUe()
                for (i in 0 until numLongTerm) { bits.skip(bits.read(4) + 1) }
            }
            bits.skip(1); bits.skip(1) // sps_temporal_mvp_enabled_flag, strong_intra_smoothing_enabled_flag

            var color = SourceColorDeclaration()
            if (bits.readFlag()) { // vui_parameters_present_flag
                color = parseVui(bits)
            }

            SpsResult(width.coerceAtLeast(1), height.coerceAtLeast(1), color)
        } catch (_: Exception) {
            SpsResult(1280, 720, SourceColorDeclaration(parseStatus = "FALLBACK"))
        }
    }

    private fun parseVui(bits: BitReader): SourceColorDeclaration {
        if (bits.readFlag()) { // aspect_ratio_info_present_flag
            val aspect = bits.read(8)
            if (aspect == 255) bits.skip(32) // Extended_SAR
        }
        if (bits.readFlag()) bits.skip(1) // overscan_info_present_flag
        var range = ColorRange.UNKNOWN
        var matrix = ColorMatrix.UNKNOWN
        var primaries = ColorPrimaries.UNKNOWN
        var transfer = ColorTransfer.UNKNOWN

        if (bits.readFlag()) { // video_signal_type_present_flag
            bits.skip(3) // video_format
            val fullRangeFlag = bits.readFlag()
            range = if (fullRangeFlag) ColorRange.FULL else ColorRange.LIMITED

            if (bits.readFlag()) { // colour_description_present_flag
                val primariesVal = bits.read(8)
                val transferVal = bits.read(8)
                val matrixVal = bits.read(8)

                primaries = when (primariesVal) {
                    1 -> ColorPrimaries.BT709
                    5 -> ColorPrimaries.BT601_625
                    6 -> ColorPrimaries.BT601_525
                    9 -> ColorPrimaries.BT2020
                    else -> ColorPrimaries.UNKNOWN
                }
                transfer = when (transferVal) {
                    1, 6, 14, 15 -> ColorTransfer.BT709
                    13 -> ColorTransfer.SRGB
                    8 -> ColorTransfer.LINEAR
                    else -> ColorTransfer.UNKNOWN
                }
                matrix = when (matrixVal) {
                    1 -> ColorMatrix.BT709
                    5, 6 -> ColorMatrix.BT601
                    9 -> ColorMatrix.BT2020
                    else -> ColorMatrix.UNKNOWN
                }
            }
        }

        return SourceColorDeclaration(
            range = range,
            matrix = matrix,
            primaries = primaries,
            transfer = transfer,
            origin = "SPS_VUI",
            parseStatus = "PARSED"
        )
    }

    private fun skipScalingList(bits: BitReader, size: Int) {
        var lastScale = 8; var nextScale = 8
        for (i in 0 until size) {
            if (nextScale != 0) {
                val deltaScale = bits.readSe()
                nextScale = (lastScale + deltaScale + 256) and 255
            }
            if (nextScale != 0) lastScale = nextScale
        }
    }

    private fun skipHevcScalingList(bits: BitReader) {
        for (sizeId in 0 until 4) {
            val count = if (sizeId == 3) 2 else 6
            for (matrixId in 0 until count) {
                if (!bits.readFlag()) {
                    bits.readUe()
                } else {
                    val coefNum = Math.min(64, 1 shl (4 + (sizeId shl 1)))
                    if (sizeId > 1) bits.readSe()
                    var next = 8
                    for (i in 0 until coefNum) {
                        next = (next + bits.readSe() + 256) and 255
                    }
                }
            }
        }
    }

    private fun unescapeRbsp(data: ByteArray, offset: Int): ByteArray {
        val out = ByteArray(data.size - offset)
        var outLen = 0
        var i = offset
        while (i < data.size) {
            if (i + 2 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 3.toByte()) {
                out[outLen++] = data[i++]
                out[outLen++] = data[i++]
                i++ // skip emulation_prevention_three_byte (0x03)
            } else {
                out[outLen++] = data[i++]
            }
        }
        return out.copyOf(outLen)
    }

    private class BitReader(private val bytes: ByteArray) {
        private var byteIndex = 0
        private var bitOffset = 0

        fun read(n: Int): Int {
            var result = 0
            for (i in 0 until n) {
                result = (result shl 1) or if (readFlag()) 1 else 0
            }
            return result
        }

        fun skip(n: Int) {
            bitOffset += n
            byteIndex += bitOffset shr 3
            bitOffset = bitOffset and 7
        }

        fun readFlag(): Boolean {
            if (byteIndex >= bytes.size) return false
            val bit = (bytes[byteIndex].toInt() shr (7 - bitOffset)) and 1
            bitOffset++
            if (bitOffset == 8) {
                bitOffset = 0
                byteIndex++
            }
            return bit == 1
        }

        fun readUe(): Int {
            var zeros = 0
            while (!readFlag() && zeros < 32) {
                zeros++
            }
            if (zeros == 0) return 0
            return (1 shl zeros) - 1 + read(zeros)
        }

        fun readSe(): Int {
            val ue = readUe()
            return if ((ue and 1) == 0) -(ue shr 1) else (ue + 1) shr 1
        }
    }

    private fun ByteArray.startsWithStartCode(): Boolean =
        size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 0.toByte() && this[3] == 1.toByte()

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)
}
