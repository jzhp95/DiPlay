package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoColorContractTest {
    @Test
    fun realH264AndHevcColorContractPriorities() {
        // H.264 live stream: full_range=true, primaries=1, transfer=13, matrix=6 (BT.601)
        val h264Source = VideoColorMetadata.Description(
            status = VideoColorMetadata.Status.PARSED,
            fullRange = true,
            primaries = 1,
            transfer = 13,
            matrix = 6
        )
        val h264Full601 = SoftwareYuvColor.resolve(SoftwareYuvConfig(0, 0), h264Source, null)
        assertTrue(h264Full601.fullRange)
        assertFalse(h264Full601.bt709)
        assertEquals("SPS", h264Full601.rangeSource)
        assertEquals("SPS", h264Full601.matrixSource)

        // HEVC live stream: full_range=true, primaries=1, transfer=13, matrix=1 (BT.709)
        val hevcSource = VideoColorMetadata.Description(
            status = VideoColorMetadata.Status.PARSED,
            fullRange = true,
            primaries = 1,
            transfer = 13,
            matrix = 1
        )
        val hevcFull709 = SoftwareYuvColor.resolve(SoftwareYuvConfig(0, 0), hevcSource, null)
        assertTrue(hevcFull709.fullRange)
        assertTrue(hevcFull709.bt709)
        assertEquals("SPS", hevcFull709.rangeSource)
        assertEquals("SPS", hevcFull709.matrixSource)

        // Unknown metadata with no decoder format
        val unknown = SoftwareYuvColor.resolve(SoftwareYuvConfig(0, 0), null, null)
        assertFalse(unknown.fullRange)
        assertEquals("assumed-limited", unknown.rangeSource)
        assertEquals("assumed-BT709", unknown.matrixSource)
    }

    @Test
    fun manualConfigOverridesSpsAndDecoder() {
        val sps = VideoColorMetadata.Description(
            status = VideoColorMetadata.Status.PARSED,
            fullRange = true,
            primaries = 1,
            transfer = 13,
            matrix = 1
        )
        // Manual range=2 (limited), manual matrix=2 (BT.601)
        val manual = SoftwareYuvColor.resolve(SoftwareYuvConfig(range = 2, matrix = 2), sps, null)
        assertFalse(manual.fullRange)
        assertFalse(manual.bt709)
        assertEquals("manual", manual.rangeSource)
        assertEquals("manual", manual.matrixSource)
    }

    @Test
    fun hdrAndWideGamutAreExplicitlyRejected() {
        val hdr = VideoColorMetadata.Description(
            status = VideoColorMetadata.Status.PARSED,
            transfer = 16
        )
        try {
            SoftwareYuvColor.resolve(SoftwareYuvConfig(0, 0), hdr, null)
            fail("HDR transfer must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("HDR transfer is not supported") == true)
        }

        val wideGamut = VideoColorMetadata.Description(
            status = VideoColorMetadata.Status.PARSED,
            primaries = 9
        )
        try {
            SoftwareYuvColor.resolve(SoftwareYuvConfig(0, 0), wideGamut, null)
            fail("Wide gamut must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("wide-gamut YUV is not supported") == true)
        }
    }
}
