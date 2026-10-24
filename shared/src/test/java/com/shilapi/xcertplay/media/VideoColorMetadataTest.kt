package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VideoColorMetadataTest {
    @Test fun packagedFixturesDeclareTheExpectedRangeAndBt709Triplet() {
        for ((name, codec) in listOf("h264" to VideoCodec.H264, "hevc" to VideoCodec.H265))
            for ((range, full) in listOf("full" to true, "limited" to false)) {
                val bytes = File("../common/src/main/assets/color-test/${name}_$range.csd").readBytes()
                val result = VideoColorMetadata.parse(codec, bytes)
                assertEquals("$name $range: ${result.detail}", VideoColorMetadata.Status.PARSED, result.status)
                assertEquals(full, result.fullRange)
                assertEquals(1, result.primaries); assertEquals(1, result.transfer); assertEquals(1, result.matrix)
            }
    }
    @Test fun missingDeclarationsRemainUnknownInsteadOfInventingAColorRange() {
        val bits = StringBuilder("010000100000000000011110") // baseline profile, constraints, level
        fun ue(value: Int) { val code = (value + 1).toString(2); bits.append("0".repeat(code.length - 1)).append(code) }
        ue(0); ue(0); ue(0); ue(0); ue(0); bits.append('0'); ue(39); ue(22)
        bits.append("11001") // frame_mbs_only, direct_8x8, crop=0, VUI=0, RBSP stop
        while (bits.length % 8 != 0) bits.append('0')
        val sps = byteArrayOf(0x67) + bits.toString().chunked(8).map { it.toInt(2).toByte() }.toByteArray()
        val avcc = byteArrayOf(1, 0x42, 0, 0x1e, 0xff.toByte(), 0xe1.toByte(), 0, sps.size.toByte()) + sps + byteArrayOf(0)
        val result = VideoColorMetadata.parse(VideoCodec.H264, avcc)
        assertEquals(VideoColorMetadata.Status.NOT_DECLARED, result.status)
        assertNull(result.fullRange)
        assertNull(result.matrix)
    }
    @Test fun malformedAndTruncatedDataCannotProduceAnAssumedValidDescription() {
        for (codec in VideoCodec.entries) for (size in listOf(0, 1, 5, 20, 127)) {
            val result = VideoColorMetadata.parse(codec, ByteArray(size))
            assertEquals(VideoColorMetadata.Status.MALFORMED, result.status)
            assertNull(result.fullRange)
        }
        val random = java.util.Random(314159)
        repeat(500) {
            val bytes = ByteArray(random.nextInt(256)).also(random::nextBytes)
            VideoColorMetadata.parse(if (it % 2 == 0) VideoCodec.H264 else VideoCodec.H265, bytes)
        }
    }
}
