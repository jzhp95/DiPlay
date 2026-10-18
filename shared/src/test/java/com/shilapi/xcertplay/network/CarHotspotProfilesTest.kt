package com.shilapi.xcertplay.network

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
class CarHotspotProfilesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val profile = CarHotspotProfile("Car", "12345678", ManualHotspotSecurity.WPA2)
    @Before fun setup() {
        app.getSharedPreferences("diplay_managed_hotspot", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }
    @Test fun desiredEditsDoNotBecomeConfirmedCredentials() {
        CarHotspotProfiles.saveDraft(app, profile)
        assertEquals(profile, CarHotspotProfiles.draft(app))
        assertNull(CarHotspotProfiles.confirmed(app))
    }
    @Test fun confirmedProfilesAreIsolatedFromManualPreferences() {
        val manual = app.getSharedPreferences("xcertplay_airplay", 0)
        manual.edit().putString("existing-manual-entry", "keep").commit()
        val original = manual.all.toMap()
        CarHotspotProfiles.saveConfirmed(app, profile)
        assertEquals(original, manual.all)
        val selected = CarHotspotProfiles.resolve(app, manual = { error("manual source should remain unread") })
        assertEquals(CarHotspotProfileSource.CONFIRMED, selected?.source)
        assertEquals(profile, selected?.profile)
    }
    @Test fun failedOrMissingManagedProfilesAllowManualFallback() {
        CarHotspotProfiles.saveDraft(app, profile.copy(ssid = "Not applied"))
        val selected = CarHotspotProfiles.resolve(app, manual = { profile })
        assertEquals(CarHotspotProfileSource.MANUAL, selected?.source)
        assertEquals(profile, selected?.profile)
    }
}
