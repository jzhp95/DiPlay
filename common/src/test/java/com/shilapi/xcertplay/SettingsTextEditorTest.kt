package com.shilapi.xcertplay

import android.app.AlertDialog
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotProfile
import com.shilapi.xcertplay.network.CarHotspotProfiles
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SettingsTextEditorTest {
    @Test fun cancelAndSaveKeepPersistenceOwnedByTheFinalSaveAction() {
        val owner = Robolectric.buildActivity(HotspotSettingsPreviewActivity::class.java).setup()
        val activity = owner.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val saved = CarHotspotProfile("Before", "12345678", ManualHotspotSecurity.WPA2)
        CarHotspotProfiles.saveSaved(activity, saved)
        var changes = 0
        val ui = CarHotspotSettingsUi(activity, { changes++ }, redraw = {})
        try {
            val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; ui.build(this) }
            val cancelled = openHotspotEditor(activity, root, R.string.hotspot_name)
            editorViews(cancelled.window!!.decorView).filterIsInstance<EditText>().single().setText("Cancelled")
            cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(saved, CarHotspotProfiles.saved(activity))
            val reopened = openHotspotEditor(activity, root, R.string.hotspot_name)
            assertEquals("Before", editorViews(reopened.window!!.decorView).filterIsInstance<EditText>().single().text.toString())
            reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            editHotspotDraft(activity, root, R.string.hotspot_name, "After")
            assertEquals("Dialog Save only changes the pending draft", saved, CarHotspotProfiles.saved(activity))
            assertEquals(0, changes)
            editorViews(root).filterIsInstance<android.widget.Button>().single {
                it.text == activity.getString(R.string.settings_hotspot_save)
            }.performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(saved.copy(ssid = "After"), CarHotspotProfiles.saved(activity))
            assertEquals(1, changes)
        } finally { ui.close(); owner.pause().stop().destroy() }
    }

    @Test fun cancellingEditorRestoresAnInitialReadThatArrivedWhileEditing() {
        val owner = Robolectric.buildActivity(HotspotSettingsPreviewActivity::class.java).setup()
        val activity = owner.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val saved = CarHotspotProfile("Read from car", "12345678", ManualHotspotSecurity.WPA2)
        CarHotspotProfiles.saveSaved(activity, saved)
        var redraws = 0
        val ui = CarHotspotSettingsUi(activity, {}, redraw = { redraws++ })
        try {
            val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; ui.build(this) }
            val dialog = openHotspotEditor(activity, root, R.string.hotspot_name)
            org.robolectric.util.ReflectionHelpers.setField(ui, "pendingInitialProfile", saved)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, redraws)
            assertEquals("Read from car", org.robolectric.util.ReflectionHelpers.getField<String>(ui, "nameText"))
            assertEquals(saved, CarHotspotProfiles.saved(activity))
        } finally { ui.close(); owner.pause().stop().destroy() }
    }

    @Test fun passwordStartsMaskedAndClearAndEyeOnlyChangeTheDraft() {
        val owner = Robolectric.buildActivity(HotspotSettingsPreviewActivity::class.java).setup()
        val activity = owner.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        var result: String? = null
        val dialog = SettingsTextEditor.show(activity, "热点密码", "original", true) { result = it }
        try {
            val views = editorViews(dialog.window!!.decorView).toList()
            val input = views.filterIsInstance<EditText>().single()
            assertNotNull(input.transformationMethod)
            val eye = views.filterIsInstance<ImageButton>().single {
                it.contentDescription == activity.getString(R.string.settings_hotspot_show_password)
            }
            eye.performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(input.transformationMethod)
            assertEquals("original", input.text.toString())
            eye.performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNotNull(input.transformationMethod)
            views.filterIsInstance<ImageButton>().single {
                it.contentDescription == activity.getString(R.string.settings_clear_input)
            }.performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(input.text.isEmpty())
            assertNull(result)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("", result)
        } finally { dialog.dismiss(); owner.pause().stop().destroy() }
    }

    @Test fun choiceRowsKeepSearchTextAndTransformBothInitialAndUpdatedValues() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val row = DiPlayActivity.SettingButton(context, DiPlayPalette.LIGHT.secondaryText, DiPlayPalette.LIGHT.accent)
        FlymeAutoStyle.button(row, DiPlayPalette.LIGHT)
        for (value in listOf("安全性 · WPA2", "安全性 · WPA3", "安全性 · WPA2")) {
            row.text = value
            assertEquals(value, row.text.toString())
            assertEquals(value.replace(" · ", "\n"), row.transformationMethod.getTransformation(row.text, row).toString().replace("\u200B", ""))
        }
        row.action = true
        row.text = "开启 · ADB"
        assertEquals("开启 · ADB", row.transformationMethod.getTransformation(row.text, row).toString().replace("\u200B", ""))
    }
}
