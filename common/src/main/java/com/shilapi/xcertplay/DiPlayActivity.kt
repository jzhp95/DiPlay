// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.view.Window
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.ClusterSpeedometerOverlay
import com.shilapi.xcertplay.reportshare.ReportShareEndpoint
import com.shilapi.xcertplay.reportshare.ReportShareServer
import com.shilapi.xcertplay.reportshare.ReportShareQrCode
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.MtkDecoderTuning
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.transport.EvChargingConnectors
import com.shilapi.xcertplay.update.BackgroundUpdateScheduler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SettingsCategory {
    OVERVIEW, CONNECTION, DISPLAY, AUDIO, NAVIGATION, VEHICLE, DIAGNOSTICS, ADVANCED, ABOUT,
}

/** A settings card. Every entry needs one category in [SettingsInformationArchitecture]; see AGENTS.md. */
internal enum class SettingsSection {
    CARPLAY_CONTROLS,
    WHEEL_KEYS,
    CONNECTION_SETUP,
    CONNECTION_DETAILS,
    DIAGNOSTICS,
    AUTOMATIC_CONNECTION,
    USB_CONNECTION,
    DISPLAY_AND_PERFORMANCE,
    ADVANCED_MEDIA,
    AUDIO_ROUTING,
    NAVIGATION_WHEEL,
    LOCATION,
    CLUSTER_MAP,
    PERMISSIONS_AND_HELP,
}

