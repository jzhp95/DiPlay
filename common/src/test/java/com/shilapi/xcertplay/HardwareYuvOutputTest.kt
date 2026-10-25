package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.SoftwareYuvConfig
import org.junit.Assert.*
import org.junit.Test

class HardwareYuvOutputTest {
    @Test fun hardwareExperimentOverridesSoftwareWithoutChangingItsSavedChoices() {
        val output = carPlayVideoOutput(true, true, false, SoftwareYuvConfig(2, 2), hardwareYuv = true)
        assertTrue(output.hardwareYuv)
        assertTrue(output.direct)
        assertFalse(output.software)
        assertFalse(output.smooth)
        assertNull(output.yuv)
        val restored = carPlayVideoOutput(true, true, false, SoftwareYuvConfig(2, 2))
        assertTrue(restored.software)
        assertEquals(SoftwareYuvConfig(2, 2), restored.yuv)
    }
}
