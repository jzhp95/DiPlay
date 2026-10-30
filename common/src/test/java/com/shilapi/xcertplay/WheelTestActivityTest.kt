package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, qualifiers = "w1080dp-h720dp-land")
class WheelTestActivityTest {
    @Test fun aLiveCarPlaySessionRequiresAnExplicitDisconnectChoice() {
        var stops = 0
        val owner = CarPlayBackgroundSession
        val previous = DiPlaySessionService.liveRuntime
        val stopAction: ((() -> Unit) -> Unit) = { stops++ }
        BackgroundSessionFixture.storeStop(stopAction)
        val activity = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        try {
            val screen = activity.get()
            descendants(screen.window.decorView).filterIsInstance<Button>().single {
                it.text == screen.getString(R.string.wheel_test_acquire)
            }.performClick()
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
            assertEquals(0, stops)
            val probe = ReflectionHelpers.getField<WheelTestController>(screen, "controller")
            assertFalse(probe.snapshot().running)
        } finally {
            activity.pause().stop().destroy()
            DiPlaySessionService.liveRuntime=previous
        }
    }

    @Test fun openingDoesNotTakeFocusAndLeavingStopsAnExplicitTest() {
        val activity = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        try {
            val screen = activity.get()
            val probe = ReflectionHelpers.getField<WheelTestController>(screen, "controller")
            fun button(id: Int) = descendants(screen.window.decorView).filterIsInstance<Button>().single { it.text == screen.getString(id) }
            assertFalse(probe.snapshot().running)
            button(R.string.wheel_test_next).performClick()
            assertEquals(1, probe.snapshot().simulated)
            assertEquals(0, probe.snapshot().received)
            button(R.string.wheel_test_acquire).performClick()
            assertTrue(probe.snapshot().running)
            button(R.string.wheel_test_release).performClick()
            assertFalse(probe.snapshot().running)
            button(R.string.wheel_test_acquire).performClick()
            activity.pause().stop()
            assertFalse(probe.snapshot().running)
            activity.restart().start().resume()
            assertFalse(probe.snapshot().running)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun appearanceFollowsSystemAndLiveChangesKeepTheTestState() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("xcertplay_airplay", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getString("app_appearance", null)
        val host = Any()
        prefs.edit().putString("app_appearance", "auto").commit()
        setSystemNight(context, true)
        AppAppearanceRuntime.publishHost(host, false)
        val activity = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        try {
            val screen = activity.get()
            val probe = ReflectionHelpers.getField<WheelTestController>(screen, "controller")
            assertTrue(screen.currentAppNight)
            probe.start()
            probe.simulate(4)
            val before = probe.snapshot()
            setSystemNight(screen, false)
            screen.onConfigurationChanged(screen.resources.configuration)
            assertFalse(screen.currentAppNight)
            assertSame(probe, ReflectionHelpers.getField<WheelTestController>(screen, "controller"))
            assertEquals(before.running, probe.snapshot().running)
            assertEquals(before.simulated, probe.snapshot().simulated)
            assertEquals(before.event, probe.snapshot().event)
            val views = ReflectionHelpers.getField<WheelTestLayout.Views>(screen, "views")
            assertEquals(DiPlayPalette.LIGHT.accentText, views.event.currentTextColor)
            assertFalse(views.acquire.isEnabled)
            assertTrue(views.release.isEnabled)
            prefs.edit().putString("app_appearance", "dark").commit()
            ReflectionHelpers.getField<Runnable>(screen, "appearancePoll").run()
            assertFalse(screen.currentAppNight)
            assertTrue(probe.snapshot().running)
            assertEquals(before.event, probe.snapshot().event)
        } finally {
            activity.pause().stop().destroy()
            AppAppearanceRuntime.clearHost(host)
            if (previous == null) prefs.edit().remove("app_appearance").commit()
            else prefs.edit().putString("app_appearance", previous).commit()
        }
    }

    @Test fun deliveredFlymeVoiceKeyShowsItsRawCodeAsRealInput() {
        val activity = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        try {
            val screen = activity.get()
            val probe = ReflectionHelpers.getField<WheelTestController>(screen, "controller")
            probe.start()
            assertTrue(screen.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, 200231)))
            assertTrue(screen.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, 200231)))
            val views = ReflectionHelpers.getField<WheelTestLayout.Views>(screen, "views")
            assertTrue(views.event.text.contains("200231"))
            assertTrue(views.event.text.contains(screen.getString(R.string.wheel_test_real)))
            assertEquals(1, probe.snapshot().received)
            assertEquals(0, probe.snapshot().simulated)
            assertFalse(probe.snapshot().event!!.simulated)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun mfiMenuChangesTheDetailsWithoutStartingWheelFocus() {
        val activity = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        try {
            val screen = activity.get()
            val probe = ReflectionHelpers.getField<WheelTestController>(screen, "controller")
            menu(screen, "MFi 认证测试").performClick()
            assertFalse(probe.snapshot().running)
            assertFalse(descendants(screen.window.decorView).filterIsInstance<Button>().any {
                it.isShown && it.text == screen.getString(R.string.wheel_test_acquire)
            })
            menu(screen, "方控测试").performClick()
            assertTrue(descendants(screen.window.decorView).filterIsInstance<Button>().any {
                it.isShown && it.text == screen.getString(R.string.wheel_test_acquire)
            })
            assertFalse(probe.snapshot().running)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun leavingWheelDetailsStopsFocusAndReturningDoesNotRestartIt() {
        val activity = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        try {
            val screen = activity.get()
            val probe = ReflectionHelpers.getField<WheelTestController>(screen, "controller")
            descendants(screen.window.decorView).filterIsInstance<Button>().single {
                it.text == screen.getString(R.string.wheel_test_acquire)
            }.performClick()
            assertTrue(probe.snapshot().running)
            menu(screen, "MFi 认证测试").performClick()
            assertFalse("离开方控详情应立即释放测试焦点", probe.snapshot().running)
            menu(screen, "方控测试").performClick()
            assertFalse("返回方控详情需要再次显式获取焦点", probe.snapshot().running)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun changingToMfiInvalidatesAPendingDisconnectAndStart() {
        val owner = CarPlayBackgroundSession
        val previous = DiPlaySessionService.liveRuntime
        var completion: (() -> Unit)? = null
        val stopAction: ((() -> Unit) -> Unit) = { completion = it }
        BackgroundSessionFixture.storeStop(stopAction)
        val activity = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        try {
            val screen = activity.get()
            val probe = ReflectionHelpers.getField<WheelTestController>(screen, "controller")
            descendants(screen.window.decorView).filterIsInstance<Button>().single {
                it.text == screen.getString(R.string.wheel_test_acquire)
            }.performClick()
            ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNotNull("测试应等待现有 CarPlay 会话释放", completion)
            menu(screen, "MFi 认证测试").performClick()
            BackgroundSessionFixture.clear()
            completion!!.invoke()
            assertFalse("菜单已切换时，延迟完成回调不能重新开启方控焦点", probe.snapshot().running)
        } finally {
            activity.pause().stop().destroy()
            DiPlaySessionService.liveRuntime=previous
        }
    }

    @Test fun mfiSelectionAndResultsSurviveAppearanceRebuild() {
        val activity = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        try {
            val screen = activity.get()
            menu(screen, "MFi 认证测试").performClick()
            val probe = ReflectionHelpers.getField<MfiTestController>(screen, "mfiController")
            ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(probe, "worker")
                .submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val before = probe.snapshot()
            val text = ReflectionHelpers.getField<MfiTestLayout.Views>(screen, "mfiViews").local.text.toString()
            screen.onConfigurationChanged(screen.resources.configuration)
            assertSame(probe, ReflectionHelpers.getField<MfiTestController>(screen, "mfiController"))
            assertEquals(before, probe.snapshot())
            assertTrue(menu(screen, "MFi 认证测试").isSelected)
            assertEquals(text, ReflectionHelpers.getField<MfiTestLayout.Views>(screen, "mfiViews").local.text.toString())
            assertFalse(ReflectionHelpers.getField<WheelTestLayout.Views>(screen, "views").root.isShown)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun uidMenuReadsIdentityAndStopsWheelWithoutStartingAnotherTest() {
        val activity = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        try {
            val screen = activity.get()
            val probe = ReflectionHelpers.getField<WheelTestController>(screen, "controller")
            probe.start()
            menu(screen, "UID 检测").performClick()
            assertFalse(probe.snapshot().running)
            val text = descendants(screen.window.decorView).filterIsInstance<android.widget.TextView>()
                .filter { it.isShown }.joinToString("\n") { it.text }
            assertTrue(text.contains("进程 UID：${android.os.Process.myUid()}"))
            assertTrue(text.contains("安装 UID：${screen.applicationInfo.uid}"))
            assertTrue(text.contains(screen.packageName))
            screen.onConfigurationChanged(screen.resources.configuration)
            assertTrue(menu(screen, "UID 检测").isSelected)
            menu(screen, "方控测试").performClick()
            assertFalse(probe.snapshot().running)
        } finally { activity.pause().stop().destroy() }
    }

    private fun menu(screen: WheelTestActivity, title: String): Button {
        val button = descendants(screen.window.decorView).filterIsInstance<Button>()
            .firstOrNull { it.isShown && it.text == title }
        assertNotNull("应提供“$title”左侧测试菜单", button)
        return button!!
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
