package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
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
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets

/** One saved profile feeds both manual connections and explicitly managed hotspot startup. */
internal class CarHotspotSettingsUi(
    private val activity: Activity,
    private val changed: () -> Unit,
    private val redraw: () -> Unit = changed,
    private val controller: CarHotspotOperations? = null,
) {
    private val palette get() = DiPlayPalette.of((activity as? AppAppearanceOwner)?.currentAppNight ?: false)
    @Volatile private var closed = false
    private var busy = false
    private var initialReadStarted = false
    private var edited = false
    private var nameText: String? = null
    private var passwordText: String? = null
    private var passwordVisible = false
    private var securitySelection: ManualHotspotSecurity? = null
    private var bandSelection: ManualHotspotBand? = null
    private var channelSelection: Int? = null
    private var status: String? = null
    private var operationThread: Thread? = null
    private var activeDialog: AlertDialog? = null
    private fun dp(n: Int) = FlymeAutoStyle.dp(activity, n)
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun gap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(FlymeAutoStyle.CONTROL_GAP_DP) }
    private fun label(text: String, secondary: Boolean = true) = TextView(activity).apply {
        this.text = text
        textSize = if (secondary) FlymeAutoStyle.SECONDARY_TEXT_SP else FlymeAutoStyle.BODY_TEXT_SP
        setTextColor(if (secondary) palette.secondaryText else palette.primaryText)
    }
    private fun button(id: Int, primary: Boolean = false, action: () -> Unit) = Button(activity).apply {
        text = activity.getString(id)
        isEnabled = !busy
        FlymeAutoStyle.button(this, palette, primary)
        layoutParams = gap()
        setOnClickListener { action() }
    }
    private fun control() = controller ?: CarHotspotController(activity, ::log)
    private fun log(message: String) { DetailedDebugLog.record("hotspot-control", message) }
    private fun legacyProfile() = CarHotspotProfile(
        AirPlayPersistence.loadManualHotspotSsid(activity), AirPlayPersistence.loadManualHotspotPassphrase(activity),
        AirPlayPersistence.loadManualHotspotSecurity(activity), AirPlayPersistence.loadManualHotspotBand(activity),
        AirPlayPersistence.loadManualHotspotChannel(activity),
    ).takeIf { it.error() == null }

    fun build(parent: LinearLayout) {
        val saved = CarHotspotProfiles.saved(activity)
        val initial = saved ?: legacyProfile()
        if (nameText == null) nameText = initial?.ssid ?: AirPlayPersistence.DEFAULT_MANUAL_HOTSPOT_SSID
        if (passwordText == null) passwordText = initial?.passphrase ?: AirPlayPersistence.DEFAULT_MANUAL_HOTSPOT_PASSPHRASE
        if (securitySelection == null) securitySelection = initial?.security ?: ManualHotspotSecurity.WPA2
        if (bandSelection == null) bandSelection = initial?.band ?: ManualHotspotBand.AUTO
        if (channelSelection == null) channelSelection = initial?.channel ?: 0
        val toggle = SettingsWidgets.createSwitchRow(
            activity, activity.getString(R.string.settings_hotspot_boot), "",
            CarHotspotSettings.enabled(activity), SettingsTheme.card(palette), onChanged = {
                CarHotspotSettings.setEnabled(activity, it)
                if (!it) CarHotspotBootService.cancel(activity)
                log("boot-auto-enable=" + it)
            },
        )
        parent.addView(toggle.rowView)
        parent.addView(label(activity.getString(R.string.settings_hotspot_configure), false), gap())
        val name = field(parent, R.string.hotspot_name, R.string.settings_hotspot_ssid_placeholder, nameText.orEmpty())
        val password = field(parent, R.string.hotspot_password, R.string.settings_hotspot_password_placeholder,
            passwordText.orEmpty(), secret = true)
        watch(name) { nameText = it }
        watch(password) { passwordText = it }
        name.imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        name.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { hideKeyboard(password); true } else false
        }
        val securities = listOf(ManualHotspotSecurity.OPEN, ManualHotspotSecurity.WPA2,
            ManualHotspotSecurity.WPA3_TRANSITION, ManualHotspotSecurity.WPA3)
        val securityTitles = listOf(activity.getString(R.string.open), "WPA2", "WPA2/WPA3", "WPA3")
        val bands = listOf(ManualHotspotBand.AUTO, ManualHotspotBand.GHZ_2_4, ManualHotspotBand.GHZ_5)
        val bandTitles = listOf(activity.getString(R.string.auto), "2.4 GHz", "5 GHz")
        fun channels() = listOf(0) + CarHotspotProfile.CHANNELS.filter {
            when (bandSelection) {
                ManualHotspotBand.GHZ_2_4 -> it <= 14
                ManualHotspotBand.GHZ_5 -> it > 14
                else -> true
            }
        }
        fun channelTitle() = if (channelSelection == 0) activity.getString(R.string.auto) else channelSelection.toString()
        choice(parent, R.string.settings_hotspot_security, { securities.zip(securityTitles) },
            { securitySelection ?: ManualHotspotSecurity.WPA2 }) { securitySelection = it }
        var channelRow: Button? = null
        choice(parent, R.string.settings_hotspot_band, { bands.zip(bandTitles) },
            { bandSelection ?: ManualHotspotBand.AUTO }) {
            bandSelection = it
            if ((channelSelection ?: 0) !in channels()) channelSelection = 0
            channelRow?.text = activity.getString(R.string.settings_hotspot_channel) + " · " + channelTitle()
        }
        channelRow = choice(parent, R.string.settings_hotspot_channel,
            { channels().map { it to if (it == 0) activity.getString(R.string.auto) else it.toString() } },
            { channelSelection ?: 0 }) { channelSelection = it }
        val error = label("").apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(palette.accentText)
        }
        parent.addView(error, gap())
        parent.addView(button(R.string.settings_hotspot_save, primary = true) {
            val profile = CarHotspotProfile(name.text.toString().trim(), password.text.toString(),
                securitySelection ?: ManualHotspotSecurity.WPA2,
                bandSelection ?: ManualHotspotBand.AUTO, channelSelection ?: 0)
            val problem = problem(profile)
            if (problem != null) {
                error.text = problem
                error.visibility = View.VISIBLE
            } else {
                CarHotspotProfiles.saveSaved(activity, profile)
                nameText = profile.ssid
                passwordText = profile.passphrase
                edited = false
                status = activity.getString(R.string.settings_hotspot_credentials_saved)
                error.visibility = View.GONE
                hideKeyboard(password)
                changed()
            }
        })
        parent.addView(button(R.string.settings_hotspot_start) { startSavedHotspot() })
        status?.let { parent.addView(label(it), gap()) }
        if (saved == null && !initialReadStarted) loadInitialProfile()
    }

    private fun field(parent: LinearLayout, title: Int, hint: Int, value: String, secret: Boolean = false): EditText {
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(FlymeAutoStyle.CONTROL_HEIGHT_DP)
            setPadding(dp(FlymeAutoStyle.BUTTON_PADDING_HORIZONTAL_DP), 0,
                dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP), 0)
        }
        row.addView(label(activity.getString(title), secondary = false).apply {
            textSize = FlymeAutoStyle.BODY_TEXT_SP
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP),
                dp(FlymeAutoStyle.CONTROL_GAP_DP), dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP))
        }, LinearLayout.LayoutParams(-2, -1))
        val input = EditText(activity).apply {
            setText(value)
            setHint(hint)
            contentDescription = activity.getString(title)
            textSize = FlymeAutoStyle.BODY_TEXT_SP
            setTextColor(palette.secondaryText)
            setHintTextColor(palette.secondaryText)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            background = null
            setSingleLine()
            minimumHeight = dp(FlymeAutoStyle.CONTROL_HEIGHT_DP)
            setPadding(0, dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP),
                dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP), dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP))
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (secret && !passwordVisible) transformationMethod = PasswordTransformationMethod.getInstance()
            isEnabled = !busy
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        row.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        if (secret) {
            row.addView(Button(activity).apply {
                fun updateLabel() {
                    setText(if (passwordVisible) R.string.settings_hotspot_hide_password else R.string.settings_hotspot_show_password)
                }
                updateLabel()
                FlymeAutoStyle.button(this, palette, compact = true)
                minimumWidth = 0
                minWidth = 0
                val inset = dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP)
                setPadding(inset, inset, inset, inset)
                minimumHeight = dp(FlymeAutoStyle.CONTROL_HEIGHT_DP)
                isEnabled = !busy
                setOnClickListener {
                    passwordVisible = !passwordVisible
                    val cursor = input.selectionStart.coerceAtLeast(0)
                    input.transformationMethod = if (passwordVisible) null else PasswordTransformationMethod.getInstance()
                    input.setSelection(cursor.coerceAtMost(input.text.length))
                    updateLabel()
                }
            }, LinearLayout.LayoutParams(-2, -1))
        }
        fun paint() {
            row.background = GradientDrawable().apply {
                setColor(palette.button)
                cornerRadius = FlymeAutoStyle.radius(activity, row.height.takeIf { it > 0 } ?: row.minimumHeight)
                if (input.hasFocus()) setStroke(dp(FlymeAutoStyle.FOCUS_STROKE_DP), palette.focusRing)
            }
        }
        input.setOnFocusChangeListener { _, _ -> paint() }
        row.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) paint()
        }
        paint()
        parent.addView(row, gap())
        return input
    }

    private fun watch(input: EditText, save: (String) -> Unit) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                edited = true
                save(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun loadInitialProfile() {
        initialReadStarted = true
        Thread({
            val profile = runCatching { CarHotspotProfiles.resolve(activity, manual = ::legacyProfile, log = ::log)?.profile }.getOrNull()
            activity.runOnUiThread {
                if (closed || activity.isFinishing || activity.isDestroyed || edited || profile == null) return@runOnUiThread
                val latest = CarHotspotProfiles.saved(activity) ?: profile
                nameText = latest.ssid
                passwordText = latest.passphrase
                securitySelection = latest.security
                bandSelection = latest.band
                channelSelection = latest.channel
                redraw()
            }
        }, "carplay-hotspot-read").start()
    }

    private fun <T> choice(parent: LinearLayout, title: Int, options: () -> List<Pair<T, String>>,
        selected: () -> T, save: (T) -> Unit): Button {
        val row = DiPlayActivity.SettingButton(activity, palette.secondaryText, palette.accent).apply {
            FlymeAutoStyle.button(this, palette)
            textSize = FlymeAutoStyle.BODY_TEXT_SP
            isEnabled = !busy
        }
        fun update() {
            val value = options().firstOrNull { it.first == selected() }?.second.orEmpty()
            row.text = activity.getString(title) + " · " + value
        }
        update()
        row.setOnClickListener {
            val values = options()
            var index = values.indexOfFirst { it.first == selected() }.coerceAtLeast(0)
            activeDialog = activity.appDialogBuilder().setTitle(title)
                .setSingleChoiceItems(values.map { it.second }.toTypedArray(), index) { _, picked -> index = picked }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (!closed && values[index].first != selected()) {
                        save(values[index].first)
                        edited = true
                        update()
                    }
                }.setNegativeButton(R.string.cancel, null).show()
        }
        parent.addView(row, gap())
        return row
    }

    private fun startSavedHotspot() {
        if (busy || closed) return
        val profile = CarHotspotProfiles.saved(activity)
        if (profile == null || edited) {
            status = activity.getString(R.string.settings_hotspot_save_first)
            redraw()
            return
        }
        fun apply() {
            if (!closed && !activity.isFinishing && !activity.isDestroyed) {
                runOperation { control().apply(profile) { closed || CarPlayBackgroundSession.hasSession() } }
            }
        }
        if (!CarPlayBackgroundSession.hasSession()) {
            apply()
            return
        }
        activeDialog = activity.appDialogBuilder().setTitle(R.string.settings_hotspot_start)
            .setPositiveButton(R.string.settings_hotspot_disconnect_and_start) { _, _ ->
                busy = true
                redraw()
                CarPlayBackgroundSession.stop {
                    activity.runOnUiThread {
                        busy = false
                        if (closed || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        if (CarPlayBackgroundSession.hasSession()) {
                            status = activity.getString(R.string.settings_hotspot_session_changed)
                            redraw()
                        } else apply()
                    }
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun problem(profile: CarHotspotProfile): String? = when (profile.error()) {
        null -> null
        CarHotspotProfile.Error.CREDENTIALS -> ManualHotspotValidation.error(profile.ssid, profile.passphrase)
            ?.let { activity.getString(it.messageResource()) } ?: activity.getString(R.string.settings_hotspot_invalid_configuration)
        CarHotspotProfile.Error.SECURITY -> activity.getString(R.string.settings_hotspot_invalid_security_password)
        else -> activity.getString(R.string.settings_hotspot_invalid_configuration)
    }

    private fun hideKeyboard(view: View) {
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    companion object {
        fun openSystemSettings(activity: Activity) {
            for (action in listOf("com.android.settings.WIFI_TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)) {
                val intent = Intent(action)
                if (activity.packageManager.resolveActivity(intent, 0) != null &&
                    runCatching { activity.startActivity(intent) }.isSuccess) return
            }
            android.widget.Toast.makeText(activity, R.string.settings_hotspot_settings_unavailable,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun runOperation(action: () -> CarHotspotControlResult) {
        if (busy || closed) return
        busy = true; status = activity.getString(R.string.settings_hotspot_working); redraw()
        operationThread = Thread({
            val result = runCatching(action).getOrElse {
                log("operation failed " + it.javaClass.simpleName)
                CarHotspotControlResult(false, null, null, "operation-exception")
            }
            log("result success=" + result.success + " running=" + result.running + " " + result.diagnostic)
            activity.runOnUiThread {
                busy = false
                if (closed || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                status = activity.getString(if (result.success) R.string.settings_hotspot_operation_success else R.string.settings_hotspot_operation_failed)
                android.widget.Toast.makeText(activity, status, android.widget.Toast.LENGTH_LONG).show()
                if (result.success) changed() else redraw()
            }
        }, "carplay-hotspot-control").also { it.start() }
    }

    fun close() {
        closed = true
        activeDialog?.dismiss()
        activeDialog = null
        operationThread?.interrupt()
    }
}
