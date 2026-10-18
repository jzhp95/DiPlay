package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [CarHotspotControllerTest.Wifi::class])
class CarHotspotControllerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        app.getSharedPreferences("diplay_managed_hotspot", 0).edit().clear().commit()
        Wifi.on = false; Wifi.accept = true
        Wifi.value = WifiConfiguration().apply { SSID = "Old"; preSharedKey = "12345678"; allowedKeyManagement.set(4) }
    }
    @Test fun androidLegacyConfigurationIsWrittenReadBackAndThenConfirmed() {
        val requested = CarHotspotProfile("New", "abcdefgh", ManualHotspotSecurity.WPA2, ManualHotspotBand.GHZ_5, 149)
        val result = CarHotspotController(app).apply(requested)
        assertTrue(result.diagnostic, result.success)
        assertTrue(Wifi.on)
        assertEquals(requested, CarHotspotProfiles.confirmed(app))
    }
    @Test fun frameworkRefusalDoesNotCacheRequestedCredentials() {
        Wifi.accept = false
        val result = CarHotspotController(app).apply(CarHotspotProfile("New", "abcdefgh", ManualHotspotSecurity.WPA2))
        assertFalse(result.success); assertNull(CarHotspotProfiles.confirmed(app)); assertEquals("Old", Wifi.value.SSID)
    }
    @Test fun wpa3IsNeverSilentlyWrittenAsLegacyWpa2() {
        val result = CarHotspotController(app).apply(CarHotspotProfile("New", "abcdefgh", ManualHotspotSecurity.WPA3))
        assertFalse(result.success); assertEquals("Old", Wifi.value.SSID)
    }

    @Implements(WifiManager::class)
    class Wifi : ShadowWifiManager() {
        companion object { var on=false; var accept=true; lateinit var value:WifiConfiguration }
        @Implementation(methodName = "getWifiApConfiguration") fun readAp(): WifiConfiguration = value
        @Implementation(methodName = "setWifiApConfiguration") fun writeAp(config:WifiConfiguration):Boolean { if(accept)value=config;return accept }
        @Implementation(methodName = "isWifiApEnabled") fun state():Boolean = on
        @Implementation(methodName = "startSoftAp") fun startAp(config:WifiConfiguration?):Boolean {on=true;return true}
        @Implementation(methodName = "stopSoftAp") fun stopAp():Boolean {on=false;return true}
        @Implementation(methodName = "setWifiApEnabled") fun enabledAp(config:WifiConfiguration?,enabled:Boolean):Boolean {on=enabled;return true}
    }
}
