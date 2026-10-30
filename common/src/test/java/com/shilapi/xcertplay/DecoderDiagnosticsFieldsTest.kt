package com.shilapi.xcertplay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DecoderDiagnosticsFieldsTest {
    @Test fun missingReadIsPreservedAndRawThermalIsNotCpuTemperature(){
        val fields=DecoderDiagnosticsFields.describe(JSONObject().put("thermalZones",JSONObject().put("rawTemperature",123))
            .put("cpuAndKernel",JSONObject().put("clock",JSONObject().put("unavailable","SecurityException")))).getJSONArray("fields")
        val rows=(0 until fields.length()).map{fields.getJSONObject(it)}
        assertEquals("unverified_interpretation",rows.single{it.getString("field").contains("rawTemperature")}.getString("confidence"))
        val missing=rows.single{it.getString("field").contains("clock")}
        assertEquals("unavailable_or_unknown",missing.getString("availability"));assertEquals("SecurityException",missing.getString("error"))
    }
}
