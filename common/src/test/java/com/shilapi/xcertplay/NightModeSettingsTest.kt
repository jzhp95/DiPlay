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
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class NightModeSettingsTest {
    @Test fun retiredAppearanceAndDrivingSideControlsAreAbsentFromEveryCategoryAndSearch() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putString("app_appearance", "light").putString("carplay_night_mode", "schedule").commit()
        BackgroundSessionFixture.clear()
        controller.setup().visible()
        try {
            val retired = listOf(R.string.settings_app_appearance, R.string.carplay_night_mode,
                R.string.ambient_light_threshold_title, R.string.ambient_delay_title,
                R.string.carplay_night_start, R.string.carplay_night_end, R.string.right_hand_drive)
                .map(activity::getString)
            for (category in SettingsCategory.entries) {
                ReflectionHelpers.setField(activity, "page", "settings")
                ReflectionHelpers.setField(activity, "settingsCategory", category)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                assertTrue(category.name, views(activity.window.decorView).filterIsInstance<TextView>().none { view ->
                    retired.any { title -> view.text == title || view.text.startsWith("$title · ") }
                })
            }
            val index = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(
                activity, "buildSettingsSearchIndex")
            assertTrue(index.none { it.title in retired })
            assertEquals(AppAppearance.AUTO, AirPlayPersistence.loadAppAppearance(activity))
            assertEquals(CarPlayNightMode.SYSTEM, AirPlayPersistence.loadCarPlayNightMode(activity))
        } finally {
            controller.pause().stop().destroy()
            BackgroundSessionFixture.clear()
            activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
