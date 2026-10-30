package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** The Choose iPhone buttons name the chosen iPhone, and no iPhone before one is chosen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class ChooseIphoneTitleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<DiPlayActivity>()

    @Before fun setUp() = clearSettings()

    @After fun tearDown() {
        opened.forEach { it.finish() }
        AppAppearanceRuntime.resetForTest()
        clearSettings()
    }

    private fun clearSettings() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun open(page: String): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", page),
    ).setup().get().also { opened += it }

    private fun pages(): List<Pair<String, DiPlayActivity>> = listOf("home" to open("home"))

    private fun texts(screen: DiPlayActivity): List<String> =
        descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun chooseButtons(screen: DiPlayActivity): List<String> = texts(screen).filter {
        it == screen.getString(R.string.choose_iphone) || it.startsWith(screen.getString(R.string.choose_iphone_prefix))
    }

    @Test fun beforeAnIphoneIsChosenTheButtonNamesNone() {
        for ((name, screen) in pages()) {
            assertEquals(name, listOf(screen.getString(R.string.choose_iphone)), chooseButtons(screen))
            assertFalse(name, texts(screen).any { "Your iPhone" in it })
        }
    }

    @Test fun aLegacyGuideIntentReturnsHomeWithoutShowingTheWizard() {
        val screen = open("setup")
        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun settingsOverviewDoesNotOfferTheRemovedGuide() {
        val screen = open("settings")
    }

    @Test fun aChosenIphoneKeepsTheHomePickerActionStable() {
        DiPlayPreferences.savePhone(context, "AA:BB:CC:DD:EE:FF", "Test iPhone")
        for ((name, screen) in pages()) {
            assertEquals(name, listOf(screen.getString(R.string.choose_iphone)), chooseButtons(screen))
        }
    }

    // The placeholder was English in every language: Traditional Chinese read "選擇 iPhone · Your iPhone".
    @Config(qualifiers = "zh-rTW-w1000dp-h700dp-land")
    @Test fun traditionalSystemLocaleStillShowsSimplifiedChinese() {
        for ((name, screen) in pages()) {
            assertEquals(name, listOf("选择 iPhone"), chooseButtons(screen))
        }
    }
}
