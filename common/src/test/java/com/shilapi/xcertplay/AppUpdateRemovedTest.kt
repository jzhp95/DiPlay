package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w960dp-h600dp-land")
class AppUpdateRemovedTest {
    @Test fun cachedReleaseCannotAddAnUpdatePromptToHome() = verifyHome()

    @Test @Config(sdk = [29], qualifiers = "en-w320dp-h600dp-port")
    fun compactHomeAlsoIgnoresCachedReleases() = verifyHome()

    private fun verifyHome() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val preferences = activity.getSharedPreferences("diplay_update_availability", Context.MODE_PRIVATE)
        preferences.edit().putString("tag", "v99.0.0").putString("apk_name", "DiPlay.apk")
            .putString("apk_url", "https://example.com/DiPlay.apk")
            .putString("checksums_url", "https://example.com/SHA256SUMS.txt").commit()
        try {
            controller.setup() // Old versions read the cached release during onCreate.
            val content = render(activity, "home")
            assertFalse(texts(content).contains("Update available"))
            assertTrue(texts(activity.window.decorView).contains(activity.getString(R.string.settings)))
        } finally {
            preferences.edit().clear().commit()
            controller.pause().stop().destroy()
        }
    }

    @Test fun aboutShowsAppDetailsWithoutBackupOrUpdateControls() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            val text = texts(render(activity, "about"))
            assertTrue(text.contains(activity.getString(R.string.settings_about_summary)))
            assertTrue(text.contains(activity.getString(R.string.settings_about_features_summary)))
            assertTrue(text.contains(activity.getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay)))
            assertFalse(text.any { it.contains("备份") || it.contains("恢复") })
            for (removed in listOf("Check for updates", "Check for updates in the background", "Download update")) {
                assertFalse(removed, text.contains(removed))
            }
        } finally { activity.finish() }
    }

    private fun render(activity: DiPlayActivity, method: String): LinearLayout {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        DiPlayActivity::class.java.getDeclaredMethod(method, LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, content)
        return content
    }

    private fun texts(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(texts(view.getChildAt(i)))
    }
}
