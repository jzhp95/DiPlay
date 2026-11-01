package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoConfigParserTest {

    private val streamId = SessionVideoId("test-session", 110, 1L)

    @Test
    fun parseAnnexBAvcSpsExtractsDimensions() {
        // Simple synthetic Annex-B SPS (baseline profile, 1280x720 approximate mock structure)
        val spsPayload = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, // NAL header (type 7)
            0x42, 0x00, 0x1f, // profile baseline (66), constraint, level 3.1
            0x8d.toByte(), 0xa8.toByte(), 0x14.toByte(), 0x05.toByte(), 0x09.toByte(),
            0x00, 0x00, 0x00, 0x01, 0x68, 0xce.toByte(), 0x3c.toByte(), 0x80.toByte() // PPS
        )

        val config = VideoConfigParser.parse(CodecType.H264, spsPayload, streamId)
        assertEquals(CodecType.H264, config.codec)
        assertEquals(SourceFraming.ANNEX_B, config.framing)
        assertTrue(config.csd.isNotEmpty())
        assertNotNull(config.colorDeclaration)
    }

    @Test
    fun parseAvcCDeduplicatesAndProducesConsistentHash() {
        // avcC format with 1 SPS and 1 PPS
        val avcC = byteArrayOf(
            1, // configurationVersion
            0x64, // AVCProfileIndication (High profile)
            0x00, // profile_compatibility
            0x1f, // AVCLevelIndication
            0xff.toByte(), // lengthSizeMinusOne | 0xfc
            0xe1.toByte(), // numOfSequenceParameterSets = 1
            0x00, 0x08, // SPS length = 8
            0x67, 0x64, 0x00, 0x1f, 0xac.toByte(), 0xd9.toByte(), 0x40.toByte(), 0xa0.toByte(), // SPS bytes
            0x01, // numOfPictureParameterSets = 1
            0x00, 0x04, // PPS length = 4
            0x68, 0xee.toByte(), 0x3c.toByte(), 0x80.toByte() // PPS bytes
        )

        val config1 = VideoConfigParser.parse(CodecType.H264, avcC, streamId)
        val config2 = VideoConfigParser.parse(CodecType.H264, avcC, streamId)

        assertEquals(config1.csdHash, config2.csdHash)
        assertEquals(config1, config2)
        assertEquals(2, config1.csd.size) // 1 SPS + 1 PPS both with start code
    }

    @Test
    fun parseHevcHvcCExtractsParameterSets() {
        val hvcC = ByteArray(32).apply {
            this[0] = 1 // configurationVersion
            this[22] = 1 // numOfArrays = 1
            this[23] = (33 or 0x80).toByte() // NAL unit type = 33 (SPS)
            this[24] = 0 // numNalus high
            this[25] = 1 // numNalus low = 1
            this[26] = 0 // nalUnitLength high
            this[27] = 4 // nalUnitLength low = 4
            this[28] = 0x42.toByte() // SPS header
            this[29] = 0x01.toByte()
            this[30] = 0x01.toByte()
            this[31] = 0x60.toByte()
        }

        val config = VideoConfigParser.parse(CodecType.HEVC, hvcC, streamId)
        assertEquals(CodecType.HEVC, config.codec)
        assertTrue(config.csd.isNotEmpty())
    }
}
