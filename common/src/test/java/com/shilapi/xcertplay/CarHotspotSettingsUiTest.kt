package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotSettingsUiTest {
    private val profile = CarHotspotProfile("Car", "12345678", ManualHotspotSecurity.WPA2, ManualHotspotBand.GHZ_5, 149)
    private lateinit var activity: Activity
    private lateinit var ui: CarHotspotSettingsUi
    private lateinit var fake: Fake
    private var changes = 0
    private var manual = 0
    private class Fake : CarHotspotOperations {
        @Volatile var applied: CarHotspotProfile? = null
        @Volatile var enabled = false
        override fun read(): CarHotspotProfile? = null
        override fun apply(profile: CarHotspotProfile, cancelled: () -> Boolean): CarHotspotControlResult {
            applied = profile
            return CarHotspotControlResult(true, true, profile, "test-verified")
        }
        override fun enable(cancelled: () -> Boolean): CarHotspotControlResult {
            enabled = true
            return CarHotspotControlResult(true, true, null, "test-started")
        }
    }
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        for (name in listOf("diplay_managed_hotspot", "diplay_car_hotspot", "xcertplay_airplay")) app.getSharedPreferences(name,0).edit().clear().commit()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        fake = Fake()
        ui = CarHotspotSettingsUi(activity, changed = { changes++ }, manualDetails = { manual++ }, redraw = {}, controller = fake)
    }
    @After fun cleanup() { ui.close(); ShadowAlertDialog.getLatestAlertDialog()?.dismiss(); activity.finish(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun cardHasExactlyTheRequestedFiveActionsAndBootDefaultsOff() {
        val parent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        ui.build(parent)
        for (id in listOf(R.string.settings_hotspot_boot,R.string.settings_hotspot_system_settings,R.string.settings_hotspot_configure,R.string.settings_hotspot_start,R.string.settings_hotspot_manual))
            assertTrue(views(parent).filterIsInstance<TextView>().any { it.text == activity.getString(id) })
        assertFalse(CarHotspotSettings.enabled(activity))
        views(parent).filterIsInstance<Button>().single { it.text == activity.getString(R.string.settings_hotspot_manual) }.performClick()
        assertEquals(1, manual)
    }
    @Test fun cancellingTheEditorNeverWritesConfiguration() {
        val dialog = editor()
        inputs(dialog)[0].setText("Cancelled")
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
        assertNull(fake.applied); assertNull(CarHotspotProfiles.draft(activity)); assertNull(CarHotspotProfiles.confirmed(activity))
    }
    @Test fun invalidRadioParametersKeepDialogOpenAndNeverWrite() {
        val dialog = editor()
        inputs(dialog)[2].setText("6") // Current band is 5 GHz.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing); assertNull(fake.applied)
        assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.settings_hotspot_invalid_configuration) })
    }
    @Test fun saveSubmitsEveryFieldAndKeepsManualPreferencesUntouched() {
        val legacy = activity.getSharedPreferences("xcertplay_airplay", 0)
        legacy.edit().putString("manual-sentinel", "unchanged").commit(); val original = legacy.all.toMap()
        val dialog = editor()
        inputs(dialog)[0].setText("Configured Car"); inputs(dialog)[1].setText("abcdef12")
        select(dialog, R.string.settings_hotspot_security, "WPA3")
        select(dialog, R.string.settings_hotspot_band, "2.4 GHz")
        inputs(dialog)[2].setText("6")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { changes == 1 }
        assertEquals(CarHotspotProfile("Configured Car", "abcdef12", ManualHotspotSecurity.WPA3, ManualHotspotBand.GHZ_2_4,6), fake.applied)
        assertEquals(fake.applied, CarHotspotProfiles.draft(activity))
        assertEquals(original, legacy.all)
    }
    @Test fun startDoesNotApplyOrOverwriteAProfile() {
        val parent = LinearLayout(activity);ui.build(parent)
        views(parent).filterIsInstance<Button>().single { it.text == activity.getString(R.string.settings_hotspot_start) }.performClick()
        await { changes == 1 }
        assertTrue(fake.enabled);assertNull(fake.applied);assertNull(CarHotspotProfiles.draft(activity))
    }
    private fun editor(): AlertDialog {
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "showEditor", ReflectionHelpers.ClassParameter(CarHotspotProfile::class.java, profile))
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    private fun inputs(dialog: AlertDialog) = views(dialog.window!!.decorView).filterIsInstance<EditText>().toList()
    private fun select(dialog: AlertDialog, title: Int, value: String) {
        views(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text.startsWith(activity.getString(title)+" · ") }.performClick()
        val choice=ShadowAlertDialog.getLatestAlertDialog()
        val index=(0 until choice.listView.adapter.count).single { choice.listView.adapter.getItem(it).toString()==value }
        choice.listView.performItemClick(null,index,index.toLong())
        choice.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle()
    }
    private fun await(predicate:()->Boolean) {
        val end=System.nanoTime()+3_000_000_000L
        while(!predicate() && System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertTrue("Background operation did not complete",predicate())
    }
    private fun views(v:View):Sequence<View> = sequence {yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(views(v.getChildAt(i)))}
}
