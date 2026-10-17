package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydFeaturesRemovedTest {
    @Test fun everySettingsCategoryAndSearchExcludeVendorControls() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup().visible()
        val screen = controller.get()
        try {
            val retired = listOf(R.string.byd_navigation, R.string.byd_adb_features,
                R.string.advanced_vehicle_data, R.string.boot_start_repair,
                R.string.settings_hide_byd_call_popup, R.string.phone_audio_route,
                R.string.phone_audio_route_apps, R.string.settings_ambient_configuration,
                R.string.settings_ambient_title, R.string.adb_cluster_activity_mode,
                R.string.platform21_cluster_mode, R.string.wheel_joystick,
                R.string.wheel_siri_long_press_only, R.string.instrument_theme,
                R.string.instrument_contrast, R.string.cluster_song, R.string.song_on_hud, R.string.settings_cluster_speedometer)
                .map(screen::getString)
            for (category in SettingsCategory.entries) {
                ReflectionHelpers.setField(screen, "page", "settings")
                ReflectionHelpers.setField(screen, "settingsCategory", category)
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                assertTrue(category.name, views(screen.window.decorView).filterIsInstance<TextView>().none { view ->
                    retired.any { view.text == it || view.text.startsWith("$it · ") }
                })
            }
            val index = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(
                screen, "buildSettingsSearchIndex")
            assertTrue(index.none { it.title in retired })
            assertTrue(index.any { it.title == screen.getString(R.string.frame_rate) })
            assertTrue(index.any { it.title == screen.getString(R.string.wheel_siri_key) })
        } finally { controller.pause().stop().destroy() }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
