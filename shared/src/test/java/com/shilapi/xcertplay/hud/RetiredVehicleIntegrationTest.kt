package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.media.AmbientMusicController
import com.shilapi.xcertplay.media.AmbientMusicSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class RetiredVehicleIntegrationTest {
    @Test fun everyLegacyVendorOptInIsIgnored() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("diplay_byd_outputs", 0)
        val keys = listOf("navigation_enabled", "cluster_stream_pause", "battery_to_iphone",
            "wheel_speed_to_iphone", "video_while_parked", "cluster_song", "cluster_song_on_change",
            "hud_song", "carplay_calls", "carplay_call_controls_experimental", "legacy_vehicle_probe",
            "phone_audio_route_experimental", "phone_audio_route_apps_experimental", "oem_cluster_freeze",
            "fce_cluster_guidance_experimental")
        prefs.edit().also { editor -> keys.forEach { editor.putBoolean(it, true) } }
            .putString("oem_cluster_hold", "PACKAGE").commit()
        try {
            val switches = listOf(BydOutputSettings.enabled(app), BydOutputSettings.clusterStreamPause(app),
                BydOutputSettings.batteryToIphoneActive(app), BydOutputSettings.wheelSpeedToIphoneActive(app),
                BydOutputSettings.videoWhileParkedActive(app), BydOutputSettings.clusterSong(app),
                BydOutputSettings.clusterSongOnChange(app), BydOutputSettings.hudSong(app),
                BydOutputSettings.carPlayCalls(app), BydOutputSettings.carPlayCallControls(app),
                BydOutputSettings.phoneAudioRoute(app), BydOutputSettings.phoneAudioRouteApps(app),
                BydOutputSettings.fceCluster(app), BydOutputSettings.available(app),
                BydOutputSettings.navigationAvailable(app), BydOutputSettings.standaloneHudAvailable(app))
            assertTrue(switches.none { it })
            assertEquals(BydOemClusterHold.OFF, BydOutputSettings.oemClusterHold(app))
            BydNavigationOutputs.start(app)
            assertNull(BydNavigationOutputs.batteryStatus(app).snapshot())
            assertNull(BydNavigationOutputs.wheelSpeed(app).drain())
            assertNull(BydNavigationOutputs.parked(app))
            assertNull(BydNavigationOutputs.clusterNaviMode())
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun phoneCallStateSurvivesTheRemovalOfVendorOutputs() {
        BydNavigationOutputs.endNow()
        BydNavigationOutputs.onFrame(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(
            CarPlayCallState.CALL_STATE_UPDATE) { u8(2, 2); string(1, "测试来电"); string(4, "test-call") })
        assertEquals(CarPlayCallCard.Phase.RINGING, BydNavigationOutputs.carPlayCall()?.phase)
        assertEquals("测试来电", CarPlayPhoneState.current()?.name)
        BydNavigationOutputs.endNow()
        assertNull(CarPlayPhoneState.current())
    }

    @Test fun legacyLampSettingsAllocateNoSinkOrVendorWorker() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("ambient_music", 0)
        prefs.edit().putBoolean("enabled", true).commit()
        try {
            assertFalse(AmbientMusicSettings.load(app).enabled)
            assertEquals(0L, AmbientMusicController.openSink(app))
            assertFalse(AmbientMusicController.checkSupport(app).get())
            AmbientMusicController.settingsChanged(app)
            AmbientMusicController.closeSink(0L)
        } finally { prefs.edit().clear().commit() }
    }
}
