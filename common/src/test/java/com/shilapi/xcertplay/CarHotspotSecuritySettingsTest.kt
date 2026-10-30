package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotControlResult
import com.shilapi.xcertplay.network.CarHotspotOperations
import com.shilapi.xcertplay.network.CarHotspotProfile
import com.shilapi.xcertplay.network.CarHotspotProfiles
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarHotspotSecuritySettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var activity: Activity
    private lateinit var ui: CarHotspotSettingsUi
    private lateinit var fake:CarHotspotOperations
    private val profile = CarHotspotProfile("Car hotspot", "12345678", ManualHotspotSecurity.WPA2)

    @Before fun setup() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay_managed_hotspot", 0).edit().clear().commit()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        CarHotspotProfiles.saveSaved(app, profile)
        fake = object : CarHotspotOperations {
            override fun read(): CarHotspotProfile? = null
            override fun apply(profile: CarHotspotProfile, cancelled: () -> Boolean) =
                CarHotspotControlResult(!cancelled(), !cancelled(), profile, "test")
            override fun enable(cancelled: () -> Boolean) =
                error("Startup must apply the saved credentials, never enable the old system profile")
        }
        ui = CarHotspotSettingsUi(activity, changed = {}, redraw = {}, controller = fake)
    }

    @After fun cleanup() {
        ui.close()
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        activity.finish()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun editingCredentialsPreservesSavedWpa3Modes() {
        for (mode in listOf(ManualHotspotSecurity.WPA3, ManualHotspotSecurity.WPA3_TRANSITION)) {
            CarHotspotProfiles.saveSaved(app, profile.copy(security = mode))
            ui.close();ui=CarHotspotSettingsUi(activity,changed={},redraw={},controller=fake)
            val card = card()
            editHotspotDraft(activity, card, R.string.hotspot_password, "87654321")
            button(card, R.string.settings_hotspot_save).performClick()
            assertEquals(mode, AirPlayPersistence.loadManualHotspotSecurity(app))
            assertEquals("87654321", AirPlayPersistence.loadManualHotspotPassphrase(app))
        }
    }

    @Test fun selectingWpa3RequiresAnExplicitCardSave() {
        val root=card();chooseSecurity(root,"WPA3")
        assertEquals(profile,CarHotspotProfiles.saved(app))
        button(root,R.string.settings_hotspot_save).performClick()
        assertEquals(ManualHotspotSecurity.WPA3,CarHotspotProfiles.saved(app)?.security)
    }
    @Test fun cancellingTheSecurityChoiceLeavesTheProfileUnchanged() {
        val root=card()
        descendants(root).filterIsInstance<Button>().single{it.text.startsWith(activity.getString(R.string.settings_hotspot_security)+" · ")}.performClick()
        val choice=ShadowAlertDialog.getLatestAlertDialog()
        choice.listView.performItemClick(null,3,3)
        choice.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        button(root,R.string.settings_hotspot_save).performClick()
        assertEquals(profile,CarHotspotProfiles.saved(app))
    }
    @Test fun emptyPasswordUsesOpenAndRetypingBeforeSavePreservesTheProtectedMode() {
        CarHotspotProfiles.saveSaved(app, profile.copy(security = ManualHotspotSecurity.WPA3))
        val card = card()
        editHotspotDraft(activity, card, R.string.hotspot_password, "")
        editHotspotDraft(activity, card, R.string.hotspot_password, "87654321")
        button(card, R.string.settings_hotspot_save).performClick()
        assertEquals(ManualHotspotSecurity.WPA3, AirPlayPersistence.loadManualHotspotSecurity(app))
        editHotspotDraft(activity, card, R.string.hotspot_password, "")
        chooseSecurity(card,activity.getString(R.string.open))
        button(card, R.string.settings_hotspot_save).performClick()
        assertEquals(ManualHotspotSecurity.OPEN, AirPlayPersistence.loadManualHotspotSecurity(app))
        assertEquals("", AirPlayPersistence.loadManualHotspotPassphrase(app))
    }

    @Test fun credentialValidationAndCancelledInterfaceEditsPreserveTheOverride() {
        AirPlayPersistence.saveManualHotspotInterface(app, "eth0.3")
        val card = card()
        editHotspotDraft(activity, card, R.string.hotspot_name, "")
        button(card, R.string.settings_hotspot_save).performClick()
        assertEquals(profile, CarHotspotProfiles.saved(app))
        assertEquals("eth0.3", AirPlayPersistence.loadManualHotspotInterface(app))
        assertFalse(descendants(card).filterIsInstance<Button>().any{
            it.text.startsWith(activity.getString(R.string.settings_hotspot_interface_short)+" · ")
        })
        assertEquals("eth0.3",AirPlayPersistence.loadManualHotspotInterface(app))
    }

    private fun card() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; ui.build(this) }
    private fun chooseSecurity(root: View, name: String) {
        descendants(root).filterIsInstance<Button>().single {
            it.text.startsWith(activity.getString(R.string.settings_hotspot_security) + " · ")
        }.performClick()
        val choice = ShadowAlertDialog.getLatestAlertDialog()
        val index = (0 until choice.listView.adapter.count).single { choice.listView.adapter.getItem(it).toString() == name }
        choice.listView.performItemClick(null, index, index.toLong())
        choice.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun button(card: View, id: Int) = descendants(card).filterIsInstance<Button>().single { it.text == activity.getString(id) }
    private fun inputs(root: View) = descendants(root).filterIsInstance<EditText>().toList()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
