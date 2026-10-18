package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
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

    @Test fun savedProfilePrecedesConfirmedAndLegacyManualCredentials() {
        app.getSharedPreferences("diplay_managed_hotspot", 0).edit()
            .putString("saved_ssid", "Saved car")
            .putString("saved_password", "saved-password")
            .putString("saved_security", "WPA3_TRANSITION")
            .putString("saved_band", "AUTO")
            .putInt("saved_channel", 0).commit()
        CarHotspotProfiles.saveConfirmed(app, profile.copy(ssid = "Old confirmed"))

        val selected = CarHotspotProfiles.resolve(app, manual = { profile.copy(ssid = "Old manual") })

        assertEquals(CarHotspotProfile("Saved car", "saved-password", ManualHotspotSecurity.WPA3_TRANSITION,
            ManualHotspotBand.AUTO, 0), selected?.profile)
    }

    @Test fun firstLegacyImportDoesNotChangeWhenLegacyCredentialsLaterChange() {
        val first = CarHotspotProfiles.resolve(app, manual = { profile })
        val second = CarHotspotProfiles.resolve(app, manual = { profile.copy(ssid = "Later legacy edit") })

        assertEquals(profile, first?.profile)
        assertEquals(profile, second?.profile)
    }

    @Test fun lateLegacyImportCannotOverwriteAnExplicitSavedProfile() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val import = FutureTask {
            CarHotspotProfiles.resolve(app, manual = {
                reading.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                profile
            })
        }
        val worker = Thread(import, "test-hotspot-import").apply { start() }
        try {
            assertTrue("Migration did not reach the slow reader", reading.await(3, TimeUnit.SECONDS))
            app.getSharedPreferences("diplay_managed_hotspot", 0).edit()
                .putString("saved_ssid", "New explicit save")
                .putString("saved_password", "new-password")
                .putString("saved_security", "WPA3")
                .putString("saved_band", "AUTO")
                .putInt("saved_channel", 0).commit()
            release.countDown()

            assertEquals(CarHotspotProfile("New explicit save", "new-password", ManualHotspotSecurity.WPA3),
                import.get(3, TimeUnit.SECONDS)?.profile)
            assertEquals("New explicit save", app.getSharedPreferences("diplay_managed_hotspot", 0).getString("saved_ssid", null))
        } finally {
            release.countDown()
            worker.join(3_000)
        }
    }

    @Test fun importingConfirmedProfilePreservesOldStoresAndIgnoresDraft() {
        val legacy = app.getSharedPreferences("xcertplay_airplay", 0)
        legacy.edit().putString("manual_hotspot_ssid", "Legacy car").putString("manual_hotspot_interface", "eth0.3").commit()
        val before = legacy.all.toMap()
        CarHotspotProfiles.saveConfirmed(app, profile)
        CarHotspotProfiles.saveDraft(app, profile.copy(ssid = "Unapplied draft"))

        val selected = CarHotspotProfiles.resolve(app, manual = { error("confirmed must precede legacy") })

        assertEquals(profile, selected?.profile)
        assertEquals("Car", app.getSharedPreferences("diplay_managed_hotspot", 0).getString("saved_ssid", null))
        assertEquals(before, legacy.all)
        assertEquals(profile, CarHotspotProfiles.confirmed(app))
        assertEquals("Unapplied draft", CarHotspotProfiles.draft(app)?.ssid)
    }

    @Test fun aDraftAloneCannotBecomeConnectionCredentials() {
        CarHotspotProfiles.saveDraft(app, profile)

        assertNull(CarHotspotProfiles.resolve(app, manual = { null }))
        assertFalse(app.getSharedPreferences("diplay_managed_hotspot", 0).contains("saved_ssid"))
        assertEquals(profile, CarHotspotProfiles.draft(app))
    }
}
