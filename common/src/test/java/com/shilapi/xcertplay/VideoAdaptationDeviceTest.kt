package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoAdaptationDeviceTest {
    @Test fun deniedBatteryAccessIsUnavailableRatherThanBreakingTheWholeSampler() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
                throw SecurityException("vendor denies battery query")
            }
        }
        val thermal = VideoAdaptationDevice.thermal(context)
        assertTrue(thermal.isNull("batteryCelsius"))
        assertEquals("unavailable_on_API28", thermal.getString("thermalStatus"))
        assertEquals("unavailable_without_verified_sensor_mapping", thermal.getString("cpuGpuTemperature"))
    }
}
