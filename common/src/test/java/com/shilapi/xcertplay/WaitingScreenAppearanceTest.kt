package com.shilapi.xcertplay

import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class WaitingScreenAppearanceTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        BackgroundSessionFixture.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
    }

    @After fun tearDown() {
        HostSessionFixture.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        HostSessionFixture.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        HostSessionFixture.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        HostSessionFixture.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        BackgroundSessionFixture.clear()
    }

    @Test fun darkAppThemeKeepsTheConnectionScreenDarkWhileCarPlayIsInDayMode() {
        assertEquals(WaitingScreenColors.of(true).background, waitingBackground(appNight = true, carPlayNight = false))
    }

    @Test fun lightAppThemeKeepsTheConnectionScreenLightWhileCarPlayIsInNightMode() {
        assertEquals(WaitingScreenColors.of(false).background, waitingBackground(appNight = false, carPlayNight = true))
    }

    private fun waitingBackground(appNight: Boolean, carPlayNight: Boolean): Int {
        HostSessionFixture.setField(activity, "appNight", appNight)
        HostSessionFixture.setField(activity, "darkMode", carPlayNight)
        val root = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
            .apply { isAccessible = true }.invoke(activity) as ViewGroup
        val expected = setOf(WaitingScreenColors.of(true).background, WaitingScreenColors.of(false).background)
        return descendants(root).drop(1).mapNotNull { (it.background as? ColorDrawable)?.color }.first { it in expected }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
