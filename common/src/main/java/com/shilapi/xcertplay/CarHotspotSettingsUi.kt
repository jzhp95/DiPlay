package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotOperations
import com.shilapi.xcertplay.network.CarHotspotController
import com.shilapi.xcertplay.network.CarHotspotControlResult
import com.shilapi.xcertplay.network.CarHotspotProfile
import com.shilapi.xcertplay.network.CarHotspotProfiles
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.ObservedHotspotInfo
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
    private var activeSyncGeneration = 0
    private var editVersion = 0
    private var pendingInitialProfile: CarHotspotProfile? = null
    private var pendingObservedHotspot: ObservedHotspotInfo? = null
    private var pendingIsForceSync = false
    private var pendingStartEditVersion = 0
    private var edited = false
    private var nameText: String? = null
    private var passwordText: String? = null
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
        FlymeAutoStyle.text(this, if (secondary) FlymeAutoStyle.TextRole.SECONDARY else FlymeAutoStyle.TextRole.LABEL)
        setTextColor(if (secondary) palette.secondaryText else palette.primaryText)
    }
    private fun button(id: Int, primary: Boolean = false, action: () -> Unit) = Button(activity).apply {
        text = activity.getString(id)
        isEnabled = !busy
        FlymeAutoStyle.settingsButton(this, palette, primary)
        layoutParams = gap()
        setOnClickListener { action() }
    }
    private fun control() = controller ?: CarHotspotController(activity, ::log)
    private fun log(message: String) { DetailedDebugLog.record("hotspot-control", message) }
    private fun legacyProfile() = AirPlayPersistence.loadConfiguredManualHotspotProfile(activity)

    fun build(parent: LinearLayout) {
        val saved = CarHotspotProfiles.saved(activity)
        val initial = saved ?: legacyProfile()
        if (nameText == null) nameText = initial?.ssid ?: ""
        if (passwordText == null) passwordText = initial?.passphrase ?: ""
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
        field(parent, R.string.hotspot_name, nameText.orEmpty()) { nameText = it }
        field(parent, R.string.hotspot_password, passwordText.orEmpty(), secret = true) { passwordText = it }
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
            { securitySelection ?: ManualHotspotSecurity.WPA2 }) {
            securitySelection = it
            editVersion++
            edited = true
        }
        var channelRow: Button? = null
        choice(parent, R.string.settings_hotspot_band, { bands.zip(bandTitles) },
            { bandSelection ?: ManualHotspotBand.AUTO }) {
            bandSelection = it
            editVersion++
            edited = true
            if ((channelSelection ?: 0) !in channels()) channelSelection = 0
            channelRow?.text = activity.getString(R.string.settings_hotspot_channel) + " · " + channelTitle()
        }
        channelRow = choice(parent, R.string.settings_hotspot_channel,
            { channels().map { it to if (it == 0) activity.getString(R.string.auto) else it.toString() } },
            { channelSelection ?: 0 }) {
            channelSelection = it
            editVersion++
            edited = true
        }
        val error = label("").apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(palette.accentText)
        }
        parent.addView(error, gap())
        val saveButton = button(R.string.settings_hotspot_save, primary = true) {
            val profile = CarHotspotProfile(nameText.orEmpty().trim(), passwordText.orEmpty(),
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
                editVersion++
                edited = false
                status = activity.getString(R.string.settings_hotspot_credentials_saved)
                error.visibility = View.GONE
                changed()
            }
        }
        val syncButton = button(R.string.settings_hotspot_sync) {
            status = activity.getString(R.string.settings_hotspot_reading)
            redraw()
            loadInitialProfile(forceSync = true)
        }
        val startButton = button(R.string.settings_hotspot_start) { startSavedHotspot() }
        FlymeAutoStyle.action(syncButton, palette, textOnly = true)
        FlymeAutoStyle.action(startButton, palette, textOnly = true)
        FlymeAutoStyle.action(saveButton, palette, primary = true)
        val actions = LinearLayout(activity).apply {
            orientation = if (FlymeAutoStyle.compact(activity) || activity.resources.configuration.fontScale > 1.3f)
                LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        listOf(syncButton, startButton, saveButton).forEachIndexed { index, action ->
            actions.addView(action, LinearLayout.LayoutParams(
                if (actions.orientation == LinearLayout.HORIZONTAL) 0 else -1, -2,
                if (actions.orientation == LinearLayout.HORIZONTAL) 1f else 0f).apply {
                if (index > 0 && actions.orientation == LinearLayout.HORIZONTAL) marginStart = dp(FlymeAutoStyle.CONTROL_GAP_DP)
                if (index > 0 && actions.orientation == LinearLayout.VERTICAL) topMargin = dp(FlymeAutoStyle.CONTROL_GAP_DP)
            })
        }
        actions.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (actions.orientation == LinearLayout.HORIZONTAL) {
                val tallest = maxOf(syncButton.height, startButton.height, saveButton.height)
                if (syncButton.minimumHeight < tallest) syncButton.minimumHeight = tallest
                if (startButton.minimumHeight < tallest) startButton.minimumHeight = tallest
                if (saveButton.minimumHeight < tallest) saveButton.minimumHeight = tallest
            }
        }
        parent.addView(actions, gap())
        status?.let { parent.addView(label(it), gap()) }
        if (saved == null && !initialReadStarted) loadInitialProfile()
    }

    private fun field(parent: LinearLayout, title: Int, value: String, secret: Boolean = false, save: (String) -> Unit) {
        var draft = value
        val row = DiPlayActivity.SettingButton(activity, palette.secondaryText, palette.accent).apply {
            FlymeAutoStyle.settingRow(this, palette)
            isEnabled = !busy
        }
        fun update() {
            val display = if (draft.isEmpty()) activity.getString(R.string.settings_value_empty)
                else if (secret) "••••••••" else draft
            row.text = activity.getString(title) + " · " + display
        }
        update()
        row.setOnClickListener {
            activeDialog = SettingsTextEditor.show(activity, activity.getString(title), draft, secret, onDismiss = ::restoreInitialProfile) {
                if (!closed && draft != it) {
                    draft = it
                    save(it)
                    editVersion++
                    edited = true
                    update()
                }
            }
        }
        parent.addView(row, gap())
    }

    private fun loadInitialProfile(forceSync: Boolean = false) {
        if (!forceSync) initialReadStarted = true
        val requestGeneration = ++activeSyncGeneration
        val startEditVersion = editVersion
        if (forceSync) {
            busy = true
            status = activity.getString(R.string.settings_hotspot_reading)
            redraw()
        }
        Thread({
            val systemProfile = if (forceSync) runCatching { control().read() }.getOrNull() else null
            val observed = runCatching { control().observe() }.getOrNull()
            val resolved = if (!forceSync && systemProfile == null && observed == null) {
                runCatching { CarHotspotProfiles.resolve(activity, manual = ::legacyProfile, log = ::log)?.profile }.getOrNull()
            } else null

            activity.runOnUiThread {
                if (forceSync) {
                    busy = false
                }
                if (closed || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (requestGeneration != activeSyncGeneration) return@runOnUiThread
                if (editVersion != startEditVersion) {
                    if (forceSync && activeDialog?.isShowing != true) redraw()
                    return@runOnUiThread
                }

                pendingStartEditVersion = startEditVersion
                pendingIsForceSync = forceSync
                if (systemProfile != null) {
                    pendingInitialProfile = systemProfile
                    pendingObservedHotspot = null
                } else if (observed != null) {
                    pendingObservedHotspot = observed
                    pendingInitialProfile = null
                } else if (resolved != null) {
                    pendingInitialProfile = resolved
                    pendingObservedHotspot = null
                } else if (!forceSync) {
                    pendingInitialProfile = CarHotspotProfiles.saved(activity)
                    pendingObservedHotspot = null
                } else {
                    pendingInitialProfile = null
                    pendingObservedHotspot = null
                    status = activity.getString(R.string.settings_hotspot_sync_failed)
                }

                if (activeDialog?.isShowing != true) {
                    restoreInitialProfile()
                }
            }
        }, "carplay-hotspot-read").start()
    }

    private fun restoreInitialProfile() {
        val isForce = pendingIsForceSync
        val reqEditVersion = pendingStartEditVersion
        val pendingProfile = pendingInitialProfile
        val pendingObserved = pendingObservedHotspot
        pendingInitialProfile = null
        pendingObservedHotspot = null
        pendingIsForceSync = false
        if (closed || activity.isFinishing || activity.isDestroyed) return
        if (isForce && editVersion != reqEditVersion) return
        if (!isForce && (edited || editVersion != reqEditVersion)) return

        if (isForce) {
            if (pendingProfile != null) {
                nameText = pendingProfile.ssid
                passwordText = pendingProfile.passphrase
                securitySelection = pendingProfile.security
                bandSelection = pendingProfile.band
                channelSelection = pendingProfile.channel
                status = activity.getString(R.string.settings_hotspot_sync_success_prompt_save)
                editVersion++
                edited = true
                redraw()
                return
            } else if (pendingObserved != null) {
                nameText = pendingObserved.ssid
                if (pendingObserved.passwordAvailable && !pendingObserved.rawPassword.isNullOrBlank()) {
                    passwordText = pendingObserved.rawPassword
                    status = activity.getString(R.string.settings_hotspot_sync_success_prompt_save)
                } else {
                    passwordText = ""
                    status = activity.getString(R.string.settings_hotspot_observed_fill_password)
                }
                securitySelection = pendingObserved.security
                bandSelection = pendingObserved.band
                channelSelection = pendingObserved.channel
                editVersion++
                edited = true
                redraw()
                return
            } else {
                redraw()
                return
            }
        }

        val saved = CarHotspotProfiles.saved(activity)
        if (saved != null) {
            nameText = saved.ssid
            passwordText = saved.passphrase
            securitySelection = saved.security
            bandSelection = saved.band
            channelSelection = saved.channel
            redraw()
            return
        }
        if (pendingProfile != null) {
            nameText = pendingProfile.ssid
            passwordText = pendingProfile.passphrase
            securitySelection = pendingProfile.security
            bandSelection = pendingProfile.band
            channelSelection = pendingProfile.channel
            redraw()
        } else if (pendingObserved != null) {
            nameText = pendingObserved.ssid
            passwordText = if (pendingObserved.passwordAvailable && !pendingObserved.rawPassword.isNullOrEmpty()) {
                pendingObserved.rawPassword
            } else {
                ""
            }
            if (!pendingObserved.passwordAvailable) {
                status = activity.getString(R.string.settings_hotspot_observed_fill_password)
            }
            securitySelection = pendingObserved.security
            bandSelection = pendingObserved.band
            channelSelection = pendingObserved.channel
            redraw()
        }
    }

    private fun <T> choice(parent: LinearLayout, title: Int, options: () -> List<Pair<T, String>>,
        selected: () -> T, save: (T) -> Unit): Button {
        val row = DiPlayActivity.SettingButton(activity, palette.secondaryText, palette.accent).apply {
            FlymeAutoStyle.settingRow(this, palette)
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
                        editVersion++
                        edited = true
                        update()
                    }
                }.setNegativeButton(R.string.cancel, null).show().also {
                    it.setOnDismissListener { restoreInitialProfile() }
                }
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
