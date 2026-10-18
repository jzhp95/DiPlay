package com.shilapi.xcertplay

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w1100dp-h700dp", manifest = Config.NONE)
class ConnectionSettingsCardsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var activity: DiPlayActivity

    @Before fun setup() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
    }

    @After fun cleanup() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        activity.finish()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun connectionAndAuthenticationChoicesShareOneCard() {
        val parent = cards()
        assertEquals(2, parent.childCount)
        val first = descendants(parent.getChildAt(0)).filterIsInstance<TextView>().toList()
        for (id in listOf(R.string.settings_connection_method, R.string.mfi_certificate_signing_target)) {
            assertTrue(first.any { it.text.startsWith(activity.getString(id) + " · ") })
        }
    }

    @Test fun onlyTheSelectedNetworkDetailsCardIsShown() {
        for ((mode, title) in listOf(
            WirelessHotspotMode.MANUAL to R.string.hotspot_setup,
            WirelessHotspotMode.WIFI_P2P to R.string.settings_wifi_setup,
            WirelessHotspotMode.EXISTING_WIFI to R.string.settings_existing_wifi_setup,
        )) {
            AirPlayPersistence.saveWirelessHotspotMode(app, mode)
            val parent = cards()
            assertEquals(2, parent.childCount)
            val headings = texts(parent.getChildAt(1))
            assertTrue(headings.contains(activity.getString(title)))
            for (other in listOf(R.string.hotspot_setup, R.string.settings_wifi_setup, R.string.settings_existing_wifi_setup) - title) {
                assertFalse(headings.contains(activity.getString(other)))
            }
        }
    }

    @Test fun homeActionsAndOldConnectionMethodTilesAreAbsent() {
        val shown = texts(cards())
        for (id in listOf(R.string.choose_iphone, R.string.connect_phone, R.string.connect_with_usb,
            R.string.s_1_choose_your_connection, R.string.s_2_pair_your_iphone,
            R.string.s_3_connect, R.string.prefer_a_cable, R.string.open_connection_setup)) {
            assertFalse("Unexpected setting: $id", shown.contains(activity.getString(id)))
        }
    }

    @Test fun cancelKeepsTheModeAndDetailsUnchanged() {
        val dialog = choose(WirelessHotspotMode.MANUAL)
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(app))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertTrue(texts(cards()).contains(activity.getString(R.string.settings_wifi_setup)))
    }

    @Test fun savingAChoiceUpdatesTheDetailsWithoutOpeningACredentialsDialog() {
        for (mode in listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI, WirelessHotspotMode.WIFI_P2P)) {
            val dialog = choose(mode)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(mode, AirPlayPersistence.loadWirelessHotspotMode(app))
            assertFalse(dialog.isShowing)
            assertSame(dialog, ShadowAlertDialog.getLatestAlertDialog())
            val title = when (mode) {
                WirelessHotspotMode.MANUAL -> R.string.hotspot_setup
                WirelessHotspotMode.EXISTING_WIFI -> R.string.settings_existing_wifi_setup
                else -> R.string.settings_wifi_setup
            }
            assertTrue(texts(cards()).contains(activity.getString(title)))
        }
    }

    @Test fun switchingModesKeepsSavedNetworkCredentials() {
        AirPlayPersistence.saveManualHotspotSsid(app, "Car 5G")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "12345678")
        AirPlayPersistence.saveExistingWifiCredentials(app, "Router", "87654321")
        choose(WirelessHotspotMode.EXISTING_WIFI).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        choose(WirelessHotspotMode.MANUAL).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Car 5G", AirPlayPersistence.loadManualHotspotSsid(app))
        assertEquals("12345678", AirPlayPersistence.loadManualHotspotPassphrase(app))
        assertEquals("Router", AirPlayPersistence.loadExistingWifiSsid(app))
        assertEquals("87654321", AirPlayPersistence.loadExistingWifiPassphrase(app))
    }

    private fun cards(): LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }.also { parent ->
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderSections",
            ReflectionHelpers.ClassParameter(LinearLayout::class.java, parent),
            ReflectionHelpers.ClassParameter(Set::class.java, setOf(SettingsSection.CONNECTION_SETUP, SettingsSection.CONNECTION_DETAILS)))
        activity.setContentView(parent)
    }

    private fun choose(mode: WirelessHotspotMode): AlertDialog {
        descendants(cards()).filterIsInstance<Button>().single {
            it.text.startsWith(activity.getString(R.string.settings_connection_method) + " · ")
        }.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val title = activity.getString(when (mode) {
            WirelessHotspotMode.MANUAL -> R.string.settings_built_in_car_hotspot_recommended
            WirelessHotspotMode.EXISTING_WIFI -> R.string.existing_wifi_title
            else -> R.string.wifi_direct
        })
        val index = (0 until dialog.listView.adapter.count).single { dialog.listView.adapter.getItem(it).toString() == title }
        dialog.listView.performItemClick(null, index, index.toLong())
        return dialog
    }

    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
