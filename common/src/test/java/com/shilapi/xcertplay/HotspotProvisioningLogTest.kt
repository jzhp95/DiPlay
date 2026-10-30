package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.CarHotspotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", manifest = Config.NONE)
class HotspotProvisioningLogTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        ShadowLog.clear()
    }

    @Test fun savingHotspotCredentialsDoesNotLogItsName() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        DiPlayActivity::class.java.getDeclaredMethod("saveHotspotCredentials",String::class.java,String::class.java,
            com.shilapi.xcertplay.orchestration.ManualHotspotSecurity::class.java)
            .apply{isAccessible=true}.invoke(activity,"Saved hotspot","12345678",com.shilapi.xcertplay.orchestration.ManualHotspotSecurity.WPA2)
        val ssid = checkNotNull(com.shilapi.xcertplay.network.CarHotspotProfiles.saved(app)).ssid
        assertEquals("Saved hotspot",ssid)
        val lines = ShadowLog.getLogsForTag("DiPlay-Hotspot")
        lines.forEach { assertFalse("hotspot name reached logcat: ${it.msg}", it.msg.contains(ssid)) }
    }

    @Test fun anExistingHotspotIsNotProvisionedOrLogged() {
        AirPlayPersistence.saveManualHotspotSsid(app, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "12345678")
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        assertEquals("Car hotspot", AirPlayPersistence.loadManualHotspotSsid(app))
        assertTrue(ShadowLog.getLogsForTag("DiPlay-Hotspot").none { it.msg.startsWith("provisioned") })
    }
}