internal object SettingsInformationArchitecture {
    val sectionsByCategory: Map<SettingsCategory, Set<SettingsSection>> = mapOf(
        SettingsCategory.OVERVIEW to emptySet(),
        SettingsCategory.ABOUT to emptySet(),
        SettingsCategory.CONNECTION to setOf(
            SettingsSection.CONNECTION_SETUP,
            SettingsSection.CONNECTION_DETAILS,
            SettingsSection.AUTOMATIC_CONNECTION,
            SettingsSection.USB_CONNECTION,
            SettingsSection.PERMISSIONS_AND_HELP,
        ),
        SettingsCategory.DISPLAY to setOf(SettingsSection.DISPLAY_AND_PERFORMANCE),
        SettingsCategory.AUDIO to setOf(SettingsSection.AUDIO_ROUTING),
        SettingsCategory.NAVIGATION to setOf(SettingsSection.LOCATION),
        SettingsCategory.VEHICLE to setOf(
            SettingsSection.CARPLAY_CONTROLS,
            SettingsSection.WHEEL_KEYS,
        ),
        SettingsCategory.DIAGNOSTICS to setOf(SettingsSection.DIAGNOSTICS),
        SettingsCategory.ADVANCED to setOf(
            SettingsSection.CLUSTER_MAP,
            SettingsSection.ADVANCED_MEDIA,
            SettingsSection.NAVIGATION_WHEEL,
        ),
    )
}

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity(), AppAppearanceOwner {
    private val handler = Handler(Looper.getMainLooper())
    private var appNight = true
    override val currentAppNight: Boolean get() = appNight
    private var palette = DiPlayPalette.DARK
    private var appearanceObserverRemoval: (() -> Unit)? = null
    private var appearanceUpdatesResumed = false
    private var appearanceRenderPending = false
    private var windowLearning: WindowKeyLearning? = null
    private val windowLearningPresses = WheelKeyPresses()
    private val endWindowLearning = Runnable { cancelKeyLearning() }
    private var page = "home"
    private var settingsCategory = SettingsCategory.OVERVIEW
    private var settingsSectionFilter: Set<SettingsSection>? = null
    private var hotspotSettingsUi: CarHotspotSettingsUi? = null
    private var hotspotConnectPreparing = false
    private var hotspotConnectGeneration = 0
    private var pendingCarHotspotSetup = false
    private var hotspotJoinControls: HotspotJoinControls? = null
    private var setupError: String? = null
    private var status: TextView? = null
    private var homeCards: CarPlayHomeLayout.Cards? = null
    private var homeConnectionHint: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var bluetoothSettingsVisited = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var usbPermissionOperation: UsbPermissionSetup.Operation? = null
    private var usbPermissionDialog: AlertDialog? = null
    internal var usbPermissionOperationFactory: (Context) -> UsbPermissionSetup.Operation = {
        UsbPermissionSetup.Operation(it.applicationContext)
    }
    internal var selectedMtkDecoderName: (String) -> String? = MtkDecoderTuning::selectedDecoderName
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var rootScroll: ScrollView? = null
    private var settingsRailScroll: ScrollView? = null
    private var reconnectBar: View? = null
    private var readinessCard: LinearLayout? = null
    private var searchIndexSink: MutableList<String>? = null
    private var settingsSearchBox: EditText? = null
    private var settingsSearchPopup: PopupWindow? = null
    private var settingsSearchIndex: List<SettingsSearchResult>? = null
    private var settingsSearchQuery = ""
    private var settingsSearchExpanded = false
    private var settingsSearchIndexing = false
    private var settingsSearchRestoreFocus = false
    private var renderedReadiness: SettingsReadiness? = null
    private var renderedPage: String? = null
    private var renderedSettingsCategory: SettingsCategory? = null
    private var pendingScrollY: Int? = null
    private var pendingRailScrollY: Int? = null
    private var pendingRailFocus = false
    @Volatile private var reportShareServer: ReportShareServer? = null
    @Volatile private var reportShareEndpoint: ReportShareEndpoint? = null
    private var carButtonCard: LinearLayout? = null
    private var hotspotStartupResult: CarHotspotTethering.Result? = null
    @Volatile private var startupHotspotCancelled = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val appearancePoll = object : Runnable {
        override fun run() {
            checkForAppearanceChange()
            if (appearanceUpdatesResumed) handler.postDelayed(this, APPEARANCE_POLL_MILLIS)
        }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val bluetoothAutoConnectPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (DiPlayPreferences.phoneAddress(this) == null) choosePhone()
        } else {
            DiPlayPreferences.saveConnectOnPhoneBluetooth(this, false)
            render()
            permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
        }
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) {
            applyLocationReporting(true)
        } else {
            render()
            permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
        }
    }
    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent(), ::cropIcon)
    private val iconDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument(), ::cropIcon)
    private fun cropIcon(uri: Uri?) {
        if (uri != null) iconCrop.launch(Intent(this, ImageCropActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    private val iconCrop = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        refreshCarButton()
        carButtonSaved()
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }
    // Android 9: storage access puts the report in the visible Downloads/DiPlay folder.
    // A denial still saves the report, only in DiPlay's own storage.
    private val reportStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        exportDiagnostics()
    }

    private var languagePreferenceAtCreate = AppLocale.SIMPLIFIED_CHINESE

    private var interfaceOverride: Configuration? = null
    private var interfaceSystemDensityDpi = 0
    private var interfaceRecreateRequested = false

    private fun enforceInterfaceSize(): Boolean {
        val language = AppLocale.enforce(this)
        if (language) android.util.Log.i("DiPlayUi", "app language re-applied")
        val override = interfaceOverride ?: return language
        val found = resources.displayMetrics.densityDpi
        if (!InterfaceSize.enforce(resources, override)) return language
        android.util.Log.i("DiPlayUi", "interface size re-applied: $found -> ${override.densityDpi} dpi")
        return true
    }

    override fun attachBaseContext(newBase: Context) {
        val base = AppLocale.wrap(newBase)
        super.attachBaseContext(base)
        interfaceSystemDensityDpi = base.resources.configuration.densityDpi
        interfaceOverride = InterfaceSize.attach(this, base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DetailedDebugLog.record("home", "created restored=${savedInstanceState != null} session=${CarPlayBackgroundSession.hasSession()}")
        cancelLegacyUpdateChecks()
        if (savedInstanceState == null && shouldEnterConnectedCarPlay(intent)) {
            openProjection(); finish(); return
        }
        // Keep the session running but expose its Open/Disconnect controls from the launcher.
        val returningToSession = savedInstanceState == null && isLauncherIntent(intent) &&
            CarPlayBackgroundSession.hasSession()
        enforceInterfaceSize()
        refreshAppearance()
        rememberLaunchAppearance()
        languagePreferenceAtCreate = AppLocale.preference(this)
        if (!CarPlayBackgroundSession.hasSession()) {
            com.shilapi.xcertplay.network.restoreWifiScanAfterInterruptedSession(applicationContext)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        applyWindowAppearance()
        setupError = runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        notificationTransport = savedInstanceState?.getBoolean("notification_transport", true) ?: true
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        settingsCategory = savedInstanceState?.getString("settings_category")
            ?.let { runCatching { SettingsCategory.valueOf(it) }.getOrNull() }
            ?: SettingsCategory.OVERVIEW
        page = if (returningToSession) "home" else savedInstanceState?.getString("page") ?: requestedPage(intent)
            ?: "home"
        render()
        handleWirelessRecovery()
        if (consumeBluetoothAutoConnectIntent(intent)) {
            if (initialLaunch) {
                initialLaunch = false
                startCarHotspotOnLaunch()
            }
            handler.post { connect(true) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") navigateBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    private fun shouldEnterConnectedCarPlay(intent: Intent): Boolean =
        isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession() &&
            DiPlayPreferences.enterCarPlayOnOpen(this)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (shouldEnterConnectedCarPlay(intent)) {
            page = "home"; render(); openProjection(); return
        }
        // Home remains the default unless the driver explicitly enables launcher auto-entry.
        page = requestedPage(intent) ?: "home"; render()
        handleWirelessRecovery()
        if (consumeBluetoothAutoConnectIntent(intent)) {
            if (initialLaunch) {
                initialLaunch = false
                startCarHotspotOnLaunch()
            }
            handler.post { connect(true) }
        }
    }
    private fun consumeBluetoothAutoConnectIntent(intent: Intent): Boolean {
        val requested = intent.getStringExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT)==PhoneBluetoothReceiver.launchToken
        intent.removeExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT)
        return requested && DiPlayPreferences.connectOnPhoneBluetooth(this) &&
            DiPlayPreferences.phoneAddress(this) != null && setupError == null &&
            !CarPlayBackgroundSession.hasSession()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putString("settings_category", settingsCategory.name)
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        outState.putBoolean("notification_transport", notificationTransport)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (updateInterfaceSize(newConfig)) return
        refreshAppearance()
        render()
    }

    /** True while a density change is recreating this activity. */
    private fun updateInterfaceSize(configuration: Configuration): Boolean {
        if (interfaceRecreateRequested) return true
        // The activity handles real system density changes too. Its callback contains our installed
        // density, while application resources retain the current unscaled system density.
        applicationContext.resources.configuration.densityDpi.takeIf { it > 0 }?.let {
            interfaceSystemDensityDpi = it
        }
        val next = InterfaceSize.configurationChange(configuration, interfaceSystemDensityDpi,
            InterfaceSize.preference(this))
        if (InterfaceSize.needsRecreate(interfaceOverride, next)) {
            interfaceRecreateRequested = true
            recreate()
            return true
        }
        interfaceOverride = next
        return false
    }
    private fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            android.widget.Toast.makeText(this, R.string.center_map_no_permission_screen, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        // Results from the image picker and crop screens arrive before onResume.
        enforceInterfaceSize()
        CenterMapOverlay.onDiPlayScreenShown()
    }

    override fun onStop() {
        cancelUsbPermissionSetup()
        startupHotspotCancelled = true
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    override fun onResume() {
        super.onResume()
        WheelKeyService.restoreIfNeeded(this)
        applyFullscreenMode()
        // Returning from another activity can bring the head unit's own density back.
        if (enforceInterfaceSize()) render()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        // Back with the hotspot access now allowed: the start-up attempt that lacked it runs again.
        if (hotspotStartupResult == CarHotspotTethering.Result.PERMISSION_REQUIRED && CarHotspotTethering.permitted(this)) {
            hotspotStartupResult = null
            startupHotspotCancelled = false
            startCarHotspotOnLaunch()
        }
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch &&
            (page == "home" || page == "settings")) render()
        startAppearanceUpdates()
        if (initialLaunch) {
            initialLaunch = false
            startCarHotspotOnLaunch()
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && requestedPage(intent) == null) {
                handler.post { connect(DiPlayPreferences.autoConnectWireless(this)) }
            }
        }
    }
    override fun onPause() {
        appearanceUpdatesResumed = false
        appearanceObserverRemoval?.invoke()
        appearanceObserverRemoval = null
        handler.removeCallbacks(appearancePoll)
        cancelKeyLearning()
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        hotspotSettingsUi?.close()
        settingsSearchPopup?.dismiss()
        appearanceObserverRemoval?.invoke()
        appearanceObserverRemoval = null
        handler.removeCallbacks(appearancePoll)
        hotspotJoinControls?.close()
        cancelUsbPermissionSetup()
        cancelKeyLearning()
        stopReportShareServer()
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyFullscreenMode()
        if (updateInterfaceSize(newConfig)) return
        render()
    }

    private val isCompactLayout: Boolean
        // Window size only: a multi-window task can fill the whole screen, and in multi-window the
        // configuration already reports the window's own size.
        get() = resources.configuration.screenWidthDp < 550 ||
            resources.configuration.screenHeightDp < 450

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenMode()
        // Some head units put their own density back without any other callback.
        if (hasFocus && enforceInterfaceSize()) render()
    }

    private fun applyFullscreenMode() {
        val multiWindow = isInMultiWindowMode
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.systemBarIconsAreDark
            isAppearanceLightNavigationBars = palette.systemBarIconsAreDark
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (AirPlayPersistence.loadHideTopBar(this@DiPlayActivity) && !multiWindow) {
                hide(WindowInsetsCompat.Type.statusBars())
            } else {
                show(WindowInsetsCompat.Type.statusBars())
            }
            if (AirPlayPersistence.loadHideBottomBar(this@DiPlayActivity) && !multiWindow) {
                hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                show(WindowInsetsCompat.Type.navigationBars())
            }
        }
        ViewCompat.requestApplyInsets(window.decorView)
    }

    private val isNaturallyExpandedSettingsLayout: Boolean
        get() = resources.configuration.let {
            SettingsLayoutPolicy.isExpanded(it.screenWidthDp, it.screenHeightDp, it.fontScale)
        }

    private val isExpandedSettingsLayout: Boolean
        get() = SettingsLayoutPreferences.isActive(this) || isNaturallyExpandedSettingsLayout

    private data class FocusSnapshot(
        val tag: Any?,
        val contentDescription: String?,
        val label: String?,
    )

    private fun focusLabel(view: View): String? = when (view) {
        is TextView -> view.text?.toString()
        is ViewGroup -> descendants(view).filterIsInstance<TextView>()
            .firstOrNull { it.text.isNotEmpty() }?.text?.toString()
        else -> null
    }

    private fun render() = render(appearanceOnly = false)

    private fun render(appearanceOnly: Boolean, preferredFocusTag: Any? = null) {
        // Older saved state or launch intents may still point at the removed guide.
        if (page == "setup") page = "home"
        // Restore old intents/saved state directly into the flattened connection category.
        if (page == "connection") {
            page = "settings"
            settingsCategory = SettingsCategory.CONNECTION
        }
        enforceInterfaceSize()
        refreshAppearance()
        applyWindowAppearance()
        // A pending assignment belongs to the widgets being replaced, never to another page.
        if (!appearanceOnly) cancelKeyLearning()
        // The report server lives only while its page is shown, however the driver left it.
        if (page != "report-share" && reportShareServer != null) stopReportShareServer()
        val focusSnapshot = if (appearanceOnly) {
            currentFocus?.let { focused ->
                FocusSnapshot(
                    tag = preferredFocusTag ?: focused.tag,
                    contentDescription = focused.contentDescription?.toString(),
                    label = focusLabel(focused),
                )
            } ?: preferredFocusTag?.let { FocusSnapshot(it, null, null) }
        } else null
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val sameDestination = renderedPage == page &&
            (page != "settings" || renderedSettingsCategory == settingsCategory)
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf { sameDestination }
        // The rail is one destination even when its selected category changes.
        val keepRailPosition = renderedPage == "settings" && page == "settings"
        val previousRailScrollY = (pendingRailScrollY ?: settingsRailScroll?.scrollY)
            ?.takeIf { keepRailPosition }
        val restoreRailFocus = keepRailPosition &&
            (pendingRailFocus || settingsRailScroll?.hasFocus() == true)
        settingsRailScroll = null
        // The results hang off the old header; the new header reopens them if the search continues.
        settingsSearchPopup?.dismiss()
        settingsSearchPopup = null
        settingsSearchRestoreFocus = settingsSearchRestoreFocus ||
            (settingsSearchExpanded && settingsSearchBox?.hasFocus() == true)
        settingsSearchBox = null
        if (page != "settings") clearSettingsSearchState()
        status = null; connectButton = null; disconnectButton = null; lastRunning = null; carButtonCard = null
        homeCards = null
        homeConnectionHint = null
        reconnectBar = null
        readinessCard = null
        exportButton = null
        val compact = isCompactLayout && when (page) {
            "settings" -> !SettingsLayoutPreferences.isActive(this)
            else -> true
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        rootScroll = scroll
        val content = column()
        scroll.addView(content)
        val header = when (page) {
            "settings" -> settingsHeader(compact)
            "home" -> homeHeader()
            else -> standardHeader(compact)
        }
        if (page == "home") scroll.background = homeLayout().background()
        val root: View = if (page == "settings" && isExpandedSettingsLayout) {
            content.setPadding(0, 0, 0, dp(32))
            settingsCategoryContent(content)
            expandedSettingsShell(header, scroll)
        } else {
            if (compact) content.setPadding(dp(12), dp(10), dp(12), dp(12))
            else content.setPadding(dp(32), dp(24), dp(32), dp(32))
            content.addView(header)
            content.addView(space(when {
                compact && (page == "settings" || page == "home") -> SETTINGS_BLOCK_GAP_DP
                compact -> 8
                else -> 24
            }))
            if (page == "settings") content.addView(reconnectBar(), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(SETTINGS_BLOCK_GAP_DP)
            })
            when (page) {
                "settings" -> settingsCategoryContent(content)
                "report-share" -> reportShare(content)
                else -> home(content)
            }
            if (page == "home") homeLayout().screen(scroll, content, checkNotNull(homeCards)) else scroll
        }
        // Keep the background full screen, but all controls inside the camera and
        // visible system/keyboard bounds. Insets are physical pixels, not scaled dp.
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(baseLeft + safe.left, baseTop + safe.top,
                baseRight + safe.right, baseBottom + safe.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        renderedPage = page
        renderedSettingsCategory = settingsCategory.takeIf { page == "settings" }
        refreshStatus()
        focusSnapshot?.let { snapshot ->
            root.doOnLayout {
                descendants(root).firstOrNull { candidate ->
                    candidate.isFocusable && when {
                        snapshot.tag != null -> candidate.tag == snapshot.tag
                        snapshot.contentDescription != null ->
                            candidate.contentDescription?.toString() == snapshot.contentDescription
                        snapshot.label != null -> focusLabel(candidate) == snapshot.label
                        else -> false
                    }
                }?.requestFocus()
            }
        }
        pendingScrollY = previousScrollY
        // A stopped window still dispatches pre-draw but skips layout, so wait for a real layout;
        // the listener stays on this view and goes away with it.
        previousScrollY?.let { y ->
            scroll.doOnLayout {
                if (rootScroll === scroll) {
                    scroll.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
        val rail = settingsRailScroll
        pendingRailScrollY = previousRailScrollY.takeIf { rail != null }
        pendingRailFocus = restoreRailFocus && rail != null
        rail?.doOnLayout {
            if (settingsRailScroll !== rail) return@doOnLayout
            previousRailScrollY?.let { rail.scrollTo(0, it) }
            val destinations = (rail.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
            val selected = (0 until destinations.childCount)
                .map(destinations::getChildAt).firstOrNull { it.isSelected }
            if (selected != null) {
                if (restoreRailFocus) selected.requestFocus()
                selected.requestRectangleOnScreen(android.graphics.Rect(0, 0, selected.width, selected.height), true)
            }
            pendingRailScrollY = null
            pendingRailFocus = false
        }
    }

    private fun standardHeader(compact: Boolean): LinearLayout = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        val logoSize = dp(if (compact) 30 else 36)
        addView(ImageView(this@DiPlayActivity).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay)
        }, LinearLayout.LayoutParams(logoSize, logoSize))
        addView(label(getString(R.string.diplay), if (compact) 20 else 26, TEXT, true,
            centreGlyphs = resources.configuration.locales[0].language != "zh").apply {
            setPadding(if (compact) dp(8) else dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, if (compact) dp(44) else dp(52), 1f))
        addView(headerButton(if (page == "home") getString(R.string.car_home) else getString(R.string.back),
            if (page == "home") R.drawable.ic_dp_car else R.drawable.ic_dp_back, compact) {
            if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            else navigateBack()
        }, LinearLayout.LayoutParams(-2, if (compact) dp(44) else dp(52)))
    }

    private fun navigateBack() {
        // Back closes an open search first, like a system search bar.
        if (page == "settings" && settingsSearchExpanded) {
            closeSettingsSearch()
            return
        }
        when {
            page == "report-share" -> {
                stopReportShareServer()
                page = "settings"
                settingsCategory = SettingsCategory.DIAGNOSTICS
            }
            page == "settings" && !isExpandedSettingsLayout && settingsCategory != SettingsCategory.OVERVIEW -> {
                settingsCategory = SettingsCategory.OVERVIEW
            }
            else -> {
                page = "home"
            }
        }
        render()
    }

    private fun openConnectionSettings() {
        page = "settings"
        settingsCategory = SettingsCategory.CONNECTION
        render()
    }

    private fun settingsHeader(compact: Boolean): LinearLayout = row().apply {
        val controlHeight = dp(if (compact) 44 else 52)
        gravity = Gravity.CENTER_VERTICAL
        addView(headerButton(getString(R.string.back), R.drawable.ic_dp_back, compact, ::navigateBack),
            LinearLayout.LayoutParams(-2, if (compact) dp(44) else dp(52)))
        val settingsTitle = label(getString(R.string.settings), if (compact) 20 else 26, TEXT, true,
            centreGlyphs = true).apply {
            setPadding(dp(12), 0, dp(12), 0)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(settingsTitle, LinearLayout.LayoutParams(0, controlHeight, 1f))
    }

    private fun homeLayout() = CarPlayHomeLayout(this, palette,
        makeButton = { title, primary, click -> button(title, primary, click) },
        makeLabel = { title, size, color, bold -> label(title, size, color, bold) })

    private fun homeHeader(): LinearLayout {
        val header = homeLayout().header(null,
            choose = { choosePhone() },
            disconnect = {
                disconnectButton?.isEnabled = false
                CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
            },
            settings = { page = "settings"; render() },
            carHome = { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) },
            wheelTest = { startActivity(Intent(this, WheelTestActivity::class.java)) },
            hotspot = { CarHotspotSettingsUi.openSystemSettings(this) })
        disconnectButton = header.disconnect
        return header.root
    }

    private fun home(content: LinearLayout) {
        val compact = isCompactLayout
        fun homeButton(title: String, primary: Boolean, click: () -> Unit) = button(title, primary, click)
        val connectionHint = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_hint)
            WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_hint_manual)
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
            else -> getString(R.string.hotspot_hint_p2p)
        }
        val cards = homeLayout().cards(
            connect = {
                if (HomeCardTransport.wireless(CarPlayBackgroundSession.hasSession(),
                        AirPlayPersistence.loadWirelessEnabled(this))) openProjection()
                else connect(true)
            },
            usbConnect = {
                if (HomeCardTransport.wired(CarPlayBackgroundSession.hasSession(),
                        AirPlayPersistence.loadWirelessEnabled(this))) openProjection()
                else connect(false)
            },
            hint = connectionHint)
        // refreshStatus binds the existing session state to its matching connection card.
        homeCards = cards
        status = cards.status
        connectButton = cards.connect
        homeConnectionHint = cards.hint
        val startupProblem = hotspotStartupResult?.takeIf {
            it != CarHotspotTethering.Result.READY && it != CarHotspotTethering.Result.CANCELLED &&
                CarHotspotSettings.shouldEnable(this, true, AirPlayPersistence.loadWirelessHotspotMode(this)) &&
                com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) != true
        }
        if (startupProblem != null) {
            val allowOnSystemScreen = startupProblem == CarHotspotTethering.Result.PERMISSION_REQUIRED &&
                CarHotspotTethering.appStartSupported()
            cards.notice.text = if (allowOnSystemScreen) getString(R.string.settings_write_settings_missing)
                else hotspotResultText(startupProblem)
            cards.notice.visibility = View.VISIBLE
            if (!compact && allowOnSystemScreen) cards.footer.addView(
                homeButton(getString(R.string.settings_write_settings_allow), false) { openWriteSettingsAccess() },
                matchButton(CONTROL_GAP_DP, 56))
        } else if (carHotspotOff()) {
            // No name is saved before Connection setup, and the sentence that quotes it would show “”.
            val name = AirPlayPersistence.loadManualHotspotSsid(this)
            val off = if (name.isBlank()) getString(R.string.hotspot_details_off) else getString(R.string.msg_car_hotspot_off, name)
            cards.notice.text = off
            cards.notice.visibility = View.VISIBLE
        }
        setupError?.let { cards.root.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
        homeLayout().addCardsTo(content, cards)
    }

    // Only the category scrolls, so the rail keeps showing where the driver is.
    private fun expandedSettingsShell(header: View, categoryScroll: ScrollView): LinearLayout = column().apply {
        setBackgroundColor(BG)
        setPadding(dp(32), dp(24), dp(32), 0)
        addView(header)
        addView(space(24))
        addView(reconnectBar(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        val split = row().apply { gravity = Gravity.TOP }
        // The rail scrolls on its own only when the window is too short for every destination.
        split.addView(ScrollView(this@DiPlayActivity).apply {
            addView(column().apply {
                setPadding(0, 0, 0, dp(12))
                addView(settingsRail())
            })
            settingsRailScroll = this
        },
            LinearLayout.LayoutParams(dp(SettingsLayoutPolicy.railWidthDp(resources.configuration.fontScale)), -2))
        split.addView(space(24), LinearLayout.LayoutParams(dp(24), 1))
        split.addView(categoryScroll, LinearLayout.LayoutParams(0, -1, 1f))
        addView(split, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun settingsRail(): LinearLayout = column().apply {
        SettingsCategory.entries.forEach { category ->
            addView(settingsRailDestination(category), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = if (category == SettingsCategory.entries.last()) 0 else dp(8)
            })
        }
    }

    private fun settingsRailDestination(category: SettingsCategory): View {
        val selected = settingsCategory == category
        val title = settingsCategoryTitle(category)
        return row().apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            isSelected = selected
            minimumHeight = dp(FlymeAutoStyle.NAVIGATION_HEIGHT_DP)
            val navRadius = FlymeAutoStyle.radius(this@DiPlayActivity, minimumHeight)
            foreground = focusRing((navRadius / resources.displayMetrics.density).roundToInt())
            contentDescription = getString(R.string.settings_open_category, title)
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(RIPPLE),
                GradientDrawable().apply {
                    setColor(if (selected) RAIL_SELECTED else Color.TRANSPARENT)
                    cornerRadius = navRadius
                },
                null,
            )
            setPadding(dp(8), 0, dp(12), 0)
            addView(View(this@DiPlayActivity).apply {
                background = GradientDrawable().apply {
                    setColor(if (selected) ACCENT else Color.TRANSPARENT)
                    cornerRadius = dp(2).toFloat()
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(4), dp(34)).apply { marginEnd = dp(10) })
            addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(settingsCategoryIcon(category))
                imageTintList = ColorStateList.valueOf(if (selected) ACCENT else TEXT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
            addView(label(title, FlymeAutoStyle.NAVIGATION_TEXT_SP.toInt(), if (selected) ACCENT else TEXT, true,
                centreGlyphs = usesChineseGlyphAlignment(resources.configuration.locales[0].language, title)).apply {
                    FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.NAVIGATION, compact = false)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener {
                openSettingsCategory(category)
            }
        }
    }

    private fun settingsCategoryIcon(category: SettingsCategory): Int = when (category) {
        SettingsCategory.OVERVIEW -> R.drawable.ic_dp_overview
        SettingsCategory.CONNECTION -> R.drawable.ic_dp_connection
        SettingsCategory.DISPLAY -> R.drawable.ic_dp_display
        SettingsCategory.AUDIO -> R.drawable.ic_dp_audio
        SettingsCategory.NAVIGATION -> R.drawable.ic_dp_navigation
        SettingsCategory.VEHICLE -> R.drawable.ic_dp_car
        SettingsCategory.DIAGNOSTICS -> R.drawable.ic_dp_diagnostics
        SettingsCategory.ADVANCED -> R.drawable.ic_dp_advanced
        SettingsCategory.ABOUT -> R.drawable.ic_dp_about
    }

    private fun settingsCategoryTitle(category: SettingsCategory): String = getString(when (category) {
        SettingsCategory.OVERVIEW -> R.string.settings_overview
        SettingsCategory.CONNECTION -> R.string.connection
        SettingsCategory.DISPLAY -> R.string.settings_display
        SettingsCategory.AUDIO -> R.string.audio
        SettingsCategory.NAVIGATION -> R.string.settings_navigation
        SettingsCategory.VEHICLE -> R.string.settings_vehicle
        SettingsCategory.DIAGNOSTICS -> R.string.diagnostics
        SettingsCategory.ADVANCED -> R.string.settings_advanced
        SettingsCategory.ABOUT -> R.string.about
    })

    private fun settingsCategoryContent(content: LinearLayout) {
        when (settingsCategory) {
            SettingsCategory.OVERVIEW -> settingsOverview(content)
            SettingsCategory.CONNECTION -> connectionSettings(content)
            SettingsCategory.DISPLAY -> displaySettings(content)
            SettingsCategory.AUDIO -> audioSettings(content)
            SettingsCategory.NAVIGATION -> navigationSettings(content)
            SettingsCategory.VEHICLE -> vehicleSettings(content)
            SettingsCategory.DIAGNOSTICS -> diagnosticsSettings(content)
            SettingsCategory.ADVANCED -> advancedSettings(content)
            SettingsCategory.ABOUT -> about(content)
        }
    }

    private fun settingsOverview(content: LinearLayout) {
        val readiness = card().apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        readinessCard = readiness
        renderedReadiness = null
        refreshReadiness()
        content.addView(readiness, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        val destinations = listOf(
            SettingsCategory.CONNECTION to R.string.settings_connection_summary,
            SettingsCategory.DISPLAY to R.string.settings_display_summary,
            SettingsCategory.AUDIO to R.string.settings_audio_summary,
            SettingsCategory.NAVIGATION to R.string.settings_navigation_summary,
            SettingsCategory.VEHICLE to R.string.settings_vehicle_summary,
            SettingsCategory.ABOUT to R.string.about_diplay,
            SettingsCategory.DIAGNOSTICS to R.string.settings_diagnostics_summary,
            SettingsCategory.ADVANCED to R.string.settings_advanced_subtitle,
        )
        // The expanded layout already exposes every category in its persistent navigation rail.
        if (!isExpandedSettingsLayout) {
            destinations.forEach { item ->
                content.addView(settingsSummaryCard(item.first, getString(item.second)),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
            }
        }
        content.addView(settingsSectionHeading(R.string.settings_quick_settings))
        content.addView(quickSettingsCard(), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(SETTINGS_BLOCK_GAP_DP)
        })

        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.OVERVIEW))
    }

    private fun currentReadiness() = SettingsReadiness.of(
        setupError = setupError != null,
        active = CarPlayBackgroundSession.active,
        running = CarPlayBackgroundSession.hasSession(),
        wireless = AirPlayPersistence.loadWirelessEnabled(this),
        phoneChosen = DiPlayPreferences.phoneAddress(this) != null,
        hotspotSetupNeeded = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            carHotspotDetailsMissing(),
    )

    private fun refreshReadiness() {
        val card = readinessCard ?: return
        val state = currentReadiness()
        if (state == renderedReadiness) return
        renderedReadiness = state
        card.removeAllViews()
        val title = getString(when (state) {
            SettingsReadiness.SETUP_ERROR, SettingsReadiness.HOTSPOT_SETUP -> R.string.setup_needs_attention
            SettingsReadiness.CONNECTED -> R.string.carplay_connected
            SettingsReadiness.CONNECTING -> R.string.connecting_to_your_iphone
            SettingsReadiness.CHOOSE_IPHONE -> R.string.settings_choose_iphone_title
            SettingsReadiness.READY_WIRELESS, SettingsReadiness.READY_USB -> R.string.ready
        })
        val detail = when (state) {
            SettingsReadiness.SETUP_ERROR -> setupError.orEmpty()
            SettingsReadiness.HOTSPOT_SETUP -> getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings)
            SettingsReadiness.CONNECTED -> getString(R.string.settings_ready_connected)
            SettingsReadiness.CONNECTING -> getString(R.string.settings_connecting_description)
            SettingsReadiness.CHOOSE_IPHONE -> getString(R.string.settings_choose_iphone_description)
            SettingsReadiness.READY_WIRELESS -> getString(R.string.settings_ready_wireless, DiPlayPreferences.phoneName(this))
            SettingsReadiness.READY_USB -> getString(R.string.settings_ready_usb)
        }
        val tint = when {
            state.needsAction -> WARNING
            state == SettingsReadiness.CONNECTED -> READY
            else -> TEXT
        }
        card.addView(label(title, 22, tint, true))
        card.addView(label(detail, 15, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        when (state) {
            SettingsReadiness.CHOOSE_IPHONE -> card.addView(button(getString(R.string.settings_choose_iphone_action), true) {
                choosePhone()
            }, matchButton(12, 56))
            SettingsReadiness.SETUP_ERROR, SettingsReadiness.HOTSPOT_SETUP -> card.addView(button(getString(R.string.open_connection_setup), true) {
                openConnectionSettings()
            }, matchButton(12, 56))
            else -> Unit
        }
    }

    private fun quickSettingsCard(): LinearLayout = card().also(::buildQuickSettings).also(::normalizeSpacing)

    private fun buildQuickSettings(card: LinearLayout): Unit = card.run {
        toggle(this, getString(R.string.connect_when_diplay_opens),
            getString(R.string.default_connection_description),
            DiPlayPreferences.autoConnect(this@DiPlayActivity)) { DiPlayPreferences.saveAutoConnect(this@DiPlayActivity, it) }
        toggle(this, getString(R.string.full_screen), getString(R.string.settings_full_screen_description),
            AirPlayPersistence.loadHideTopBar(this@DiPlayActivity) && AirPlayPersistence.loadHideBottomBar(this@DiPlayActivity)) { enabled ->
            AirPlayPersistence.saveHideTopBar(this@DiPlayActivity, enabled)
            AirPlayPersistence.saveHideBottomBar(this@DiPlayActivity, enabled)
            applyFullscreenMode()
        }
        toggle(this, getString(R.string.report_location_to_iphone),
            "${getString(R.string.location_reporting_reconnects)} ${getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th)}",
            AirPlayPersistence.loadLocationReportingEnabled(this@DiPlayActivity), save = ::onLocationReportingChanged)
        Unit
    }

    private fun openSettingsCategory(category: SettingsCategory) {
        setSearchKeyboard(settingsSearchBox, false)
        clearSettingsSearchState()
        settingsCategory = category
        render()
    }

    private fun settingsSectionHeading(title: Int) = label(getString(title), 22, TEXT, true).apply {
        setPadding(0, 0, 0, dp(10))
    }

    private fun settingsSummaryCard(category: SettingsCategory, summary: String): LinearLayout = card().apply {
        val warning = category == SettingsCategory.ADVANCED
        setPadding(dp(24), dp(10), dp(24), dp(10))
        isClickable = true
        isFocusable = true
        foreground = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null),
            focusRing((layoutCornerRadius() * 1.35f / resources.displayMetrics.density).roundToInt())))
        contentDescription = getString(R.string.settings_open_category, settingsCategoryTitle(category))
        addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(settingsCategoryIcon(category))
                imageTintList = ColorStateList.valueOf(if (warning) WARNING else ACCENT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(20) })
            addView(column().apply {
                addView(label(settingsCategoryTitle(category), 17, if (warning) WARNING else TEXT, true))
                addView(label(summary, 13, MUTED).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        setOnClickListener { openSettingsCategory(category) }
    }

    private fun connectionSettings(content: LinearLayout) {
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.CONNECTION))
    }

    private fun displaySettings(content: LinearLayout) {
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.DISPLAY))
    }

    private fun audioSettings(content: LinearLayout) {
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.AUDIO))
    }

    private fun navigationSettings(content: LinearLayout) {
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.NAVIGATION))
    }

    private fun vehicleSettings(content: LinearLayout) {
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.VEHICLE))
        section(content, getString(R.string.settings_advanced), R.drawable.ic_dp_advanced) { card ->
            card.addView(label(getString(R.string.settings_vehicle_description), 16, MUTED))
            card.addView(button(getString(R.string.settings_open_advanced), false) {
                settingsCategory = SettingsCategory.ADVANCED
                render()
            }, matchButton(14, 56))
        }
    }

    private fun diagnosticsSettings(content: LinearLayout) {
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.DIAGNOSTICS))
    }

    internal data class SettingsSearchResult(val title: String, val category: SettingsCategory)

    // Renders every category into a detached view and records what the builders label.
    // render() afterwards replaces every widget reference those detached builds assigned.
    // ponytail: indexes the default state only; controls behind an expander (vehicle data) are not found.
    private fun buildSettingsSearchIndex(): List<SettingsSearchResult> {
        val selected = settingsCategory
        // Category names first, so a card that only links to a category cannot claim its name.
        val index = SettingsCategory.entries.associateByTo(linkedMapOf(), ::settingsCategoryTitle)
        try {
            for (category in SettingsCategory.entries.filter { it != SettingsCategory.OVERVIEW } + SettingsCategory.OVERVIEW) {
                settingsCategory = category
                val titles = mutableListOf(settingsCategoryTitle(category))
                searchIndexSink = titles
                settingsCategoryContent(column())
                titles.forEach { index.putIfAbsent(it, category) }
            }
        } finally {
            searchIndexSink = null
            settingsCategory = selected
        }
        render()
        return index.map { SettingsSearchResult(it.key, it.value) }
    }

    private fun searchSettings(index: List<SettingsSearchResult>, query: String): List<SettingsSearchResult> {
        val words = query.trim().lowercase(Locale.getDefault()).split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return emptyList()
        return index.filter { result ->
            val haystack = "${result.title} ${settingsCategoryTitle(result.category)}".lowercase(Locale.getDefault())
            words.all(haystack::contains)
        }
    }

    private fun openSearchResult(result: SettingsSearchResult) {
        setSearchKeyboard(settingsSearchBox, false)
        clearSettingsSearchState()
        openSettingsCategory(result.category)
        val scroll = rootScroll ?: return
        val match = descendants(scroll).filterIsInstance<TextView>().firstOrNull {
            it.text.toString() == result.title || it.text.startsWith(result.title + VALUE_SEPARATOR)
        } ?: return
        val target = generateSequence<View>(match) { it.parent as? View }.firstOrNull { it.isClickable } ?: match
        scroll.doOnLayout {
            var top = 0
            var view: View? = target
            while (view != null && view !== scroll) { top += view.top; view = view.parent as? View }
            scroll.smoothScrollTo(0, (top - dp(24)).coerceAtLeast(0))
            target.requestFocus()
            target.isPressed = true
            handler.postDelayed({ target.isPressed = false }, SEARCH_HIGHLIGHT_MILLIS)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    /**
     * The settings header search box; matches drop down under it as the driver types.
     * The index is built before expanding the field, so typing keeps the same editor
     * and keyboard input connection.
     */
    private fun settingsSearchField(compact: Boolean): EditText = SettingsSearchEditText(this).apply {
        settingsSearchBox = this
        setSingleLine()
        hint = getString(R.string.settings_search_hint)
        contentDescription = getString(R.string.settings_search)
        textSize = if (compact) 16f else 18f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        // The editor scrolls long text sideways and its drawing canvas scrolls with it, but Android
        // draws the background unscrolled. The icon and focus ring live there, so they stay put
        // without compensating for scroll or for the text's vertical centring.
        val iconSize = headerIconSize(compact)
        val icon = getDrawable(R.drawable.ic_dp_search)?.mutate()?.apply { setTint(MUTED) }
        background = android.graphics.drawable.LayerDrawable(
            listOfNotNull(buttonShape(BUTTON, BORDER), icon, buttonFocusRing()).toTypedArray(),
        ).apply {
            if (icon != null) {
                setLayerGravity(1, Gravity.START or Gravity.CENTER_VERTICAL)
                setLayerSize(1, iconSize, iconSize)
                setLayerInsetRelative(1, dp(16), 0, 0, 0)
            }
        }
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
        setPaddingRelative(dp(16) + if (icon != null) iconSize + dp(10) else 0, 0, dp(16), 0)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        // Landscape head units would otherwise cover the results with a full-screen editor.
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        // Touch focus waits for a tap, so opening settings never focuses the box or raises the keyboard.
        isFocusableInTouchMode = settingsSearchRestoreFocus
        setText(settingsSearchQuery)
        setSelection(text.length)
        setOnClickListener {
            if (!isFocusableInTouchMode) {
                isFocusableInTouchMode = true
                requestFocus()
                setSearchKeyboard(this, true)
            }
            showSettingsSearchResults()
        }
        setOnFocusChangeListener { _, focused -> if (focused) showSettingsSearchResults() }
        setOnEditorActionListener { _, actionId, event ->
            val search = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (!search) return@setOnEditorActionListener false
            if (event == null || event.action == KeyEvent.ACTION_DOWN) settingsSearchResults().firstOrNull()?.let(::openSearchResult)
            true
        }
        doAfterTextChanged { onSettingsSearchChanged(this) }
        // The keyboard resizes the window, not this field, so follow window layouts. A listener added
        // here would join the window's observer and outlive every rebuilt field; register it only
        // while this field is attached.
        val followWindow = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (settingsSearchBox === this && settingsSearchPopup?.isShowing == true) {
                positionSettingsSearchResults(this, settingsSearchPopup!!)
            }
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = view.viewTreeObserver.addOnGlobalLayoutListener(followWindow)
            override fun onViewDetachedFromWindow(view: View) = view.viewTreeObserver.removeOnGlobalLayoutListener(followWindow)
        })
        if (settingsSearchRestoreFocus) {
            settingsSearchRestoreFocus = false
            post {
                if (settingsSearchBox !== this) return@post
                requestFocus()
                setSearchKeyboard(this, true)
                showSettingsSearchResults()
            }
        }
    }

    private fun onSettingsSearchChanged(box: EditText) {
        if (box !== settingsSearchBox) return
        settingsSearchQuery = box.text.toString()
        if (settingsSearchQuery.isEmpty()) {
            // Keep this search's index when the query is cleared. Closing search invalidates it.
            settingsSearchPopup?.dismiss()
            return
        }
        if (settingsSearchIndex != null) {
            showSettingsSearchResults()
            return
        }
        if (settingsSearchIndexing) return
        settingsSearchIndexing = true
        // Not from inside the text watcher: indexing replaces this box.
        handler.post {
            settingsSearchIndexing = false
            if (page != "settings" || settingsSearchQuery.isEmpty() || settingsSearchIndex != null) return@post
            settingsSearchRestoreFocus = true
            settingsSearchIndex = buildSettingsSearchIndex()
        }
    }

    private fun settingsSearchResults(): List<SettingsSearchResult> =
        settingsSearchIndex?.let { searchSettings(it, settingsSearchQuery) }.orEmpty()

    private fun showSettingsSearchResults() {
        val box = settingsSearchBox ?: return
        if (settingsSearchIndex == null || settingsSearchQuery.isBlank() || !box.isAttachedToWindow) {
            settingsSearchPopup?.dismiss()
            return
        }
        val results = settingsSearchResults()
        val list = column().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        if (results.isEmpty()) {
            list.addView(label(getString(R.string.settings_search_empty), 15, MUTED).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
        }
        results.forEach { list.addView(settingsSearchRow(it)) }
        val existing = settingsSearchPopup?.takeIf { it.isShowing }
        val scroll = (existing?.contentView as? ScrollView) ?: ScrollView(this)
        scroll.removeAllViews()
        scroll.addView(list)
        if (existing != null) {
            positionSettingsSearchResults(box, existing)
            return
        }
        settingsSearchPopup = PopupWindow(scroll, box.width, 0, false).apply {
            setBackgroundDrawable(rounded(SURFACE, BORDER))
            elevation = dp(8).toFloat()
            // Tapping elsewhere closes the list and keeps the query; tapping the box reopens it.
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            // The list is placed in screen coordinates. Android 10 made this call public; before it the
            // same switch is setLayoutInScreenEnabled, and calling the new name stops the app.
            if (Build.VERSION.SDK_INT >= 29) setIsLaidOutInScreen(true)
            else runCatching {
                PopupWindow::class.java.getMethod("setLayoutInScreenEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(this, true)
            }
            positionSettingsSearchResults(box, this)
        }
    }

    private fun positionSettingsSearchResults(box: EditText, popup: PopupWindow) {
        val visible = android.graphics.Rect()
        box.getWindowVisibleDisplayFrame(visible)
        val location = IntArray(2)
        box.getLocationOnScreen(location)
        val top = location[1] + box.height + dp(SETTINGS_SEARCH_RESULTS_GAP_DP)
        val width = minOf(box.width, visible.width())
        val maxHeight = minOf(
            visible.bottom - top,
            dp(if (resources.configuration.screenHeightDp < 600)
                SETTINGS_SEARCH_RESULTS_SHORT_HEIGHT_DP else SETTINGS_SEARCH_RESULTS_HEIGHT_DP),
        )
        // Never let Android flip the list above its anchor and cover the field.
        if (width <= 0 || maxHeight <= 0) {
            popup.dismiss()
            return
        }
        val left = location[0].coerceIn(visible.left, visible.right - width)
        val content = popup.contentView
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        val height = content.measuredHeight.coerceIn(1, maxHeight)
        if (popup.isShowing) {
            popup.update(left, top, width, height)
        } else {
            popup.width = width
            popup.height = height
            popup.showAtLocation(box.rootView, Gravity.TOP or Gravity.LEFT, left, top)
        }
    }

    private fun settingsSearchRow(result: SettingsSearchResult): View = column().apply {
        val category = settingsCategoryTitle(result.category)
        isClickable = true
        isFocusable = true
        contentDescription = "${result.title}, $category"
        foreground = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null), focusRing(12)))
        minimumHeight = dp(56)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(label(result.title, 17, TEXT, true))
        addView(label(category, 13, MUTED))
        setOnClickListener { openSearchResult(result) }
    }

    private fun closeSettingsSearch() {
        val box = settingsSearchBox
        setSearchKeyboard(box, false)
        clearSettingsSearchState()
        box?.clearFocus()
        render()
        descendants(window.decorView).firstOrNull { it.tag == SETTINGS_SEARCH_BUTTON_TAG }?.requestFocus()
    }

    private fun clearSettingsSearchState() {
        settingsSearchQuery = ""
        settingsSearchExpanded = false
        settingsSearchIndex = null
        settingsSearchRestoreFocus = false
        settingsSearchPopup?.dismiss()
    }

    private fun setSearchKeyboard(view: View?, show: Boolean) {
        view ?: return
        val keyboard = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        if (show) keyboard.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        else keyboard.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun advancedSettings(content: LinearLayout) {
        val caution = card().apply {
            addView(label(getString(R.string.settings_advanced_caution_title), 18, WARNING, true))
            addView(label(getString(R.string.settings_advanced_caution_description), 14, MUTED).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        content.addView(caution, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.ADVANCED))
    }

    private fun renderSections(
        content: LinearLayout,
        sections: Set<SettingsSection>,
    ) {
        val previous = settingsSectionFilter
        settingsSectionFilter = sections
        try {
            allSettingsSections(content)
        } finally {
            settingsSectionFilter = previous
        }
    }

    private fun allSettingsSections(content: LinearLayout) {
        filteredSection(content, SettingsSection.CONNECTION_SETUP,
            getString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
            wirelessConnectionChoice(card)
        }
        filteredSection(content, SettingsSection.CONNECTION_DETAILS,
            getString(when (selectedWirelessHotspotMode()) {
                WirelessHotspotMode.MANUAL -> R.string.hotspot_setup
                WirelessHotspotMode.EXISTING_WIFI -> R.string.settings_existing_wifi_setup
                else -> R.string.settings_wifi_setup
            }), R.drawable.ic_dp_connection, ::wirelessLinkControls)

        filteredSection(content, SettingsSection.CARPLAY_CONTROLS,
            getString(R.string.carplay_controls), R.drawable.ic_dp_controls) { card ->
            val gestureFingers = listOf(0, 2, 3, 4)
            choice(card, getString(R.string.settings_gesture_fingers_label),
                gestureFingers.map { if (it == 0) getString(R.string.settings_gesture_disabled) else getString(R.string.settings_gesture_fingers_option, it) },
                gestureFingers.indexOf(AirPlayPersistence.loadSettingsGestureFingers(this)).coerceAtLeast(0),
                reconnects = false) {
                AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingers[it])
            }
            card.addView(label(getString(R.string.settings_gesture_fingers_hint), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        filteredSection(content, SettingsSection.WHEEL_KEYS,
            getString(R.string.settings_wheel_keys), R.drawable.ic_dp_controls, ::wheelKeysSettings)
        filteredSection(content, SettingsSection.DIAGNOSTICS,
            getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
            toggle(card, getString(R.string.settings_detailed_debug), getString(R.string.settings_detailed_debug_description),
                DetailedDebugLog.enabled(this)) { enabled ->
                DetailedDebugLog.setEnabled(this, enabled)
                DetailedDebugLog.record("snapshot", "session=${CarPlayBackgroundSession.hasSession()} active=${CarPlayBackgroundSession.active} wireless=${AirPlayPersistence.loadWirelessEnabled(this)} focusDisabled=${AirPlayPersistence.loadDisableMediaKeyAudioFocus(this)} wheelService=${WheelKeyService.connected()}")
                toast(getString(if (enabled) R.string.settings_detailed_debug_started else R.string.settings_detailed_debug_stopped))
            }
            card.addView(toolButton(R.string.settings_debug_marker, R.string.settings_debug_marker_hint, R.drawable.ic_dp_diagnostics) {
                if (DetailedDebugLog.enabled(this)) {
                    DetailedDebugLog.record("USER_MARK", "problem observed session=${CarPlayBackgroundSession.hasSession()} active=${CarPlayBackgroundSession.active}")
                    toast(getString(R.string.settings_debug_marker_added))
                } else toast(getString(R.string.settings_debug_enable_first))
            }, matchButton(SETTINGS_BLOCK_GAP_DP, 60))
            exportButton = toolButton(if (exportInProgress) R.string.saving_report else R.string.save_diagnostic_report, R.string.settings_debug_export_hint, R.drawable.ic_dp_diagnostics) {
                saveReportToDownloads()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            card.addView(toolButton(R.string.choose_save_location, R.string.settings_debug_location_hint, R.drawable.ic_dp_advanced) { chooseReportDestination() }, matchButton(10, 60))
            card.addView(toolButton(R.string.settings_report_on_phone, R.string.settings_debug_phone_hint, R.drawable.ic_dp_connection) { openReportShare() }, matchButton(10, 60))
            val destination = getString(R.string.reports_save_to_downloads_diplay)
            card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
            toggle(card, getString(R.string.settings_fps_counter), getString(R.string.settings_fps_counter_description),
                AirPlayPersistence.loadFpsCounter(this)) { AirPlayPersistence.saveFpsCounter(this, it) }
        }
        filteredSection(content, SettingsSection.AUTOMATIC_CONNECTION,
            getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
            toggle(card, getString(R.string.connect_when_diplay_opens), getString(R.string.default_connection_description), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            toggle(card, getString(R.string.settings_enter_carplay_on_open),
                getString(R.string.settings_enter_carplay_on_open_description),
                DiPlayPreferences.enterCarPlayOnOpen(this)) {
                DiPlayPreferences.saveEnterCarPlayOnOpen(this, it)
            }
            toggle(card, getString(R.string.connect_when_iphone_bluetooth_connects),
                getString(R.string.connect_when_iphone_bluetooth_connects_description),
                DiPlayPreferences.connectOnPhoneBluetooth(this)) { enabled ->
                DiPlayPreferences.saveConnectOnPhoneBluetooth(this, enabled)
                if (enabled && Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    bluetoothAutoConnectPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else if (enabled && DiPlayPreferences.phoneAddress(this) == null) choosePhone()
            }
            val connectionModes = DefaultConnectionMode.entries
            choice(card, getString(R.string.default_connection_mode), listOf(
                getString(R.string.default_connection_last_used),
                getString(R.string.default_connection_wireless),
                getString(R.string.default_connection_usb)
            ), connectionModes.indexOf(DiPlayPreferences.defaultConnectionMode(this)), reconnects = false) {
                DiPlayPreferences.saveDefaultConnectionMode(this, connectionModes[it])
            }
            toggle(card, getString(R.string.open_after_the_car_starts),
                getString(R.string.availability_depends_on_your_head_unit_s_startup_settings),
                AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
            val autoConfirmActive = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
            toggle(
                card,
                getString(R.string.usb_auto_confirm_title),
                getString(R.string.usb_auto_confirm_subtitle),
                autoConfirmActive,
            ) { enabled ->
                if (enabled) promptEnableUsbAutoConfirm()
                else {
                    if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
                    render()
                }
            }
            if (!autoConfirmActive) {
                card.addView(
                    button(
                        getString(R.string.btn_auto_apply_permissions),
                        true,
                    ) {
                        autoApplyPermissions()
                    },
                    matchButton(8, 54),
                )
            } else {
                card.addView(label(getString(R.string.usb_auto_confirm_active_hint), 14, READY).apply {
                    setPadding(0, dp(4), 0, dp(8))
                })
            }
            card.addView(label(getString(R.string.boot_start_repair_desc), 14, MUTED).apply { setPadding(0, dp(8), 0, dp(6)) })
        }
        filteredSection(content, SettingsSection.USB_CONNECTION,
            getString(R.string.settings_usb_connection), R.drawable.ic_dp_connection) { card ->
            val currents = UsbChargingCurrent.entries
            choice(card, getString(R.string.settings_usb_charging), listOf(
                getString(R.string.settings_usb_charging_normal),
                getString(R.string.settings_usb_charging_reduced),
                getString(R.string.settings_usb_charging_low),
            ), currents.indexOf(AirPlayPersistence.loadUsbChargingCurrent(this)), reconnects = false) {
                AirPlayPersistence.saveUsbChargingCurrent(this, currents[it])
                if (!AirPlayPersistence.loadWirelessEnabled(this)) markReconnectNeeded()
            }
            card.addView(label(getString(R.string.settings_usb_charging_description), 14, MUTED))
        }
        filteredSection(content, SettingsSection.DISPLAY_AND_PERFORMANCE,
            getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            card.addView(button(getString(R.string.picture_adjustments), false) {
                OwnScreenStart.start(this, Intent(this, CarPlayHostActivity::class.java)
                    .putExtra("picture_controls", true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, matchButton(0, 56).apply { bottomMargin = dp(24) })
            carPlaySizeControl(card)
            resolutionSettingControl(
                card, R.string.resolution, R.string.custom_resolution_hint,
                CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT, 100,
                R.string.custom_resolution_summary,
                { AirPlayPersistence.loadDisplayScalePercent(this) }, reconnects = true,
                save = { AirPlayPersistence.saveDisplayScalePercent(this, it) },
            )
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            carPlayDockControl(card)
            addSystemBarControls(
                hideTopBar = AirPlayPersistence.loadHideTopBar(this),
                hideBottomBar = AirPlayPersistence.loadHideBottomBar(this),
                onHideTopBarChanged = { AirPlayPersistence.saveHideTopBar(this, it); applyFullscreenMode() },
                onHideBottomBarChanged = { AirPlayPersistence.saveHideBottomBar(this, it); applyFullscreenMode() },
            ) { label, checked, onChanged ->
                toggle(card, getString(label), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), checked, save = onChanged)
            }
            toggle(card, getString(R.string.adapt_pip_resolution), getString(R.string.adapt_pip_resolution_description), AirPlayPersistence.loadAdaptPipResolution(this)) {
                AirPlayPersistence.saveAdaptPipResolution(this, it)
            }
            card.addView(button("${getString(R.string.settings_interface_size)} · ${InterfaceSize.displayName(this, InterfaceSize.preference(this))}", false) {
                InterfaceSize.showPicker(this)
            }, matchButton(12, 60))
            card.addView(label(getString(R.string.settings_interface_size_hint), 14, MUTED))
            if (!isNaturallyExpandedSettingsLayout) toggle(card, getString(R.string.settings_force_full_settings),
                getString(R.string.settings_force_full_settings_hint), SettingsLayoutPreferences.forceFull(this)) {
                SettingsLayoutPreferences.saveForceFull(this, it)
                render()
            }
        }
        // Opt-in controls that can cost sound or video on some head units.
        filteredSection(content, SettingsSection.ADVANCED_MEDIA,
            getString(R.string.settings_advanced_media), R.drawable.ic_dp_advanced) { card ->
            val hardwareYuv = AirPlayPersistence.loadHardwareYuvEnabled(this)
            toggle(card, getString(R.string.settings_hardware_yuv_video),
                getString(R.string.settings_hardware_yuv_description), hardwareYuv) { enabled ->
                AirPlayPersistence.saveHardwareYuvEnabled(this, enabled)
                markReconnectNeeded()
                render()
            }
            val softwareYuv = !hardwareYuv && AirPlayPersistence.loadSoftwareYuvEnabled(this)
            val softwareDirect = !hardwareYuv && (softwareYuv || AirPlayPersistence.loadSoftwareDirectVideoOutput(this))
            val controlledOutput = hardwareYuv || softwareDirect
            toggle(card, getString(R.string.settings_software_yuv_video),
                getString(R.string.settings_software_yuv_description), softwareYuv, enabled = !hardwareYuv) { enabled ->
                val mime = if (AirPlayPersistence.loadHevcEnabled(this)) MediaFormat.MIMETYPE_VIDEO_HEVC
                    else MediaFormat.MIMETYPE_VIDEO_AVC
                if (enabled && com.shilapi.xcertplay.media.softwareYuvDecoderName(mime) == null) {
                    toast(getString(R.string.settings_software_yuv_unavailable))
                } else {
                    AirPlayPersistence.saveSoftwareYuvEnabled(this, enabled)
                    markReconnectNeeded()
                }
                render()
            }
            if (softwareYuv) {
                val color = AirPlayPersistence.loadSoftwareYuvColor(this)
                choice(card, getString(R.string.settings_software_yuv_range), listOf(
                    getString(R.string.settings_software_yuv_auto), getString(R.string.settings_software_yuv_full),
                    getString(R.string.settings_software_yuv_limited)), color.range, reconnects = false) {
                    AirPlayPersistence.saveSoftwareYuvColor(this, AirPlayPersistence.loadSoftwareYuvColor(this).copy(range = it))
                    markReconnectNeeded()
                }
                choice(card, getString(R.string.settings_software_yuv_matrix), listOf(
                    getString(R.string.settings_software_yuv_auto), "BT.709", "BT.601"), color.matrix, reconnects = false) {
                    AirPlayPersistence.saveSoftwareYuvColor(this, AirPlayPersistence.loadSoftwareYuvColor(this).copy(matrix = it))
                    markReconnectNeeded()
                }
                card.addView(label(getString(R.string.settings_software_yuv_hint), 14, MUTED))
            }
            toggle(card, getString(R.string.settings_software_direct_video),
                getString(R.string.settings_software_direct_video_description),
                !hardwareYuv && !softwareYuv && AirPlayPersistence.loadSoftwareDirectVideoOutput(this), enabled = !hardwareYuv && !softwareYuv) { enabled ->
                val mime = if (AirPlayPersistence.loadHevcEnabled(this)) MediaFormat.MIMETYPE_VIDEO_HEVC
                    else MediaFormat.MIMETYPE_VIDEO_AVC
                if (enabled && com.shilapi.xcertplay.media.diagnosticSoftwareDecoderName(
                        com.shilapi.xcertplay.media.diagnosticSoftwareCandidates(), mime) == null) {
                    toast(getString(R.string.settings_software_direct_video_unavailable))
                } else {
                    AirPlayPersistence.saveSoftwareDirectVideoOutput(this, enabled)
                    markReconnectNeeded()
                }
                render()
            }
            toggle(card, getString(R.string.efficient_video), getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility),
                AirPlayPersistence.loadHevcEnabled(this), enabled = !softwareDirect) {
                AirPlayPersistence.saveHevcEnabled(this, it)
                markReconnectNeeded()
                render()
            }
            val hevc = AirPlayPersistence.loadHevcEnabled(this)
            val selectedMime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val mtkDecoder = if (controlledOutput || (hevc && AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this))) null
                else selectedMtkDecoderName(selectedMime)
            if (mtkDecoder != null) {
                toggle(card, getString(R.string.settings_mtk_decoder_tuning),
                    getString(R.string.settings_mtk_decoder_tuning_description, mtkDecoder),
                    AirPlayPersistence.loadMtkDecoderTuning(this)) {
                    AirPlayPersistence.saveMtkDecoderTuning(this, it)
                    markReconnectNeeded()
                }
            }
            toggle(card, getString(R.string.smooth_video), getString(R.string.smooth_video_description),
                !controlledOutput && AirPlayPersistence.loadSmoothVideo(this), enabled = !controlledOutput) {
                AirPlayPersistence.saveSmoothVideo(this, it)
                reconnectIfRunning()
            }
            toggle(card, getString(R.string.settings_direct_video_output),
                getString(R.string.settings_direct_video_output_description),
                controlledOutput || AirPlayPersistence.loadDirectVideoOutput(this), enabled = !controlledOutput) {
                AirPlayPersistence.saveDirectVideoOutput(this, it)
                reconnectIfRunning()
            }
            toggle(card, getString(R.string.settings_low_latency_decoder),
                getString(R.string.settings_low_latency_decoder_description),
                !controlledOutput && AirPlayPersistence.loadLowLatencyDecoder(this), enabled = !controlledOutput) {
                AirPlayPersistence.saveLowLatencyDecoder(this, it)
                markReconnectNeeded()
            }
            toggle(card, getString(R.string.settings_native_hfp_calls), getString(R.string.settings_native_hfp_calls_description),
                AirPlayPersistence.loadNativeHfpCalls(this)) {
                AirPlayPersistence.saveNativeHfpCalls(this, it)
                markReconnectNeeded()
            }
            toggle(card, getString(R.string.call_echo_cancellation), getString(R.string.call_echo_cancellation_description),
                AirPlayPersistence.loadCallEchoCancellation(this)) {
                AirPlayPersistence.saveCallEchoCancellation(this, it)
                markReconnectNeeded()
            }
            toggle(card, getString(R.string.call_voice_filter), getString(R.string.call_voice_filter_description),
                AirPlayPersistence.loadCallVoiceFilter(this)) {
                AirPlayPersistence.saveCallVoiceFilter(this, it)
                markReconnectNeeded()
            }
            experimentalAudioControls(card)
            audioFocusControls(card)
            if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                toggle(card, getString(R.string.advanced_audio_channel_mapping),
                    getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                    markReconnectNeeded()
                }
            }
            toggle(card, getString(R.string.main_buffered_audio), getString(R.string.main_buffered_audio_description),
                AirPlayPersistence.loadMainBufferedAudio(this)) {
                AirPlayPersistence.saveMainBufferedAudio(this, it)
                reconnectIfRunning()
            }
            toggle(card, getString(R.string.settings_speech_without_low_rates), getString(R.string.settings_speech_without_low_rates_description),
                AirPlayPersistence.loadSpeechWithout8And16Khz(this)) {
                AirPlayPersistence.saveSpeechWithout8And16Khz(this, it)
                reconnectIfRunning()
            }
            toggle(card, getString(R.string.settings_car_bluetooth_audio), getString(R.string.settings_car_bluetooth_audio_description),
                AirPlayPersistence.loadCarBluetoothAudio(this)) {
                AirPlayPersistence.saveCarBluetoothAudio(this, it)
                reconnectIfRunning()
            }
        }
        filteredSection(content, SettingsSection.AUDIO_ROUTING,
            getString(R.string.audio_routing), R.drawable.ic_dp_audio) { card ->
            mediaChannelControl(card)
            navigationChannelControl(card)
            val gainSlider = overlaySliderRow(
                getString(R.string.settings_navigation_attenuation),
                (com.shilapi.xcertplay.media.NavigationAudioGain.MIN_DB..
                    com.shilapi.xcertplay.media.NavigationAudioGain.MAX_DB).toList(),
                AirPlayPersistence.loadNavigationAudioAttenuationDb(this),
            ) { db -> getString(R.string.settings_navigation_attenuation_value, db) }
            val gainTitle = getString(R.string.settings_navigation_attenuation)
            gainSlider.slider.contentDescription = gainTitle + ": " +
                getString(R.string.settings_navigation_attenuation_value,
                    AirPlayPersistence.loadNavigationAudioAttenuationDb(this))
            gainSlider.onSave = { db ->
                AirPlayPersistence.saveNavigationAudioAttenuationDb(this, db)
                DiPlaySessionService.liveRuntime?.currentConnection?.sink?.setNavigationAttenuationDb(db)
                gainSlider.slider.contentDescription = gainTitle + ": " +
                    getString(R.string.settings_navigation_attenuation_value, db)
            }
            card.addView(gainSlider)
            card.addView(label(getString(R.string.settings_navigation_attenuation_description), 14, MUTED).apply {
                setPadding(0, dp(SETTINGS_BLOCK_GAP_DP), 0, 0)
            })
            musicBufferChoice(card, wireless = true)
            musicBufferChoice(card, wireless = false)
            toggle(card, getString(R.string.settings_call_speakerphone), getString(R.string.settings_call_speakerphone_description),
                AirPlayPersistence.loadCallSpeakerphone(this)) { AirPlayPersistence.saveCallSpeakerphone(this, it) }
        }
        filteredSection(content, SettingsSection.NAVIGATION_WHEEL,
            getString(R.string.settings_navigation_wheel_volume), R.drawable.ic_dp_audio) { card ->
            navigationWheelControls(card)
        }
        filteredSection(content, SettingsSection.LOCATION,
            getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.report_location_to_iphone),
                getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                AirPlayPersistence.loadLocationReportingEnabled(this), save = ::onLocationReportingChanged)
            card.addView(label(getString(R.string.location_reporting_reconnects), 14, MUTED))
        }
        filteredSection(content, SettingsSection.CLUSTER_MAP,
            getString(R.string.carplay_map_on_instrument_cluster_experimental), R.drawable.ic_dp_dashboard) { card ->
            val clusterMapEnabled = AirPlayPersistence.loadClusterMapEnabled(this)
            reconnectingToggle(card, getString(R.string.carplay_map_on_instrument_cluster_experimental),
                getString(R.string.shows_the_iphone_s_cluster_map_virtual_stream_description),
                clusterMapEnabled) {
                AirPlayPersistence.saveClusterMapEnabled(this, it)
                render()
            }
            if (clusterMapEnabled) {
                clusterMusicControls(card)
                toggle(card, getString(R.string.center_map_card),
                    getString(R.string.center_map_card_virtual_description),
                    AirPlayPersistence.loadCenterMapOverlay(this)) {
                    AirPlayPersistence.saveCenterMapOverlay(this, it)
                    if (it && !CenterMapOverlay.permitted(this)) openOverlayPermission()
                    render()
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    toggle(card, getString(R.string.center_map_follows_dashboard), getString(R.string.center_map_follows_dashboard_description),
                        AirPlayPersistence.loadCenterMapFollowsDashboard(this)) {
                        AirPlayPersistence.saveCenterMapFollowsDashboard(this, it)
                    }
                    toggle(card, getString(R.string.center_map_auto_hide), getString(R.string.center_map_auto_hide_description),
                        AirPlayPersistence.loadCenterMapAutoHide(this)) {
                        AirPlayPersistence.saveCenterMapAutoHide(this, it)
                    }
                }
                toggle(card, getString(R.string.launcher_map_sharing), getString(R.string.launcher_map_sharing_description),
                    AirPlayPersistence.loadLauncherMapSharing(this)) {
                    AirPlayPersistence.saveLauncherMapSharing(this, it)
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    val overlay = CenterMapOverlay.permitted(this)
                    card.addView(label(if (overlay) getString(R.string.center_map_overlay_allowed)
                        else getString(R.string.center_map_overlay_missing, packageName), 14, if (overlay) MUTED else WARNING))
                    val usage = HomeScreenMonitor.hasAccess(this)
                    card.addView(label(if (usage) getString(R.string.center_map_auto_hide_active)
                        else getString(R.string.center_map_auto_hide_needed), 14, if (usage) MUTED else WARNING))
                    if (!usage) {
                        card.addView(button(getString(R.string.btn_auto_apply_permissions), false) { autoApplyPermissions() }, matchButton(8, 54))
                    }
                }

            }
        }
        filteredSection(content, SettingsSection.PERMISSIONS_AND_HELP,
            getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
            card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button(getString(R.string.bluetooth_settings), false) { openBluetoothSettings() }, matchButton(10, 60))
            card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
    }

    private fun about(content: LinearLayout) {
        section(content, getString(R.string.app_name)) { card ->
            card.addView(label(getString(R.string.settings_about_version, version()), 18, ACCENT, true))
            card.addView(label(getString(R.string.settings_about_summary), 18, MUTED))
        }
        section(content, getString(R.string.settings_about_features_title)) { card ->
            card.addView(label(getString(R.string.settings_about_features_summary), 18, TEXT))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }
    }

    private fun openReportShare() {
        page = "report-share"
        render()
    }

    private fun reportShare(content: LinearLayout) {
        val preferredPrefix = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.WIFI_P2P -> "p2p"
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "ap"
            WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI -> "wlan"
        }
        val addresses = ReportShareServer.addressCandidates(preferredPrefix)
        content.addView(label(getString(R.string.settings_report_on_phone), 40, TEXT, true))
        var endpoint = reportShareEndpoint
        // A running endpoint can outlive its last network interface. Re-rendering must not
        // index an empty address list; keep the endpoint available if the network returns.
        if (addresses.isEmpty()) {
            content.addView(space(SETTINGS_BLOCK_GAP_DP))
            content.addView(label(getString(R.string.settings_report_no_network), 16, MUTED))
            return
        }
        if (reportShareServer == null || endpoint == null) {
            val created = ReportShareServer(
                page = reportSharePageHtml(),
                report = { diagnosticReportText(applicationContext).toByteArray(Charsets.UTF_8) },
                codePage = assets.open("report-code.html").use { it.readBytes() },
            )
            created.onCodeEntryClosed = {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && reportShareServer === created) render()
                }
            }
            created.onIdle = {
                runOnUiThread {
                    if (isFinishing || isDestroyed || reportShareServer !== created) return@runOnUiThread
                    stopReportShareServer()
                    page = "settings"
                    settingsCategory = SettingsCategory.DIAGNOSTICS
                    render()
                }
            }
            endpoint = created.start()
            if (endpoint == null) {
                created.stop()
                content.addView(space(SETTINGS_BLOCK_GAP_DP))
                content.addView(label(getString(R.string.settings_report_no_network), 16, MUTED))
                return
            }
            reportShareServer = created
            reportShareEndpoint = endpoint
        }
        val active = endpoint
        val qr = ImageView(this).apply {
            val side = dp(240)
            setImageBitmap(ReportShareQrCode.encode(active.url(addresses.first()), side))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(space(SETTINGS_BLOCK_GAP_DP))
        content.addView(card().apply {
            addView(label(getString(R.string.settings_report_on_phone_hint), 16, MUTED))
            addView(qr, LinearLayout.LayoutParams(dp(240) + dp(24), dp(240) + dp(24)).apply {
                topMargin = dp(SETTINGS_BLOCK_GAP_DP); gravity = Gravity.CENTER_HORIZONTAL
            })
            // For a computer, or a phone whose camera will not read the code.
            addView(label(
                if (reportShareServer?.codeEntryOpen == true) {
                    getString(R.string.settings_report_type_address, active.typedAddress(addresses.first()),
                        active.code.chunked(3).joinToString(" "))
                } else getString(R.string.settings_report_code_entry_closed),
                16,
                TEXT,
            ).apply { setPadding(0, dp(16), 0, 0) })
            if (addresses.size > 1) {
                addView(label(
                    addresses.drop(1).joinToString("\n") { getString(R.string.settings_report_other_addresses, it) },
                    14,
                    MUTED,
                ).apply { setPadding(0, dp(16), 0, 0) })
            }
        })
    }

    private fun stopReportShareServer() {
        reportShareServer?.stop()
        reportShareServer = null
        reportShareEndpoint = null
    }

    private fun reportSharePageHtml(): ByteArray = assets.open("report-share.html").use { it.readBytes() }

    /** Retire jobs persisted by versions that still offered online updates. */
    private fun cancelLegacyUpdateChecks() {
        runCatching { BackgroundUpdateScheduler.cancel(applicationContext) }
            .onFailure { Log.i("Apple CarPlay", "legacy update work cancellation unavailable", it) }
    }

    // An opted-in connection prepares the hotspot in the controller instead of stopping at this reminder.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            CarHotspotSettings.reportedOff(this, com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this)) &&
            !(CarHotspotSettings.enabled(this) && CarHotspotTethering.permitted(this))

    private fun startCarHotspotOnLaunch() {
        if (!startupHotspotEligible()) return
        val app = applicationContext
        Thread({
            val started = runCatching { com.shilapi.xcertplay.network.CarHotspotController(app,
                log = { DetailedDebugLog.record("hotspot-startup", it) })
                .enable { startupHotspotCancelled || !startupHotspotEligible() } }.getOrNull()
            val result = if (started?.success == true) CarHotspotTethering.Result.READY
                else if (startupHotspotCancelled) CarHotspotTethering.Result.CANCELLED else CarHotspotTethering.Result.FAILED
            runOnUiThread {
                if (isFinishing || isDestroyed || result == CarHotspotTethering.Result.CANCELLED) return@runOnUiThread
                hotspotStartupResult = result
                if (page == "home") render()
            }
        }, "diplay-hotspot-startup").start()
    }

    private fun startupHotspotEligible(): Boolean =
        CarHotspotSetup.shouldStartOnLaunch(applicationContext, CarPlayBackgroundSession.hasSession())

    private fun hotspotResultText(result: CarHotspotTethering.Result): String = getString(when (result) {
        CarHotspotTethering.Result.READY -> R.string.hotspot_control_on
        CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.hotspot_control_missing
        CarHotspotTethering.Result.UNSUPPORTED -> R.string.hotspot_control_unsupported
        CarHotspotTethering.Result.TIMED_OUT -> R.string.hotspot_control_timeout
        else -> R.string.hotspot_control_failed
    })

    private fun carHotspotOffDialog() {
        appDialogBuilder().setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ ->
                // The driver sees the hotspot on: trust that over Android's report, or this dialog would
                // come back on every tap (#604).
                CarHotspotSettings.setOffReportIgnored(this, true)
                connect(true)
            }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun openWriteSettingsAccess() =
        openSystem(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))

    private fun openBluetoothSettings() = openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    private fun openCarWifiSettings() {
        val tether = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        if (packageManager.resolveActivity(tether, 0) != null &&
            runCatching { startActivity(tether) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)

        openSystem(wifi)
    }

    private fun selectedWirelessHotspotMode(): WirelessHotspotMode =
        if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)

    private fun wirelessModeTitle(mode: WirelessHotspotMode): String = getString(when (mode) {
        WirelessHotspotMode.MANUAL -> R.string.settings_built_in_car_hotspot_recommended
        WirelessHotspotMode.WIFI_P2P -> R.string.wifi_direct
        WirelessHotspotMode.EXISTING_WIFI -> R.string.existing_wifi_title
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> R.string.settings_built_in_car_hotspot_recommended
    })

    private fun wirelessConnectionChoice(parent: LinearLayout) {
        val current = selectedWirelessHotspotMode()
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI)
        choice(parent, getString(R.string.settings_connection_method), modes.map(::wirelessModeTitle),
            modes.indexOf(current), reconnects = false) { index ->
            val mode = modes[index]
            pendingCarHotspotSetup = false
            applyWirelessLink(mode)
        }
        parent.addView(label(getString(R.string.settings_connection_method_description), 16, MUTED))
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = selectedWirelessHotspotMode()
        if (mode != WirelessHotspotMode.MANUAL) {
            parent.addView(label(getString(if (mode == WirelessHotspotMode.EXISTING_WIFI)
                R.string.existing_wifi_description else R.string.hotspot_mode_p2p_desc), 16, MUTED))
        }
        if (mode == WirelessHotspotMode.MANUAL) {
            val ui = hotspotSettingsUi ?: CarHotspotSettingsUi(this,
                changed = { pendingCarHotspotSetup = false; markReconnectNeeded(); render() },
                redraw = { render() },
            ).also { hotspotSettingsUi = it }
            ui.build(parent)
        } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
            parent.addView(label(getString(R.string.existing_wifi_instructions), 16, MUTED))
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
            parent.addView(button(getString(R.string.existing_wifi_details), false) {
                askHotspotCredentials(existingWifi = true) { ssid, password, _ ->
                    AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                    toast(getString(R.string.saved_for_your_next_connection))
                }
            }, matchButton(12, 60))
        } else {
            parent.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            wifiDirectChannelControl(parent)
            // #755: the pause also stops the car joining a known network, such as the phone's hotspot, by itself.
            toggle(parent, getString(R.string.settings_wifi_direct_scan_pause),
                getString(R.string.settings_wifi_direct_scan_pause_description),
                AirPlayPersistence.loadWifiDirectScanPause(this)) {
                AirPlayPersistence.saveWifiDirectScanPause(this, it)
                markReconnectNeeded()
            }
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    private fun wifiDirectChannelLabel(channel: Int): String = if (channel == WifiP2pChannels.AUTO) {
        getString(R.string.auto)
    } else if (channel == WifiP2pChannels.AUTO_5_GHZ || channel == WifiP2pChannels.AUTO_2_4_GHZ) {
        getString(R.string.settings_wifi_direct_auto_band,
            getString(if (channel == WifiP2pChannels.AUTO_5_GHZ) R.string.s_5_ghz else R.string.s_2_4_ghz))
    } else {
        getString(R.string.wifi_direct_channel_choice, channel,
            getString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))
    }

    private fun wifiDirectChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.wifi_direct_channel_summary, wifiDirectChannelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadWifiP2pPreferredChannel(this)), false) {}
        control.setOnClickListener {
            val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.bandChoices + WifiP2pChannels.channels
            val current = AirPlayPersistence.loadWifiP2pPreferredChannel(this)
            var selection = current
            appDialogBuilder().setTitle(R.string.wifi_direct_channel_title)
                .setSingleChoiceItems(choices.map(::wifiDirectChannelLabel).toTypedArray(),
                    choices.indexOf(current)) { _, which -> selection = choices[which] }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (selection != current) {
                        AirPlayPersistence.saveWifiP2pPreferredChannel(this, selection)
                        control.text = summary(selection)
                        toast(getString(R.string.saved_for_your_next_connection))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parent.addView(control, matchButton(12, 60))
        parent.addView(label(getString(R.string.wifi_direct_channel_description), 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(6))
        })
        parent.addView(label(getString(R.string.settings_wifi_direct_band_note), 15, MUTED).apply {
            setPadding(0, 0, 0, dp(12))
        })
    }

    private fun mediaChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_media_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadMediaAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_media_channel_label),
                current = current,
                navigation = false,
                onApply = { value -> applyMediaChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(12, 60))
    }

    private fun navigationChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_nav_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadNavigationAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_nav_channel_label),
                current = current,
                navigation = true,
                onApply = { value -> applyNavigationChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(10, 60))
        parent.addView(label(getString(R.string.contrib_audio_home_nav_channel_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    private fun showChannelDialog(title: String, current: Int, navigation: Boolean, onApply: (Int) -> Unit) {
        val preview = AudioChannelPreview { channel ->
            toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = current.coerceIn(channels.first, channels.last)
        appDialogBuilder().setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_and_reconnect) else getString(R.string.save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun channelLabel(value: Int): String = value.toString()

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    /** True until a usable car hotspot name and password are saved, as on a first run. */
    private fun carHotspotDetailsMissing() = com.shilapi.xcertplay.network.CarHotspotProfiles.confirmed(this) == null &&
        hotspotError(storedSsid(), storedPassword()) != null

    private fun manualHotspotProfile(): com.shilapi.xcertplay.network.CarHotspotProfile? =
        AirPlayPersistence.loadConfiguredManualHotspotProfile(this)

    private fun saveHotspotCredentials(ssid: String, password: String, security: ManualHotspotSecurity) {
        val previous = com.shilapi.xcertplay.network.CarHotspotProfiles.saved(this)
        com.shilapi.xcertplay.network.CarHotspotProfiles.saveSaved(this,
            com.shilapi.xcertplay.network.CarHotspotProfile(ssid, password, security,
                previous?.band ?: com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO,
                previous?.channel ?: 0))
        markReconnectNeeded()
    }

    private fun askHotspotCredentials(existingWifi: Boolean = false, done: (String, String, ManualHotspotSecurity) -> Unit) {
        val dialogContext = appDialogContext()
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(if (existingWifi) R.string.existing_wifi_instructions else R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(dialogContext).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_ssid else R.string.hotspot_name)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiSsid(this@DiPlayActivity) else storedSsid())
            setSingleLine()
        }
        val password = EditText(dialogContext).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_password else R.string.hotspot_password)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiPassphrase(this@DiPlayActivity) else storedPassword())
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        val nameWarning = label(getString(R.string.settings_hotspot_name_non_english), 14, WARNING)
        fun updateNameWarning() {
            nameWarning.visibility = if (!existingWifi &&
                com.shilapi.xcertplay.orchestration.ManualHotspotValidation.nameMayNotReachIphone(ssid.text.toString())
            ) View.VISIBLE else View.GONE
        }
        updateNameWarning()
        ssid.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = updateNameWarning()
        })
        fields.addView(ssid); fields.addView(nameWarning); fields.addView(password)
        fields.addView(CheckBox(dialogContext).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val securityModes = listOf(ManualHotspotSecurity.WPA2, ManualHotspotSecurity.WPA3_TRANSITION, ManualHotspotSecurity.WPA3)
        val securityTitles = listOf(getString(R.string.wpa2), getString(R.string.wpa3_transition), getString(R.string.wpa3))
        var protectedSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
            .takeIf { it in securityModes } ?: ManualHotspotSecurity.WPA2
        if (!existingWifi) {
            lateinit var securityControl: Button
            fun updateSecurityControl() {
                val hasPassword = password.text.isNotEmpty()
                val title = if (hasPassword) securityTitles[securityModes.indexOf(protectedSecurity)] else getString(R.string.open)
                securityControl.text = "${getString(R.string.security)} · $title"
                securityControl.isEnabled = hasPassword
            }
            securityControl = button("", false) {
                var pendingSecurity = protectedSecurity
                appDialogBuilder().setTitle(getString(R.string.security))
                    .setSingleChoiceItems(securityTitles.toTypedArray(), securityModes.indexOf(protectedSecurity)) { _, index ->
                        pendingSecurity = securityModes[index]
                    }
                    .setPositiveButton(getString(R.string.save)) { _, _ ->
                        protectedSecurity = pendingSecurity
                        updateSecurityControl()
                    }.setNegativeButton(getString(R.string.cancel), null).show()
            }
            updateSecurityControl()
            password.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = updateSecurityControl()
            })
            fields.addView(securityControl, matchButton(12, 60))
            fields.addView(label(getString(R.string.saved_for_your_next_connection), 15, MUTED))
        }
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = appDialogBuilder().setTitle(getString(if (existingWifi) R.string.existing_wifi_details else R.string.car_hotspot_details))
            .setView(ScrollView(dialogContext).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = if (existingWifi) ssid.text.toString() else ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else {
                    val security = if (secret.isEmpty()) ManualHotspotSecurity.OPEN else protectedSecurity
                    hideKeyboard(); dialog.dismiss(); done(name, secret, security)
                }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun markerStepLabel(step: Int, negative: String, positive: String): String = when {
        step == 0 -> getString(R.string.marker_centre_default)
        step < 0 -> "$negative ${-step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
        else -> "$positive ${step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
    }

    private fun clusterMusicControls(card: LinearLayout) {
        toggle(card, getString(R.string.settings_cluster_music), getString(R.string.settings_cluster_music_description),
            ClusterMusicSettings.enabled(this)) {
            ClusterMusicSettings.saveEnabled(this, it)
            render()
        }
        if (!ClusterMusicSettings.enabled(this)) return
        choice(card, getString(R.string.settings_cluster_music_mode), listOf(
            getString(R.string.settings_cluster_music_temporary), getString(R.string.settings_cluster_music_permanent)),
            if (ClusterMusicSettings.permanent(this)) 1 else 0, reconnects = false) {
            ClusterMusicSettings.savePermanent(this, it == 1)
        }
        listOf(
            Triple("x", R.string.settings_cluster_music_horizontal, ClusterMusicSettings.x(this)),
            Triple("y", R.string.settings_cluster_music_vertical, ClusterMusicSettings.y(this)),
            Triple("size", R.string.settings_cluster_music_size, ClusterMusicSettings.size(this)),
        ).forEach { (key, title, value) ->
            card.addView(overlaySliderRow(getString(title),
                (if (key == "size") 50..150 step 5 else 5..95 step 5).toList(), value) { "$it%" }.also {
                it.onSave = { percent -> ClusterMusicSettings.save(this, key, percent) }
                val head = it.getChildAt(0) as LinearLayout
                val value = head.getChildAt(head.childCount - 1)
                value.layoutParams = (value.layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = dp(SETTINGS_BLOCK_GAP_DP)
                }
            })
        }
    }

    private fun overlaySliderRow(title: String, values: List<Int>, current: Int, describe: (Int) -> String): OverlaySliderRow =
        OverlaySliderRow(this, title, values, current, describe)

    private inner class OverlaySliderRow(
        context: android.content.Context,
        title: String,
        private val steps: List<Int>,
        current: Int,
        private val describe: (Int) -> String,
    ) : LinearLayout(context) {
        var onSave: (Int) -> Unit = {}
        /** Fired once when the finger leaves the slider, for saves that need a reconnect. */
        var onCommit: () -> Unit = {}
        val slider: SeekBar

        init {
            orientation = VERTICAL
            val valueView = label(describe(current), 16, ACCENT, true)
            val head = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
            head.addView(label(title, 16, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(valueView)
            addView(head)
            slider = SeekBar(context).apply {
                max = steps.lastIndex
                progress = steps.indexOf(current).coerceIn(steps.indices)
                // ProgressBar.setMinHeight needs API 29; the view minimum keeps the touch target before that.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) minHeight = dp(44) else minimumHeight = dp(44)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = steps[progress.coerceIn(steps.indices)]
                        valueView.text = describe(value)
                        if (fromUser) onSave(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) { onCommit() }
                })
            }
            addView(slider, LinearLayout.LayoutParams(-1, dp(44)))
        }
    }

    private fun overlayOffsetLabel(percent: Int, negative: String, positive: String, centre: Int): String {
        val delta = percent - centre
        return when {
            delta == 0 -> getString(R.string.marker_centre_default)
            delta < 0 -> "$negative ${-delta} %"
            else -> "$positive $delta %"
        }
    }

    private fun promptEnableUsbAutoConfirm() {
        if (UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)) {
            toast(getString(R.string.usb_auto_confirm_status_on))
            return
        }
        appDialogBuilder()
            .setTitle(getString(R.string.usb_auto_confirm_title))
            .setMessage(getString(R.string.usb_auto_confirm_dialog_msg))
            .setPositiveButton(getString(R.string.btn_auto_apply_permissions)) { _, _ ->
                autoApplyPermissions()
            }
            .setNeutralButton(getString(R.string.btn_open_accessibility_setting)) { _, _ ->
                if (!UsbAutoConfirmService.openSettings(this)) {
                    toast(getString(R.string.wheel_keys_no_settings))
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                render()
            }
            .setOnCancelListener {
                render()
            }
            .show()
    }

    private fun cancelUsbPermissionSetup() {
        val operation = usbPermissionOperation
        usbPermissionOperation = null
        operation?.cancel()
        val dialog = usbPermissionDialog
        usbPermissionDialog = null
        dialog?.dismiss()
    }

    private fun autoApplyPermissions() {
        if (usbPermissionOperation != null || isFinishing || isDestroyed) return
        val operation = usbPermissionOperationFactory(applicationContext)
        usbPermissionOperation = operation
        val progress = appDialogBuilder()
            .setTitle(R.string.auto_grant_title)
            .setMessage(R.string.auto_grant_msg)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelUsbPermissionSetup() }
            .create()
        usbPermissionDialog = progress
        progress.setOnCancelListener { cancelUsbPermissionSetup() }
        progress.setOnDismissListener {
            if (usbPermissionDialog === progress) cancelUsbPermissionSetup()
        }
        progress.show()
        Thread({
            val result = operation.run()
            handler.post {
                if (usbPermissionOperation !== operation || operation.isCancelled || isFinishing || isDestroyed) {
                    return@post
                }
                usbPermissionOperation = null
                usbPermissionDialog = null
                progress.dismiss()
                render()
                if (result.complete) {
                    appDialogBuilder()
                        .setTitle(R.string.auto_grant_success_title)
                        .setMessage(R.string.auto_grant_success_msg)
                        .setPositiveButton(R.string.close, null)
                        .show()
                } else {
                    val reason = when (result.access) {
                        LocalAdb.Access.NOT_APPROVED -> getString(R.string.auto_grant_confirm_msg)
                        LocalAdb.Access.UNSUPPORTED -> getString(R.string.adb_pairing_only)
                        else -> getString(R.string.auto_grant_incomplete)
                    }
                    showManualPermissionDialog(reason)
                }
            }
        }, "diplay-auto-permission").start()
    }

    private fun showManualPermissionDialog(reason: String) {
        val body = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
        body.addView(label(reason, 15, MUTED).apply { setPadding(0, 0, 0, dp(12)) })

        val autoConfirmOn = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
        body.addView(button(if (autoConfirmOn) getString(R.string.usb_auto_confirm_status_on) else getString(R.string.btn_open_accessibility_setting), false) {
            if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
        }, matchButton(0, 56))

        val overlayOn = CenterMapOverlay.permitted(this)
        body.addView(button(if (overlayOn) getString(R.string.center_map_overlay_allowed) else getString(R.string.btn_open_overlay_setting), false) {
            openOverlayPermission()
        }, matchButton(10, 56))

        UsbPermissionSetup.snapshot(this).forEach { (permission, granted) ->
            val title = getString(when (permission) {
                UsbPermissionSetup.Permission.ACCESSIBILITY -> R.string.usb_auto_confirm_title
                UsbPermissionSetup.Permission.USAGE -> R.string.center_map_auto_hide
                UsbPermissionSetup.Permission.OVERLAY -> R.string.btn_open_overlay_setting
            })
            body.addView(label("$title: ${getString(if (granted) R.string.permission_enabled_ready else R.string.permission_not_enabled)}", 14, if (granted) MUTED else WARNING))
        }
        val adbCmd = UsbPermissionSetup.manualCommand(packageName)
        body.addView(label(getString(R.string.manual_grant_cmd_hint), 14, MUTED).apply { setPadding(0, dp(12), 0, dp(6)) })
        body.addView(label(adbCmd, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(BUTTON)
        })
        body.addView(button(getString(R.string.copy_command), false) {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText("Apple CarPlay ADB Command", adbCmd)
            )
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(8, 50))

        appDialogBuilder()
            .setTitle(getString(R.string.permissions_and_connection_help))
            .setView(ScrollView(appDialogContext()).apply { addView(body) })
            .setPositiveButton(getString(R.string.close)) { _, _ -> render() }
            .show()
    }

    /** Optional guidance-volume routing through the existing wheel service. */
    private fun navigationWheelControls(card: LinearLayout) {
        toggle(card, getString(R.string.settings_navigation_wheel_volume), getString(R.string.settings_navigation_wheel_volume_description),
            NavigationWheelSettings.enabled(this)) {
            NavigationWheelSettings.setEnabled(this, it)
            render()
        }
        if (!NavigationWheelSettings.enabled(this) || WheelKeyService.connected()) return
        card.addView(label(getString(R.string.settings_navigation_wheel_service_required), 14, WARNING))
        card.addView(button(getString(R.string.wheel_keys_open_settings), false) {
            if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
        }, matchButton(10, 56))
        card.addView(button(getString(R.string.wheel_keys_enable_adb), false) {
            Thread({
                val access = WheelKeyService.enableOverAdb(this)
                runOnUiThread {
                    if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                        toast(getString(R.string.wheel_keys_adb_failed, access.name))
                    }
                    render()
                }
            }, "diplay-navigation-wheel-enable").start()
        }, matchButton(10, 56))
    }

    /** Steering-wheel keys for the dashboard map zoom and the CarPlay joystick: the switches, the key service and the keys. */
    // One place for the key service setup, above every feature that needs it.
    private fun wheelKeysSettings(card: LinearLayout) {
        if (WheelZoomSettings.siriKey(this)) wheelKeyServiceControls(card)
        siriKeyControls(card)
    }

    /** A wheel key that opens Siri on any car whose key reaches the key service. */
    private fun siriKeyControls(card: LinearLayout) {
        toggle(card, getString(R.string.wheel_siri_key), getString(R.string.wheel_siri_key_description),
            WheelZoomSettings.siriKey(this)) {
            WheelZoomSettings.setSiriKey(this, it)
            render()
        }
        if (!WheelZoomSettings.siriKey(this)) return
        toggle(card, getString(R.string.wheel_siri_double_press),
            getString(R.string.wheel_siri_double_press_description),
            WheelZoomSettings.siriDoublePress(this)) {
            WheelZoomSettings.setSiriDoublePress(this, it)
            render()
        }
        if (WheelZoomSettings.siriDoublePress(this)) {
            val intervals = WheelZoomSettings.SIRI_DOUBLE_PRESS_INTERVALS
            choice(card, getString(R.string.wheel_siri_double_press_interval),
                intervals.map { getString(R.string.fps_counter_millis, it) },
                intervals.indexOf(WheelZoomSettings.siriDoublePressIntervalMillis(this)), reconnects = false) {
                WheelZoomSettings.setSiriDoublePressIntervalMillis(this, intervals[it])
            }
            val delays = WheelZoomSettings.SIRI_TRIGGER_DELAYS
            choice(card, getString(R.string.wheel_siri_trigger_delay),
                delays.map { getString(R.string.fps_counter_millis, it) },
                delays.indexOf(WheelZoomSettings.siriTriggerDelayMillis(this)), reconnects = false) {
                WheelZoomSettings.setSiriTriggerDelayMillis(this, delays[it])
            }
        }
        wheelKeyAssignButton(card, WheelZoomSettings.Role.SIRI, getString(R.string.wheel_key_role_siri))
    }

    private fun wheelKeyServiceControls(card: LinearLayout) {
        val connected = WheelKeyService.connected()
        card.addView(label(getString(when {
            connected -> R.string.wheel_keys_service_on
            WheelKeyService.enabledInSettings(this) -> R.string.wheel_keys_service_starting
            else -> R.string.wheel_keys_service_off
        }), 14, if (connected) MUTED else WARNING))
        if (!connected) {
            card.addView(actionButton(getString(R.string.wheel_keys_enable_adb), false) {
                Thread({
                    val access = WheelKeyService.enableOverAdb(this)
                    runOnUiThread {
                        if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                            toast(getString(R.string.wheel_keys_adb_failed, access.name))
                        }
                        render()
                    }
                }, "diplay-wheel-keys-enable").start()
            }, matchButton(10, 56))
            card.addView(button(getString(R.string.wheel_keys_open_settings), false) {
                if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
            }, matchButton(10, 56))
        }
    }

    private fun wheelKeyAssignButton(card: LinearLayout, role: WheelZoomSettings.Role, name: String) {
        fun current(key: WheelKey? = WheelZoomSettings.key(this, role)) =
            getString(R.string.wheel_key_assign, name, key?.toString() ?: getString(R.string.wheel_key_none))
        lateinit var assign: android.widget.Button
        assign = button(current(), false) {
            val cancelled = { runOnUiThread {
                assign.text = current()
                applyPendingAppearanceRender()
            } }
            val refused = { taken: WheelZoomSettings.Role ->
                runOnUiThread {
                    assign.text = current()
                    toast(getString(R.string.wheel_key_in_use, getString(wheelKeyRoleName(taken))))
                    applyPendingAppearanceRender()
                }
            }
            val started = WheelKeyService.learn(role, cancelled, refused) { _, key -> runOnUiThread {
                assign.text = current(key)
                applyPendingAppearanceRender()
            } } ||
                // Without the service, keys that reach this window can still be learnt.
                ((role == WheelZoomSettings.Role.SIRI ||
                    role == WheelZoomSettings.Role.MEDIA_PLAY_PAUSE) && learnInWindow(role, cancelled, refused) {
                    assign.text = current(it)
                    applyPendingAppearanceRender()
                })
            if (started) assign.text = getString(R.string.wheel_key_press, name)
            else toast(getString(R.string.wheel_keys_service_off))
        }
        card.addView(assign, matchButton(10, 56))
    }

    private fun wheelKeyRoleName(role: WheelZoomSettings.Role): Int = when (role) {
        WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_mode
        WheelZoomSettings.Role.ZOOM_IN -> R.string.wheel_key_role_zoom_in
        WheelZoomSettings.Role.ZOOM_OUT -> R.string.wheel_key_role_zoom_out
        WheelZoomSettings.Role.JOYSTICK -> R.string.wheel_key_role_joystick
        WheelZoomSettings.Role.PREVIOUS -> R.string.wheel_key_role_previous
        WheelZoomSettings.Role.NEXT -> R.string.wheel_key_role_next
        WheelZoomSettings.Role.SELECT -> R.string.wheel_key_role_select
        WheelZoomSettings.Role.SIRI -> R.string.wheel_key_role_siri
        WheelZoomSettings.Role.MEDIA_PLAY_PAUSE -> R.string.wheel_key_role_media_play_pause
    }

    private class WindowKeyLearning(
        val role: WheelZoomSettings.Role,
        val cancelled: () -> Unit,
        val refused: (WheelZoomSettings.Role) -> Unit,
        val done: (WheelKey) -> Unit,
    )

    private fun learnInWindow(role: WheelZoomSettings.Role, cancelled: () -> Unit,
        refused: (WheelZoomSettings.Role) -> Unit, done: (WheelKey) -> Unit): Boolean {
        cancelKeyLearning()
        windowLearning = WindowKeyLearning(role, cancelled, refused, done)
        handler.postDelayed(endWindowLearning, WheelKeyService.LEARNING_TIMEOUT_MILLIS)
        return true
    }

    private fun cancelKeyLearning() {
        WheelKeyService.cancelLearning()
        handler.removeCallbacks(endWindowLearning)
        val cancelled = windowLearning?.cancelled
        windowLearning = null
        cancelled?.invoke()
        applyPendingAppearanceRender()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        val action = windowLearningPresses.filter(
            Triple(event.deviceId, event.keyCode, event.scanCode),
            event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0,
        ) {
            val learning = windowLearning
            if (learning == null || event.keyCode == KeyEvent.KEYCODE_BACK) return@filter WheelZoomKeys.Action.PASS
            if (learning.role == WheelZoomSettings.Role.MEDIA_PLAY_PAUSE &&
                CarPlayRemoteKeys.isProtectedNavigationKey(event.keyCode)) {
                toast(getString(R.string.wheel_media_navigation_reserved))
                return@filter WheelZoomKeys.Action.CONSUME
            }
            val enabled = when (learning.role) {
                WheelZoomSettings.Role.SIRI -> WheelZoomSettings.siriKey(this)
                WheelZoomSettings.Role.MEDIA_PLAY_PAUSE -> WheelZoomSettings.mediaPlayPause(this)
                else -> false
            }
            if (inCall(this) || !enabled) {
                cancelKeyLearning()
                return@filter WheelZoomKeys.Action.PASS
            }
            handler.removeCallbacks(endWindowLearning)
            windowLearning = null
            val key = WheelKey.of(event)
            val taken = WheelZoomSettings.conflict(this, learning.role, key)
            if (taken != null) {
                Log.i(WheelKeyService.TAG, "${learning.role} key $key refused: it is the $taken key (learnt without the service)")
                learning.refused(taken)
            } else {
                WheelZoomSettings.assign(this, learning.role, key)
                Log.i(WheelKeyService.TAG, "${learning.role} key is now $key (learnt without the service)")
                learning.done(key)
            }
            WheelZoomKeys.Action.CONSUME
        }
        return action != WheelZoomKeys.Action.PASS || super.dispatchKeyEvent(event)
    }

    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: getString(R.string.apply_and_reconnect) already does it.
    private fun reconnectIfRunning() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    // Saved without dropping CarPlay; the reconnect bar lets the driver choose when.
    private fun markReconnectNeeded() {
        DiPlaySessionService.liveRuntime?.currentConnection?.controller?.let(PendingReconnect::mark)
        refreshReconnectBar()
    }

    private fun refreshReconnectBar() {
        reconnectBar?.visibility =
            if (PendingReconnect.isPending(DiPlaySessionService.liveRuntime?.currentConnection?.controller)) View.VISIBLE else View.GONE
    }

    private fun reconnectBar(): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(RAIL_SELECTED, RAIL_SELECTED_BORDER)
        setPadding(dp(20), dp(10), dp(12), dp(10))
        addView(label(getString(R.string.settings_reconnect_pending), 16, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        addView(button(getString(R.string.settings_reconnect_now), true) {
            // No clear(): if connect() stops early (hotspot off, a permission prompt), the change is still pending.
            reconnectIfRunning()
        }, LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(12) })
        reconnectBar = this
        refreshReconnectBar()
    }

    private fun onLocationReportingChanged(enabled: Boolean) {
        if (enabled && !hasPreciseLocation()) {
            // Keep the switch off until precise location is actually granted.
            render()
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        applyLocationReporting(enabled)
    }

    private fun applyLocationReporting(enabled: Boolean) {
        if (AirPlayPersistence.loadLocationReportingEnabled(this) == enabled) return
        AirPlayPersistence.saveLocationReportingEnabled(this, enabled)
        render()
        // Location support is advertised during iAP2 identification, so both enabling and
        // disabling it require a new session. The host also refreshes its location service type.
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
        else toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        hotspotConnectGeneration++
        hotspotConnectPreparing = false
        startupHotspotCancelled = true
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        markReconnectNeeded()
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(appDialogContext()).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        appDialogBuilder().setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resolutionSettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(appDialogContext()).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = appDialogBuilder().setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.resolution_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.resolution_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showResolutionSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showResolutionSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun nightDelaySettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(appDialogContext()).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = appDialogBuilder().setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.custom_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun ambientLightThresholdControl(parent: LinearLayout) {
        val title = getString(R.string.ambient_light_threshold_title)
        fun summary(): String = getString(
            R.string.contrib_audio_home_choice_summary,
            title,
            getString(R.string.ambient_light_threshold_summary, AirPlayPersistence.loadAmbientLightThreshold(this).lux),
        )

        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(appDialogContext()).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(AirPlayPersistence.loadAmbientLightThreshold(this@DiPlayActivity).lux.toString())
            }
            fields.addView(input)
            fields.addView(label(getString(R.string.ambient_light_threshold_hint), 14, MUTED))
            val dialog = appDialogBuilder()
                .setTitle(title)
                .setView(fields)
                .setPositiveButton(getString(R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener saveThreshold@{
                    val lux = input.text.toString().trim().toIntOrNull()
                    if (lux == null || !AmbientLightThreshold.isValid(lux)) {
                        input.error = getString(R.string.ambient_light_threshold_error)
                        return@saveThreshold
                    }
                    AirPlayPersistence.saveAmbientLightThreshold(this, AmbientLightThreshold(lux))
                    control.text = summary()
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(AmbientLightThreshold.DEFAULT_LUX.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showNightModeSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    /** Where CarPlay puts its dock; between the two fixed edges it moves at once, otherwise on reconnect. */
    private fun carPlayDockControl(card: LinearLayout) {
        val docks = CarPlayDock.entries
        choice(card, getString(R.string.carplay_dock), listOf(
            getString(R.string.carplay_dock_automatic),
            getString(R.string.carplay_dock_driver_side),
            getString(R.string.carplay_dock_bottom),
        ), docks.indexOf(CarPlayDock.load(this)), reconnects = false) {
            val from = CarPlayDock.load(this)
            val to = docks[it]
            CarPlayDock.save(this, to)
            val session = DiPlaySessionService.liveRuntime?.currentConnection
            val areas = session?.display?.viewAreas
            val target = to.edge?.let { areas?.withDock(it) }
            if (CarPlayDock.movesLive(from, to) && areas != null && target != null) {
                if (session.controller.showViewArea(target)) areas.use(target)
            } else {
                markReconnectNeeded()
            }
        }
        card.addView(label(getString(R.string.carplay_dock_hint), 14, MUTED).apply { setPadding(0, 0, 0, dp(18)) })
    }

    private fun carButtonControls(parent: LinearLayout) {
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val preview = row().apply { gravity = Gravity.CENTER_VERTICAL }
        preview.addView(ImageView(this).apply {
            setImageBitmap(custom ?: BitmapFactory.decodeResource(resources, R.raw.ic_car_home))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(SURFACE, BORDER)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
        val text = column()
        text.addView(label(getString(R.string.car_button_icon), 18, TEXT, true))
        text.addView(label(getString(if (custom != null) R.string.car_button_icon_custom else R.string.default_icon), 14, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        preview.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(preview)
        parent.addView(button(getString(R.string.choose_image), false) {
            launchCarButtonImagePicker(
                openDocument = { iconDocumentPicker.launch(arrayOf("image/*")) },
                getContent = { iconPicker.launch("image/*") },
                documentPickerIsSystem = documentPickerIsSystem(),
            ).onFailure { toast(getString(R.string.this_head_unit_has_no_image_picker)) }
        }, matchButton(16, 60))
        if (custom != null) parent.addView(button(getString(R.string.default_icon), false) {
            AirPlayPersistence.clearCustomAirPlayIcon(this)
            refreshCarButton()
            carButtonSaved()
        }, matchButton(10, 60))
        val name = AirPlayPersistence.loadOemLabel(this)
        parent.addView(button("${getString(R.string.car_button_name)} · $name", false) {
            textInput(getString(R.string.car_button_name), name, secret = false) {
                AirPlayPersistence.saveOemLabel(this, it)
                refreshCarButton()
                carButtonSaved()
            }
        }, matchButton(10, 60))
        parent.addView(label(getString(R.string.car_button_description), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun carButtonSaved() {
        markReconnectNeeded()
        if (CarPlayBackgroundSession.hasSession()) toast(getString(R.string.car_button_saved_next_connection))
    }

    // Rebuilds only this card: render() would scroll the page back to the top.
    private fun refreshCarButton() {
        val card = carButtonCard ?: return
        card.removeViews(1, card.childCount - 1)
        carButtonControls(card)
        normalizeSpacing(card)
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun scheduleTimeControl(parent: LinearLayout, start: Boolean) {
        val title = getString(if (start) R.string.carplay_night_start else R.string.carplay_night_end)
        fun labelFor(minute: Int) = "$title · ${String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)}"
        val initial = AirPlayPersistence.loadCarPlayNightSchedule(this)
        val control = button(labelFor(if (start) initial.startMinute else initial.endMinute), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadCarPlayNightSchedule(this)
            val selected = if (start) current.startMinute else current.endMinute
            TimePickerDialog(this, appDialogTheme(), { _, hour, minute ->
                val updatedMinute = hour * 60 + minute
                val other = if (start) current.endMinute else current.startMinute
                if (updatedMinute == other) {
                    toast(getString(R.string.carplay_night_schedule_same_time))
                } else {
                    val updated = if (start) current.copy(startMinute = updatedMinute)
                        else current.copy(endMinute = updatedMinute)
                    AirPlayPersistence.saveCarPlayNightSchedule(this, updated)
                    control.text = labelFor(updatedMinute)
                    handler.post(::checkForAppearanceChange)
                }
            }, selected / 60, selected % 60, true).also { styleAppDialog(it) }.show()
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun connect(wireless: Boolean) {
        if (!wireless || AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) {
            hotspotConnectGeneration++
            hotspotConnectPreparing = false
            connectPrepared(wireless)
            return
        }
        if (hotspotConnectPreparing) return
        hotspotConnectPreparing = true
        val generation = ++hotspotConnectGeneration
        Thread({
            val resolved = runCatching { com.shilapi.xcertplay.network.CarHotspotProfiles.resolve(applicationContext,
                manual = { manualHotspotProfile() }, log = { DetailedDebugLog.record("hotspot-profile", it) }) }
                .onFailure { DetailedDebugLog.record("hotspot-profile", "resolution failed ${it.javaClass.simpleName}") }.getOrNull()
            runOnUiThread {
                if (generation != hotspotConnectGeneration) return@runOnUiThread
                hotspotConnectPreparing = false
                if (isFinishing || isDestroyed || AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) return@runOnUiThread
                if (resolved == null) {
                    pendingCarHotspotSetup = true
                    openConnectionSettings()
                    toast(getString(R.string.settings_hotspot_no_profile))
                } else {
                    pendingCarHotspotSetup = false
                    connectPrepared(true)
                }
            }
        }, "carplay-hotspot-profile").start()
    }

    private fun connectPrepared(wireless: Boolean) {
        DetailedDebugLog.record("home", "connect requested wireless=$wireless setupReady=${setupError == null} pendingHotspot=$pendingCarHotspotSetup")
        startupHotspotCancelled = true
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && carHotspotOff()) { DetailedDebugLog.record("home", "connect waiting reason=hotspot-off"); carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            DetailedDebugLog.record("home", "connect waiting reason=choose-phone")
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            DetailedDebugLog.record("home", "connect waiting reason=notification-permission")
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        DetailedDebugLog.record("home", "open projection session=${CarPlayBackgroundSession.hasSession()}")
        OwnScreenStart.start(this, Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            DetailedDebugLog.record("home", "connect waiting reason=bluetooth-permission")
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            // Android here has no Bluetooth for apps at all, so there is no switch to send the driver to.
            pendingWireless = false
            appDialogBuilder().setTitle(getString(R.string.wireless_carplay_2))
                .setMessage(getString(R.string.bluetooth_cannot_open_connections))
                .setPositiveButton(getString(R.string.got_it), null).show(); return
        }
        if (!adapter.isEnabled) {
            // Back from Bluetooth settings and still off: on some head units the switch will not stay on.
            val message = getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first) +
                if (bluetoothSettingsVisited) "\n\n" + getString(R.string.bluetooth_will_not_stay_on_hint) else ""
            appDialogBuilder().setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(message)
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ ->
                    bluetoothSettingsVisited = true
                    openBluetoothSettings()
                }
                .setNegativeButton(getString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            appDialogBuilder().setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openBluetoothSettings() }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        appDialogBuilder().setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openBluetoothSettings() }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        appDialogBuilder().setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        appDialogBuilder().setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        val wired = HomeCardTransport.wired(running, AirPlayPersistence.loadWirelessEnabled(this))
        homeCards?.let { cards ->
            val activeStatus = if (wired) cards.usbStatus else cards.status
            val activeButton = if (wired) cards.usbConnect else cards.connect
            if (status !== activeStatus || connectButton !== activeButton) {
                cards.status.text = getString(R.string.ready_when_you_are)
                cards.usbStatus.text = getString(R.string.connect_your_iphone_with_a_usb_cable)
                cards.connect.text = getString(R.string.connect_phone)
                cards.usbConnect.text = getString(R.string.connect_with_usb)
                cards.connect.isEnabled = true
                cards.usbConnect.isEnabled = true
                status = activeStatus
                connectButton = activeButton
                lastRunning = null
            }
        }
        homeConnectionHint?.visibility = if (wired) View.GONE else View.VISIBLE
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone)
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
        refreshReconnectBar()
        refreshReadiness()
    }


    private fun reportFileName() = "Apple CarPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun saveReportToDownloads() {
        if (exportInProgress) return
        if (DiagnosticExportStore.needsStoragePermission(this)) {
            runCatching { reportStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                .onFailure { exportDiagnostics() }
        } else exportDiagnostics()
    }

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        runCatching { export.launch(reportFileName()) }.onFailure { exportDiagnostics() }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = diagnosticReportText(appContext)
                val savedReport = if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report)
                savedReport to report
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val (savedReport, report) = result.getOrThrow()
                    appDialogBuilder().setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(when {
                            savedReport.savedInApp -> getString(R.string.diagnostic_report_saved_in_app)
                            savedReport.savedPath != null -> getString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath)
                            uri == null -> "Downloads/Apple CarPlay/$fileName"
                            else -> getString(R.string.your_report_was_saved_to_the_selected_location)
                        })
                        .setPositiveButton(getString(R.string.view_diagnostic_report)) { _, _ -> showDiagnosticReport(report) }
                        .setNegativeButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedReport.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { showDiagnosticReport(report) }
                        }.show()
                } else {
                    appDialogBuilder().setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, "diplay-export").start()
    }

    /** The text of a diagnostic report. It reads the session logs, so call it off the main thread. */
    private fun diagnosticReportText(appContext: Context): String = buildString {
        appendLine("Apple CarPlay ${version()} · private beta diagnostic report")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
        AirPlayPersistence.loadUsbChargingCurrent(appContext).let {
            appendLine("USB charging offer: ${it.key} (${it.milliAmps} mA)")
        }
        appendLine("Authentication: local experimental beta identity; no remote fallback")
        appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
        appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
        appendLine("Media-key audio focus: ${if (AirPlayPersistence.loadDisableMediaKeyAudioFocus(appContext)) "disabled" else "enabled"}")
        appendLine("MTK decoder tuning preference: ${if (AirPlayPersistence.loadMtkDecoderTuning(appContext)) "enabled" else "disabled"}")
        appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
        appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
        appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
        appendLine(com.shilapi.xcertplay.BluetoothDiagnosticSnapshot.report(appContext))
        appendLine(PhoneWakeDiagnostics.report(appContext))
        appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
        appendLine()
        appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
        appendLine(DisplayDiagnosticSnapshot.report(appContext))
        appendLine()
        appendLine("--- Last received boot and app-launch result ---")
        appendLine(StartupDiagnosticSnapshot.report(appContext))
        appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
            "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
        appendLine("Car hotspot control: autoEnable=${CarHotspotSettings.enabled(appContext)} " +
            "writeSettings=${CarHotspotTethering.permitted(appContext)} " +
            "hotspotOn=${runCatching { com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) }.getOrNull()}")
        appendLine(WheelKeyService.report(appContext))
        WheelKeyLog.report(WheelKeyService.connected()).forEach { appendLine(it) }
        appendLine(FlymeWheelIntegration.report())
        appendLine()
        appendLine()
        appendLine("--- Recent own-app process exits (Android 11+) ---")
        appendLine(ProcessExitDiagnostics.report(appContext))
        appendLine()
        appendLine("--- Last crash recorded by Apple CarPlay ---")
        appendLine(CrashRecord.report(appContext))
        appendLine()
        appendLine("--- Detailed connection / focus / wheel event recording ---")
        appendLine(DetailedDebugLog.report(appContext))
        appendLine()
        for (name in SessionLogFile.REPORT_NAMES) {
            val file = File(appContext.filesDir, "logs/$name")
            if (file.isFile) {
                appendLine("--- $name ---")
                file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
            }
        }
    }

    private fun showDiagnosticReport(report: String) {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.diagnostic_report_copy_hint), 14, MUTED))
        body.addView(label(report, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        appDialogBuilder().setTitle(getString(R.string.view_diagnostic_report))
            .setView(ScrollView(appDialogContext()).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null).show()
    }
    private fun permissionHelp(title: String, body: String) {
        appDialogBuilder().setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            val pcm = assets.open("navigation_test.pcm").use { it.readBytes() }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("Apple CarPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(getString(R.string.audio_stream_unavailable, streamType, state))
            return
        }
        Log.i("Apple CarPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        handler.postDelayed(stop, 4500)
    }

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        target.setTextColor(if (selected) ON_ACCENT else TEXT)
        target.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            buttonShape(if (selected) ACCENT else SURFACE, if (selected) ACCENT else BORDER, target.height),
            null
        )
    }

    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 3
            setPadding(0, dp(8), 0, dp(8))
        }
        for (i in 0..20) {
            val btn = Button(this).apply {
                text = i.toString()
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                stateListAnimator = null
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return grid
    }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        searchIndexSink?.add(title)
        // About contains plain text, so it needs the section card rather than row-owned backgrounds.
        val openGroup = page == "settings" && settingsCategory != SettingsCategory.OVERVIEW &&
            settingsCategory != SettingsCategory.ABOUT
        val card = if (openGroup) column() else card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(CONTROL_GAP_DP)) }
        if (icon != null && !openGroup) heading.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, TEXT, true,
            centreGlyphs = !openGroup && icon != null && usesChineseGlyphAlignment(resources.configuration.locales[0].language, title)).apply {
                FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.SECTION_TITLE, isCompactLayout)
                if (openGroup) setTextColor(palette.supportingText)
            },
            LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        if (page == "settings") normalizeSpacing(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
    }
    private fun audioFocusControls(parent: LinearLayout) {
        toggle(parent, getString(R.string.settings_disable_media_key_audio_focus),
            getString(R.string.settings_disable_media_key_audio_focus_description),
            AirPlayPersistence.loadDisableMediaKeyAudioFocus(this)) {
            AirPlayPersistence.saveDisableMediaKeyAudioFocus(this, it)
            markReconnectNeeded()
        }
        val enabled = AirPlayPersistence.loadAudioFocusEnabled(this)
        val dependent = column().apply { visibility = if (enabled) View.VISIBLE else View.GONE }
        toggle(parent, getString(R.string.contrib_audio_home_toggle_audio_focus),
            getString(R.string.contrib_audio_home_toggle_audio_focus_desc), enabled) {
            AirPlayPersistence.saveAudioFocusEnabled(this, it)
            markReconnectNeeded()
            dependent.visibility = if (it) View.VISIBLE else View.GONE
        }
        toggle(dependent, getString(R.string.audio_focus_auto_yield), getString(R.string.audio_focus_auto_yield_desc),
            AirPlayPersistence.loadAudioFocusAutoYield(this)) {
            AirPlayPersistence.saveAudioFocusAutoYield(this, it)
            markReconnectNeeded()
        }
        parent.addView(dependent)
    }

    private fun filteredSection(
        parent: LinearLayout,
        key: SettingsSection,
        title: String,
        icon: Int? = null,
        build: (LinearLayout) -> Unit,
    ) {
        if (settingsSectionFilter?.contains(key) == false) return
        section(parent, title, icon, build)
    }
    // Routing changes affect the live cluster surface as soon as the host resumes.
    // Ask before saving them instead of promising to defer only part of the change.
    private fun reconnectingToggle(parent: LinearLayout, title: String, description: String,
        value: Boolean, save: (Boolean) -> Unit) {
        toggle(parent, title, description, value) { selected ->
            if (!CarPlayBackgroundSession.hasSession()) {
                save(selected)
            } else {
                appDialogBuilder().setTitle(title).setMessage(description)
                    .setPositiveButton(R.string.apply_and_reconnect) { _, _ ->
                        save(selected)
                        reconnectIfRunning()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> render() }
                    .setOnCancelListener { render() }
                    .show()
            }
        }
    }

    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, enabled: Boolean = true, save: (Boolean) -> Unit): Switch {
        searchIndexSink?.add(title)
        val result = SettingsWidgets.createSwitchRow(
            context = this,
            label = title,
            description = description,
            checked = value,
            theme = SettingsTheme.card(palette),
            contentDescription = title,
            enabled = enabled,
            onChanged = save,
        )
        parent.addView(result.rowView)
        return result.switch
    }
    private fun experimentalAudioControls(card: LinearLayout) {
        val levels = (-12..12).toList()
        fun update(transform: (com.shilapi.xcertplay.media.ExperimentalAudioConfig) -> com.shilapi.xcertplay.media.ExperimentalAudioConfig) {
            AirPlayPersistence.saveExperimentalAudio(this, transform(AirPlayPersistence.loadExperimentalAudio(this)))
            markReconnectNeeded()
        }
        val config = AirPlayPersistence.loadExperimentalAudio(this)
        fun gain(title: Int, description: Int, valueTitle: Int, enabled: Boolean, value: Int,
            enable: (Boolean) -> Unit, save: (Int) -> Unit) {
            toggle(card, getString(title), getString(description), enabled) {
                enable(it)
                render()
            }
            if (enabled) choice(card, getString(valueTitle),
                levels.map { if (it > 0) "+$it dB" else "$it dB" }, levels.indexOf(value),
                reconnects = false) { save(levels[it]) }
        }
        gain(R.string.settings_mic_gain, R.string.settings_mic_gain_description,
            R.string.settings_mic_gain_db, config.microphoneEnabled, config.microphoneDb,
            { enabled -> update { it.copy(microphoneEnabled = enabled) } },
            { value -> update { it.copy(microphoneDb = value) } })
        gain(R.string.settings_nav_gain, R.string.settings_nav_gain_description,
            R.string.settings_nav_gain_db, config.navigationEnabled, config.navigationDb,
            { enabled -> update { it.copy(navigationEnabled = enabled) } },
            { value -> update { it.copy(navigationDb = value) } })
        gain(R.string.settings_call_gain, R.string.settings_call_gain_description,
            R.string.settings_call_gain_db, config.callEnabled, config.callDb,
            { enabled -> update { it.copy(callEnabled = enabled) } },
            { value -> update { it.copy(callDb = value) } })
        toggle(card, getString(R.string.settings_audio_recovery), getString(R.string.settings_audio_recovery_description),
            config.outputRecovery) { enabled -> update { it.copy(outputRecovery = enabled) } }
        card.addView(label(getString(R.string.settings_audio_gain_hint), 14, MUTED))
    }

    private fun musicBufferChoice(card: LinearLayout, wireless: Boolean) {
        val buffer = com.shilapi.xcertplay.media.MediaAudioBuffer
        val presets = if (wireless) buffer.wirelessPresets else buffer.wiredPresets
        val labels = presets.map { millis ->
            when (millis) {
                buffer.DEFAULT_MILLIS -> getString(R.string.s_300_ms_default)
                buffer.MOST_STABLE_MILLIS -> getString(R.string.s_1000_ms_most_stable)
                else -> getString(R.string.settings_music_buffer_ms, millis)
            }
        }
        val title = getString(if (wireless) R.string.settings_music_buffer_wireless else R.string.settings_music_buffer_wired)
        val sessionTransport = AirPlayPersistence.loadWirelessEnabled(this) == wireless
        choice(card, title, labels,
            presets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this, wireless)).coerceAtLeast(0),
            reconnects = sessionTransport) {
            AirPlayPersistence.saveMediaBufferMillis(this, wireless, presets[it])
        }
    }

    // [announcesReconnect] labels a choice whose [save] reconnects by itself.
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true,
        announcesReconnect: Boolean = reconnects, enabled: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}.apply { isEnabled = enabled }
        button.setOnClickListener {
            var pendingSelection = selection
            appDialogBuilder().setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (announcesReconnect && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    private fun card() = column().apply { background = FlymeAutoStyle.card(this@DiPlayActivity, palette); setPadding(dp(FlymeAutoStyle.CARD_PADDING_DP), dp(FlymeAutoStyle.CARD_PADDING_DP), dp(FlymeAutoStyle.CARD_PADDING_DP), dp(FlymeAutoStyle.CARD_PADDING_DP)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private class SettingsSearchEditText(context: android.content.Context) : EditText(context) {
        private val glyphBounds = android.graphics.Rect()
        private var measuredHint: String? = null
        private var measuredHintHeight = -1
        private var measuredHintTextSize = -1f
        private var measuredHintBaseline = 0

        override fun onDraw(canvas: android.graphics.Canvas) {
            val value: CharSequence = if (text.isNullOrEmpty()) hint ?: "" else text
            val drawingBaseline = if (text.isNullOrEmpty() && value.isNotEmpty()) hintBaseline(value) else baseline
            val offset = if (baseline >= 0) {
                val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                contentCentre - alignedTextCentreY(this, value, glyphBounds, drawingBaseline)
            } else 0f
            // Text, selection and cursor move together.
            val checkpoint = canvas.save()
            canvas.translate(0f, offset)
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }

        /**
         * TextView.baseline describes the editable layout, even while it draws a hint, and a hint in
         * a fallback font can sit differently. Measured once per hint, height and text size rather
         * than on every frame of the blinking cursor.
         */
        private fun hintBaseline(value: CharSequence): Int {
            if (value.toString() != measuredHint || height != measuredHintHeight || paint.textSize != measuredHintTextSize) {
                // The editor scrolls horizontally rather than wrapping: lay the hint out on one line.
                val hintWidth = maxOf(
                    (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1),
                    kotlin.math.ceil(android.text.Layout.getDesiredWidth(value, paint).toDouble()).toInt(),
                )
                val hintLayout = android.text.StaticLayout.Builder.obtain(value, 0, value.length, paint, hintWidth)
                    .setMaxLines(1).setIncludePad(includeFontPadding).apply {
                        setUseLineSpacingFromFallbacks(isFallbackLineSpacing)
                    }.build()
                measuredHintBaseline = extendedPaddingTop +
                    ((height - extendedPaddingTop - extendedPaddingBottom - hintLayout.height).coerceAtLeast(0) / 2) +
                    hintLayout.getLineBaseline(0)
                measuredHint = value.toString()
                measuredHintHeight = height
                measuredHintTextSize = paint.textSize
            }
            return measuredHintBaseline
        }
    }

    private class HeaderTitleView(context: android.content.Context) : TextView(context) {
        private val glyphBounds = android.graphics.Rect()

        override fun onDraw(canvas: android.graphics.Canvas) {
            val value = text.toString()
            paint.getTextBounds(value, 0, value.length, glyphBounds)
            val checkpoint = canvas.save()
            if (layout?.lineCount == 1 && !glyphBounds.isEmpty && baseline >= 0) {
                val textCentre = alignedTextCentreY(this, value, glyphBounds)
                val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                canvas.translate(0f, contentCentre - textCentre)
            }
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false, centreGlyphs: Boolean = false) =
        (if (centreGlyphs) HeaderTitleView(this) else TextView(this)).apply {
        text = value
        FlymeAutoStyle.text(this, when {
            size >= 28 -> FlymeAutoStyle.TextRole.PAGE_TITLE
            size >= 22 && bold -> FlymeAutoStyle.TextRole.LABEL
            size >= 18 -> FlymeAutoStyle.TextRole.BODY
            else -> FlymeAutoStyle.TextRole.SECONDARY
        }, compact = isCompactLayout)
        setTextColor(if (color == ACCENT) palette.accentText else color)
        gravity = Gravity.CENTER_VERTICAL
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun headerIconSize(compact: Boolean) = dp(if (compact) 20 else 24)

    private fun headerButton(title: String, icon: Int, compact: Boolean, click: () -> Unit) = button(title, false, click).apply {
        alignVisibleGlyphs = false
        if (compact) textSize = FlymeAutoStyle.NAVIGATION_TEXT_SP
        includeFontPadding = false
        setTextColor(TEXT)
        setSingleLine(true)
        gravity = Gravity.CENTER
        val iconSize = headerIconSize(compact)
        val drawable = getDrawable(icon)?.mutate()?.apply {
            setTint(TEXT)
            setBounds(0, 0, iconSize, iconSize)
        }
        setCompoundDrawablesRelative(drawable, null, null, null)
        compoundDrawablePadding = dp(8)
        doOnLayout {
            // Use the same text alignment reference as other buttons in this locale.
            if (baseline >= 0 && text.isNotEmpty()) {
                val glyphCentre = textAlignmentCentreY()
                val drawableCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                val offset = (glyphCentre - drawableCentre).roundToInt()
                drawable?.setBounds(0, offset, iconSize, iconSize + offset)
                // Move the aligned icon/text group to the button's visual centre.
                contentOffsetY = drawableCentre - glyphCentre
                invalidate()
            }
        }
    }

    private fun layoutCornerRadius(): Float {
        val compact = isCompactLayout && (page != "settings" || !SettingsLayoutPreferences.isActive(this))
        return FlymeAutoStyle.radius(this, dp(if (compact) FlymeAutoStyle.COMPACT_CONTROL_HEIGHT_DP else FlymeAutoStyle.CONTROL_HEIGHT_DP))
    }

    private fun buttonShape(color: Int, stroke: Int, heightPx: Int = 0) = rounded(color, stroke).apply {
        cornerRadius = if (heightPx > 0) FlymeAutoStyle.radius(this@DiPlayActivity, heightPx) else layoutCornerRadius()
    }

    private fun buttonFocusRing() = focusRing(
        (layoutCornerRadius() / resources.displayMetrics.density).roundToInt(),
    )

    private fun button(title: String, primary: Boolean, click: () -> Unit) = SettingButton(this, MUTED, ACCENT).apply {
        if (page == "settings") FlymeAutoStyle.settingsButton(this, palette, primary, compact = isCompactLayout)
        else FlymeAutoStyle.button(this, palette, primary, compact = isCompactLayout)
        compoundDrawablePadding = dp(FlymeAutoStyle.CONTROL_GAP_DP)
        text = title
        searchIndexSink?.add(title.substringBefore(VALUE_SEPARATOR))
        setOnClickListener { click() }
    }

    private fun toolButton(title: Int, description: Int, icon: Int, click: () -> Unit) =
        button(getString(title) + VALUE_SEPARATOR + getString(description), false, click).apply {
            val size = dp(FlymeAutoStyle.INPUT_ACTION_DP / 2)
            val drawable = getDrawable(icon)?.mutate()?.apply {
                setTint(palette.secondaryText)
                setBounds(0, 0, size, size)
            }
            setCompoundDrawablesRelative(drawable, null, compoundDrawablesRelative[2], null)
        }

    private fun iconButton(icon: Int, description: String, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(ACCENT)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        stateListAnimator = null
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            buttonShape(BUTTON, BORDER),
            null,
        )
        foreground = buttonFocusRing()
        setOnClickListener { click() }
    }

    // For an action whose label happens to contain the separator, such as "Turn on … · ADB".
    private fun actionButton(title: String, primary: Boolean, click: () -> Unit) =
        button(title, primary, click).apply { action = true; text = title }

    /**
     * Text in the "Title · Value" form opens a choice, so it reads as a setting row:
     * start-aligned, muted value, chevron. Any other text stays a centred action button.
     * The plain text is unchanged, so callers and tests keep matching "Title · Value".
     */
    internal class SettingButton(
        context: android.content.Context,
        private val muted: Int,
        private val accent: Int,
    ) : Button(context) {
        var action = false
        var contentOffsetY = 0f
        var alignVisibleGlyphs = true
        private val glyphBounds = android.graphics.Rect()
        private var installingTransformation = false

        fun textAlignmentCentreY(): Float {
            return alignedTextCentreY(this, text, glyphBounds)
        }

        override fun setLayoutParams(params: ViewGroup.LayoutParams) {
            if (params.height > 0) {
                minHeight = maxOf(minHeight, params.height)
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            super.setLayoutParams(params)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            var offset = contentOffsetY
            if (alignVisibleGlyphs) {
                offset = 0f
                // Wrapped labels keep Android's multiline layout. Measure single-line labels
                // on every draw so changing a setting value also updates its visual centre.
                if (layout?.lineCount == 1 && baseline >= 0) {
                    val textCentre = textAlignmentCentreY()
                    val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                    offset = contentCentre - textCentre
                }
                // The chevron is already centred by TextView; compensate for the text shift.
                compoundDrawablesRelative.filterNotNull().forEach { drawable ->
                    val bounds = drawable.bounds
                    val top = -offset.roundToInt()
                    drawable.setBounds(bounds.left, top, bounds.right, top + bounds.height())
                }
            }
            val checkpoint = canvas.save()
            canvas.translate(0f, offset)
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }

        override fun setText(text: CharSequence?, type: BufferType?) {
            if (installingTransformation) {
                super.setText(text, type)
                return
            }
            val split = if (action) -1 else text?.indexOf(VALUE_SEPARATOR) ?: -1
            if (text == null || split <= 0) {
                super.setText(text, type)
                gravity = Gravity.CENTER
                setCompoundDrawablesRelative(null, null, null, null)
                return
            }
            FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.LABEL)
            minHeight = FlymeAutoStyle.dp(context, if (FlymeAutoStyle.compact(context))
                FlymeAutoStyle.COMPACT_SETTING_ROW_HEIGHT_DP else FlymeAutoStyle.SETTING_ROW_HEIGHT_DP)
            val inset = FlymeAutoStyle.dp(context, FlymeAutoStyle.INPUT_PADDING_DP)
            setPaddingRelative(inset, inset, inset, inset)
            // Keep the source text for search and accessibility; transform only its presentation.
            super.setText(text, BufferType.SPANNABLE)
            if (transformationMethod !is SettingsValueTransformation) {
                installingTransformation = true
                try { transformationMethod = SettingsValueTransformation(muted) }
                finally { installingTransformation = false }
            }
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                context.getDrawable(R.drawable.ic_dp_chevron)?.mutate()?.apply { setTint(accent) }, null)
        }
    }
    private class SettingsValueTransformation(private val muted: Int) : android.text.method.TransformationMethod {
        override fun getTransformation(source: CharSequence, view: View): CharSequence {
            if ((view as? SettingButton)?.action == true) return source
            val at = source.indexOf(VALUE_SEPARATOR)
            if (at <= 0) return source
            // TextView keeps span/layout offsets into the source. Keep the transformation
            // length unchanged on API 28: newline plus two zero-width separators.
            return android.text.SpannableString(source.toString().replaceRange(at, at + VALUE_SEPARATOR.length, "\n\u200B\u200B")).apply {
                setSpan(android.text.style.ForegroundColorSpan(muted), at + 1, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.RelativeSizeSpan(if (FlymeAutoStyle.compact(view.context)) 18f / 20f else 24f / 26f),
                    at + 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.TypefaceSpan("sans-serif"), at + 1, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        override fun onFocusChanged(view: View, sourceText: CharSequence, focused: Boolean,
            direction: Int, previouslyFocusedRect: android.graphics.Rect?) = Unit
    }

    // Remote and D-pad users need to see where they are; touch mode never shows it.
    private fun focusRing(radiusDp: Int = 20) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(radiusDp).toFloat(); setStroke(dp(FlymeAutoStyle.FOCUS_STROKE_DP), FOCUS_RING)
        })
    }

    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = layoutCornerRadius() * 1.35f; setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)); tag = SPACER }

    /**
     * One vertical rhythm for every card, whatever margins each control builder chose:
     * buttons are one height with one gap, a note under a button closes its group with a larger gap,
     * and the last item adds nothing to the card's own padding.
     */
    private fun normalizeSpacing(container: LinearLayout, isCard: Boolean = true) {
        if (container.orientation != LinearLayout.VERTICAL) return
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i).tag == SPACER) container.removeViewAt(i)
        }
        fun isNote(view: View?) = view is TextView && view !is Button
        fun isToggleRow(view: View?) = view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL &&
            (0 until view.childCount).any { view.getChildAt(it) is Switch }
        fun isControl(view: View?) = view is Button || isToggleRow(view)
        var previous: View? = null
        var last: View? = null
        var noteFollowsButton = false
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.visibility == View.GONE) continue
            val next = (i + 1 until container.childCount).asSequence()
                .map { container.getChildAt(it) }.firstOrNull { it.visibility != View.GONE }
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            when {
                child is Button -> {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    child.minHeight = maxOf(child.minHeight, dp(CONTROL_HEIGHT_DP)); lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else CONTROL_GAP_DP)
                }
                isToggleRow(child) -> {
                    val inset = dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP)
                    child.setPadding(child.paddingLeft, inset, child.paddingRight, inset)
                    lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else CONTROL_GAP_DP)
                }
                child is TextView -> {
                    if (!isNote(previous)) noteFollowsButton = isControl(previous)
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    // Lines of one note stay together; the gap after the last line closes the group.
                    lp.bottomMargin = dp(when {
                        isNote(next) -> NOTE_LINE_GAP_DP
                        noteFollowsButton -> GROUP_GAP_DP
                        else -> CONTROL_GAP_DP
                    })
                }
                child is LinearLayout && child.orientation == LinearLayout.VERTICAL -> {
                    normalizeSpacing(child, isCard = false)
                    lp.topMargin = 0; lp.bottomMargin = 0
                }
                // Any other row (an icon preview, a status line) gets the same gap; the card heading keeps its padding.
                i > 0 || !isCard -> { lp.topMargin = 0; lp.bottomMargin = dp(CONTROL_GAP_DP) }
            }
            child.layoutParams = lp
            if (child.visibility != View.GONE) last = child
            previous = child
        }
        // A nested group keeps its trailing gap so it does not touch the control after it.
        if (isCard && !(last is LinearLayout && last.orientation == LinearLayout.VERTICAL)) {
            (last?.layoutParams as? LinearLayout.LayoutParams)?.let { it.bottomMargin = 0; last.layoutParams = it }
        }
    }
    // Rounded, not truncated: below 160 dpi dp(1) became 0 and every border vanished.
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun startAppearanceUpdates() {
        appearanceUpdatesResumed = true
        appearanceObserverRemoval?.invoke()
        appearanceObserverRemoval = AppAppearanceRuntime.observeHost {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                checkForAppearanceChange()
            } else {
                handler.post { if (appearanceUpdatesResumed) checkForAppearanceChange() }
            }
        }
        handler.removeCallbacks(appearancePoll)
        handler.post(appearancePoll)
        applyPendingAppearanceRender()
    }

    private fun checkForAppearanceChange() {
        if (refreshAppearance()) {
            requestAppearanceRender()
        }
    }

    private fun requestAppearanceRender() {
        appearanceRenderPending = true
        if (!appearanceUpdatesResumed || shouldDeferAppearanceRender(
                windowKeyLearning = windowLearning != null,
                serviceKeyLearning = WheelKeyService.isLearning(),
            )
        ) return
        appearanceRenderPending = false
        render(appearanceOnly = true)
    }

    private fun applyPendingAppearanceRender() {
        if (!appearanceRenderPending) return
        handler.post {
            if (appearanceRenderPending && appearanceUpdatesResumed &&
                !shouldDeferAppearanceRender(
                    windowKeyLearning = windowLearning != null,
                    serviceKeyLearning = WheelKeyService.isLearning(),
                )
            ) {
                requestAppearanceRender()
            }
        }
    }

    private fun refreshAppearance(): Boolean {
        val resolved = resolveAppNightNow()
        if (resolved == appNight && palette === DiPlayPalette.of(resolved)) return false
        appNight = resolved
        palette = DiPlayPalette.of(resolved)
        return true
    }

    // Remember the current system palette for the next starting window on API 33+.
    private fun rememberLaunchAppearance() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val light = !resolveAppNightNow()
        splashScreen.setSplashScreenTheme(if (light) R.style.Theme_Xcertplay_Light else R.style.Theme_Xcertplay)
    }

    private fun applyWindowAppearance() {
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.systemBarIconsAreDark
            isAppearanceLightNavigationBars = palette.systemBarIconsAreDark
        }
        applyFullscreenMode()
    }

    private val BG get() = palette.background
    private val SURFACE get() = palette.surface
    private val BUTTON get() = palette.button
    private val BORDER get() = palette.outline
    private val ACCENT get() = palette.accent
    private val ON_ACCENT get() = palette.onAccent
    private val RAIL_SELECTED get() = palette.railSelected
    private val RAIL_SELECTED_BORDER get() = palette.railSelectedOutline
    private val TEXT get() = palette.primaryText
    private val MUTED get() = palette.secondaryText
    private val WARNING get() = palette.warning
    private val READY get() = palette.success
    private val RIPPLE get() = palette.ripple
    private val FOCUS_RING get() = palette.focusRing

    companion object {
        private val INTENT_PAGES=setOf("home","settings","connection","setup","wireless-recovery")
        private fun requestedPage(intent:Intent):String?=intent.getStringExtra("page")?.takeIf{it in INTENT_PAGES}

        private fun alignedTextCentreY(
            view: TextView,
            text: CharSequence,
            bounds: android.graphics.Rect,
            drawingBaseline: Int = view.baseline,
        ): Float {
            if (usesChineseGlyphAlignment(view.resources.configuration.locales[0].language, text)) {
                val value = text.toString()
                view.paint.getTextBounds(value, 0, value.length, bounds)
                return drawingBaseline + (bounds.top + bounds.bottom) / 2f
            }
            // Keep the baseline stable when labels contain descenders or accents.
            val metrics = view.paint.fontMetrics
            return drawingBaseline + (metrics.ascent + metrics.descent) / 2f
        }

        private fun usesChineseGlyphAlignment(language: String, text: CharSequence): Boolean {
            return language == "zh" && text.isNotBlank() &&
                text.none { it in 'A'..'Z' || it in 'a'..'z' }
        }

        internal fun isLauncherIntent(intent: Intent): Boolean =
            intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
                !intent.hasExtra("page")

        private const val ADB_KEY_SAVE_WAIT_MILLIS = 500L
        private const val APPEARANCE_POLL_MILLIS = 2_000L
        private const val SETTINGS_SEARCH_BUTTON_TAG = "settings_search_button"
        private const val SETTINGS_HEADER_COMPACT_GAP_DP = 8
        private const val SETTINGS_HEADER_GAP_DP = 12
        private const val SETTINGS_SEARCH_RESULTS_GAP_DP = 6
        private const val SETTINGS_SEARCH_RESULTS_SHORT_HEIGHT_DP = 200
        private const val SETTINGS_SEARCH_RESULTS_HEIGHT_DP = 380
        private const val VALUE_SEPARATOR = " · "
        private const val SEARCH_HIGHLIGHT_MILLIS = 900L
        private const val SPACER = "spacer"
        private const val CONTROL_HEIGHT_DP = FlymeAutoStyle.CONTROL_HEIGHT_DP
        private const val CONTROL_GAP_DP = FlymeAutoStyle.CONTROL_GAP_DP
        private const val GROUP_GAP_DP = 20
        private const val NOTE_LINE_GAP_DP = 4
        private const val NOTE_ATTACH_GAP_DP = 6
        private const val SETTINGS_BLOCK_GAP_DP = FlymeAutoStyle.BLOCK_GAP_DP
    }
}
