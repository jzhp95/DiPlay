package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class RetiredVehiclePreferencesTest {
    @Test fun oldVehicleOptInsAreIgnoredWithoutChangingGenericConnectionSettings() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().putBoolean("cluster_map_enabled", true)
            .putBoolean("adb_cluster_activity_enabled", true)
            .putBoolean("platform21_cluster_enabled", true)
            .putBoolean("hide_byd_call_popup", true)
            .putBoolean("bt_suspend_during_carplay", true)
            .putBoolean("cluster_speedometer_enabled", true).putInt("cluster_small_window_mode", 2).commit()
        try {
            assertFalse(AirPlayPersistence.loadAdbClusterEnabled(app))
            assertFalse(AirPlayPersistence.loadLegacyClusterEnabled(app))
            assertFalse(AirPlayPersistence.loadHideBydCallPopup(app))
            assertFalse(AirPlayPersistence.loadBtSuspendDuringCarplay(app))
            assertEquals(0, AirPlayPersistence.loadClusterSmallWindowMode(app))
            assertFalse(AirPlayPersistence.loadClusterSpeedometerEnabled(app))
            AirPlayPersistence.saveFps(app, 60)
            AirPlayPersistence.saveAutoStartOnBoot(app, true)
            AirPlayPersistence.saveLauncherMapSharing(app, true)
            assertEquals(60, AirPlayPersistence.loadFps(app))
            assertTrue(AirPlayPersistence.loadAutoStartOnBoot(app))
            assertTrue(AirPlayPersistence.loadLauncherMapSharing(app))
        } finally { prefs.edit().clear().commit() }
    }
}
