package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.CarHotspotProfile
import com.shilapi.xcertplay.network.CarHotspotProfiles
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotPersistenceCompatibilityTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay_managed_hotspot", 0).edit().clear()
            .putString("saved_ssid", "Saved car")
            .putString("saved_password", "saved-password")
            .putString("saved_security", "WPA3_TRANSITION")
            .putString("saved_band", "AUTO")
            .putInt("saved_channel", 0).commit()
    }

    @Test fun legacyReadersUseTheSameSavedCredentialsAndRadioIntent() {
        assertEquals("Saved car", AirPlayPersistence.loadManualHotspotSsid(app))
        assertEquals("saved-password", AirPlayPersistence.loadManualHotspotPassphrase(app))
        assertEquals(ManualHotspotSecurity.WPA3_TRANSITION, AirPlayPersistence.loadManualHotspotSecurity(app))
        assertEquals(ManualHotspotBand.AUTO, AirPlayPersistence.loadManualHotspotBand(app))
        assertEquals(0, AirPlayPersistence.loadManualHotspotChannel(app))
    }

    @Test fun legacyPasswordWriterUpdatesTheSavedProfileAndPreservesInterfaceOverride() {
        AirPlayPersistence.saveManualHotspotInterface(app, "eth0.3")

        AirPlayPersistence.saveManualHotspotPassphrase(app, "updated-password")
        val selected = CarHotspotProfiles.resolve(app, manual = { null })

        assertEquals(CarHotspotProfile("Saved car", "updated-password", ManualHotspotSecurity.WPA3_TRANSITION,
            ManualHotspotBand.AUTO, 0), selected?.profile)
        assertEquals("eth0.3", AirPlayPersistence.loadManualHotspotInterface(app))
    }
}
