package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotOperations
import com.shilapi.xcertplay.network.CarHotspotController
import com.shilapi.xcertplay.network.CarHotspotControlResult
import com.shilapi.xcertplay.network.CarHotspotProfile
import com.shilapi.xcertplay.network.CarHotspotProfiles
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets

/** App-owned hotspot controls; firmware writes and profile precedence live in shared/network. */
internal class CarHotspotSettingsUi(
    private val activity: Activity,
    private val changed: () -> Unit,
    private val manualDetails: () -> Unit,
    private val redraw: () -> Unit = changed,
    private val controller: CarHotspotOperations? = null,
) {
    private val palette get() = DiPlayPalette.of((activity as? AppAppearanceOwner)?.currentAppNight ?: false)
    @Volatile private var closed = false
    private var busy = false
    private var status: String? = null
    private fun dp(n: Int) = FlymeAutoStyle.dp(activity, n)
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun label(text: String) = TextView(activity).apply {
        this.text = text; textSize = FlymeAutoStyle.BODY_TEXT_SP; setTextColor(palette.secondaryText)
    }
    private fun button(id: Int, action: () -> Unit) = Button(activity).apply {
        text = activity.getString(id); isAllCaps = false; isEnabled = !busy
        FlymeAutoStyle.button(this, palette)
        layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(FlymeAutoStyle.CONTROL_GAP_DP) }
        setOnClickListener { action() }
    }
    private fun control() = controller ?: CarHotspotController(activity, ::log)
    private fun log(message: String) { DetailedDebugLog.record("hotspot-control", message) }

    fun build(parent: LinearLayout) {
        val toggle = SettingsWidgets.createSwitchRow(activity, activity.getString(R.string.settings_hotspot_boot),
            activity.getString(R.string.settings_hotspot_boot_description), CarHotspotSettings.enabled(activity),
            SettingsTheme.card(palette), onChanged = {
                CarHotspotSettings.setEnabled(activity, it)
                if (!it) CarHotspotBootService.cancel(activity)
                log("boot-auto-enable=$it")
            })
        parent.addView(toggle.rowView)
        parent.addView(button(R.string.settings_hotspot_system_settings) { openSystemSettings() })
        parent.addView(button(R.string.settings_hotspot_configure) { loadEditor() })
        parent.addView(button(R.string.settings_hotspot_start) {
            runOperation { control().enable { closed } }
        })
        parent.addView(button(R.string.settings_hotspot_manual) { manualDetails() })
        parent.addView(label(activity.getString(R.string.settings_hotspot_priority_description)))
        status?.let { parent.addView(label(it)) }
    }

    fun close() { closed = true }

    private fun openSystemSettings() {
        // Same first action/fallback as the reference package; no vehicle-specific launcher changes.
        for (action in listOf("com.android.settings.WIFI_TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)) {
            val intent = Intent(action)
            if (activity.packageManager.resolveActivity(intent, 0) != null && runCatching { activity.startActivity(intent) }.isSuccess) return
        }
        android.widget.Toast.makeText(activity, R.string.settings_hotspot_settings_unavailable, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun loadEditor() {
        if (busy) return
        busy = true; status = activity.getString(R.string.settings_hotspot_reading); redraw()
        Thread({
            val current = runCatching { control().read() }.getOrNull()
            activity.runOnUiThread {
                busy = false
                if (closed || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                status = null; redraw()
                showEditor(current ?: CarHotspotProfiles.draft(activity) ?: CarHotspotProfiles.confirmed(activity))
            }
        }, "carplay-hotspot-read").start()
    }

    private fun showEditor(current: CarHotspotProfile?) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fun input(id: Int, value: String, secret: Boolean = false) = EditText(activity.appDialogContext()).apply {
            hint = activity.getString(id); setText(value); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            fields.addView(label(activity.getString(id)))
            fields.addView(this, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val ssid = input(R.string.hotspot_name, current?.ssid.orEmpty())
        val password = input(R.string.hotspot_password, current?.passphrase.orEmpty(), secret = true)
        fields.addView(CheckBox(activity.appDialogContext()).apply {
            text = activity.getString(R.string.show_password)
            setOnCheckedChangeListener { _, show -> password.transformationMethod = if (show) null else android.text.method.PasswordTransformationMethod.getInstance() }
        })
        val securities = listOf(ManualHotspotSecurity.OPEN, ManualHotspotSecurity.WPA2, ManualHotspotSecurity.WPA3_TRANSITION, ManualHotspotSecurity.WPA3)
        val securityTitles = listOf(activity.getString(R.string.open), "WPA2", "WPA2/WPA3", "WPA3")
        var security = current?.security ?: ManualHotspotSecurity.WPA2
        val bands = listOf(ManualHotspotBand.AUTO, ManualHotspotBand.GHZ_2_4, ManualHotspotBand.GHZ_5)
        val bandTitles = listOf(activity.getString(R.string.auto), "2.4 GHz", "5 GHz")
        var band = current?.band ?: ManualHotspotBand.AUTO
        fun choice(titleId: Int, options: List<String>, selected: () -> Int, save: (Int) -> Unit) {
            val control = button(titleId) {}.apply { text = "${activity.getString(titleId)} · ${options[selected()]}" }
            control.setOnClickListener {
                var pending = selected()
                activity.appDialogBuilder().setTitle(titleId).setSingleChoiceItems(options.toTypedArray(), pending) { _, index -> pending = index }
                    .setPositiveButton(R.string.save) { _, _ -> save(pending); control.text = "${activity.getString(titleId)} · ${options[selected()]}" }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            fields.addView(control, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        choice(R.string.settings_hotspot_security, securityTitles, { securities.indexOf(security) }) { security = securities[it] }
        choice(R.string.settings_hotspot_band, bandTitles, { bands.indexOf(band) }) { band = bands[it] }
        val channel = input(R.string.settings_hotspot_channel, current?.channel?.takeIf { it != 0 }?.toString().orEmpty()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        fields.addView(label(activity.getString(R.string.settings_hotspot_channel_description)))
        fields.addView(label(activity.getString(R.string.settings_hotspot_apply_description)))
        val error = label(""); fields.addView(error)
        val dialog = activity.appDialogBuilder().setTitle(R.string.settings_hotspot_configure).setView(fields)
            .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val radioChannel = if (channel.text.isBlank()) 0 else channel.text.toString().toIntOrNull()
                val profile = CarHotspotProfile(ssid.text.toString().trim(), password.text.toString(), security, band, radioChannel ?: -1)
                if (profile.error() != null) {
                    error.text = activity.getString(R.string.settings_hotspot_invalid_configuration)
                    return@setOnClickListener
                }
                CarHotspotProfiles.saveDraft(activity, profile)
                dialog.dismiss()
                runOperation { control().apply(profile) { closed } }
            }
        }
        dialog.show()
    }

    private fun runOperation(action: () -> CarHotspotControlResult) {
        if (busy) return
        busy = true; status = activity.getString(R.string.settings_hotspot_working); redraw()
        Thread({
            val result = runCatching(action).getOrElse {
                log("operation failed ${it.javaClass.simpleName}")
                CarHotspotControlResult(false, null, null, "operation-exception")
            }
            log("result success=${result.success} running=${result.running} ${result.diagnostic}")
            activity.runOnUiThread {
                busy = false
                if (closed || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                status = activity.getString(if (result.success) R.string.settings_hotspot_operation_success else R.string.settings_hotspot_operation_failed)
                android.widget.Toast.makeText(activity, status, android.widget.Toast.LENGTH_LONG).show()
                if (result.success) changed() else redraw()
            }
        }, "carplay-hotspot-control").start()
    }
}
