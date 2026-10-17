package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import com.shilapi.xcertplay.host.R

/** A test never outlives this screen or silently replaces a live CarPlay session. */
class WheelTestActivity : Activity(), AppAppearanceOwner {
    override var currentAppNight: Boolean = true
        private set
    private var appearanceObserverRemoval: (() -> Unit)? = null
    private lateinit var controller: WheelTestController
    private lateinit var views: WheelTestLayout.Views
    private val appearanceHandler = Handler(Looper.getMainLooper())
    private val appearancePoll = object : Runnable {
        override fun run() {
            if (!foreground) return
            checkAppearance()
            appearanceHandler.postDelayed(this, 5_000L)
        }
    }
    private var foreground = false
    private var startGeneration = 0
    private var disconnectDialog: AlertDialog? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller = WheelTestController(this, ::showState)
        renderAppearance()
    }

    private fun renderAppearance() {
        val scrollY = if (::views.isInitialized) views.root.scrollY else 0
        currentAppNight = resolveAppNightNow()
        val palette = DiPlayPalette.of(currentAppNight)
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.systemBarIconsAreDark
            isAppearanceLightNavigationBars = palette.systemBarIconsAreDark
        }
        views = WheelTestLayout(this, palette).build(DetailedDebugLog.enabled(this), { enabled ->
            DetailedDebugLog.setEnabled(this, enabled)
            Toast.makeText(this, if (enabled) R.string.settings_detailed_debug_started else R.string.settings_detailed_debug_stopped, Toast.LENGTH_SHORT).show()
        }, ::action)
        ViewCompat.setOnApplyWindowInsetsListener(views.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(views.root)
        showState(controller.snapshot())
        val root = views.root
        root.post { root.scrollTo(0, scrollY) }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        appearanceObserverRemoval?.invoke()
        appearanceObserverRemoval = AppAppearanceRuntime.observeHost {
            runOnUiThread {
                checkAppearance()
            }
        }
        appearanceHandler.removeCallbacks(appearancePoll)
        appearanceHandler.postDelayed(appearancePoll, 5_000L)
        showState(controller.snapshot())
    }

    private fun checkAppearance() {
        if (foreground && !isFinishing && !isDestroyed && currentAppNight != resolveAppNightNow()) renderAppearance()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkAppearance()
    }

    private fun action(action: WheelTestLayout.Action) {
        when (action) {
            WheelTestLayout.Action.ACQUIRE -> acquire()
            WheelTestLayout.Action.RELEASE -> { startGeneration++; controller.stop() }
            WheelTestLayout.Action.BACK -> finish()
            WheelTestLayout.Action.PLAY -> controller.simulate(1)
            WheelTestLayout.Action.PAUSE -> controller.simulate(2)
            WheelTestLayout.Action.TOGGLE -> controller.simulate(3)
            WheelTestLayout.Action.NEXT -> controller.simulate(4)
            WheelTestLayout.Action.PREVIOUS -> controller.simulate(5)
            WheelTestLayout.Action.SIRI -> controller.simulate(6)
        }
    }

    private fun acquire() {
        if (!foreground) return
        if (!CarPlayBackgroundSession.hasSession()) { controller.start(); return }
        if (disconnectDialog?.isShowing == true) return
        disconnectDialog = appDialogBuilder().setTitle(R.string.wheel_test_title)
            .setMessage(R.string.wheel_test_disconnect_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wheel_test_disconnect_and_start) { _, _ ->
                val expected = ++startGeneration
                views.acquire.isEnabled = false
                CarPlayBackgroundSession.stop {
                    runOnUiThread {
                        if (!isFinishing && foreground && startGeneration == expected && !CarPlayBackgroundSession.hasSession()) {
                            controller.start()
                        } else if (foreground && startGeneration == expected) {
                            showState(controller.snapshot())
                        }
                    }
                }
            }.show()
    }

    private fun showState(state: WheelTestController.State) {
        if (!::views.isInitialized) return
        views.acquire.isEnabled = !state.running
        views.release.isEnabled = state.running
        val androidFocus = if (!state.running) getString(R.string.wheel_test_stopped)
            else if (state.focusChange == AudioManager.AUDIOFOCUS_GAIN ||
                (state.focusChange == null && state.requestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)) getString(R.string.wheel_test_granted)
            else getString(R.string.wheel_test_result, state.requestResult?.toString() ?: "—", state.focusChange?.toString() ?: "—")
        views.status.text = getString(R.string.wheel_test_status,
            getString(if (state.running) R.string.wheel_test_running else R.string.wheel_test_stopped),
            androidFocus, getString(if (!state.running) R.string.wheel_test_stopped else if (state.easOwned) R.string.wheel_test_granted else R.string.wheel_test_other_focus))
        state.event?.let { event ->
            val title = when (event.media) {
                1 -> getString(R.string.wheel_test_play_label)
                2 -> getString(R.string.wheel_test_pause_label)
                3 -> getString(R.string.wheel_test_toggle_label)
                4 -> getString(R.string.wheel_test_next_label)
                5 -> getString(R.string.wheel_test_previous_label)
                6 -> if (event.raw != null) getString(R.string.wheel_test_voice_key_code, event.raw)
                    else getString(R.string.wheel_test_siri_label)
                else -> getString(R.string.wheel_test_raw_label, event.raw ?: -1)
            }
            views.event.text = getString(R.string.wheel_test_event, title,
                getString(if (event.simulated) R.string.wheel_test_simulated else R.string.wheel_test_real),
                if (event.simulated) state.simulated else state.received)
        }
        views.counts.text = getString(R.string.wheel_test_count, state.received, state.simulated, state.event?.source ?: "—")
        views.logs.text = state.lines.joinToString("\n")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::controller.isInitialized && controller.key(event, "前台按键")) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        foreground = false
        appearanceHandler.removeCallbacks(appearancePoll)
        appearanceObserverRemoval?.invoke()
        appearanceObserverRemoval = null
        startGeneration++
        disconnectDialog?.dismiss()
        disconnectDialog = null
        if (::controller.isInitialized) controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.close()
        super.onDestroy()
    }
}
