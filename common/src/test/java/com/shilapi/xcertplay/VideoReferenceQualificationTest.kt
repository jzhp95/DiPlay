package com.shilapi.xcertplay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoReferenceQualificationTest {

    @Test
    fun uncalibratedJsonIdentifiedCorrectly() {
        val json = JSONObject()
            .put("basis", "h1-reference.json")
            .put("calibrationStatus", "uncalibrated")

        val result = VideoReferenceQualification.evaluate(json)
        assertFalse(result.isCalibrated)
        assertEquals("h1-reference.json", result.referenceBasis)
        assertTrue(result.uncalibratedReason?.contains("uncalibrated") == true)
    }

    @Test
    fun calibratedJsonReturnsQualified() {
        val json = JSONObject()
            .put("basis", "ground-truth-calibrated")
            .put("calibrationStatus", "VERIFIED_CALIBRATED")

        val result = VideoReferenceQualification.evaluate(json)
        assertTrue(result.isCalibrated)
        assertEquals(null, result.uncalibratedReason)
    }
}
