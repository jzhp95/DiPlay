package com.shilapi.xcertplay

import android.content.Intent
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class LauncherIntentTest {
    private var controller: ActivityController<DiPlayActivity>? = null
    private var stops = 0

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        BackgroundSessionFixture.clear()
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        BackgroundSessionFixture.storeStop(stop)
    }

    @After fun cleanup() {
        controller?.destroy()
        BackgroundSessionFixture.clear()
    }
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test
    fun onlyAPlainLauncherIntentIsRecognizedAsLauncher() {
        assertTrue(DiPlayActivity.isLauncherIntent(launcher()))
        assertFalse(DiPlayActivity.isLauncherIntent(launcher().putExtra("page", "settings")))
        assertFalse(DiPlayActivity.isLauncherIntent(launcher().putExtra("page", null as String?)))
        assertFalse(DiPlayActivity.isLauncherIntent(launcher().putExtra("page", 123)))
        assertFalse(DiPlayActivity.isLauncherIntent(Intent(Intent.ACTION_MAIN)))
        assertFalse(DiPlayActivity.isLauncherIntent(Intent().putExtra("page", "connection")))
    }

    @Test fun freshLauncherShowsHomeAndAllowsExplicitDisconnect() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, launcher()).create()
        val activity = controller!!.get()
        assertFalse(activity.isFinishing)
        assertEquals("home", HostSessionFixture.getField<String>(activity, "page"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals(0, stops)
        val disconnect = HostSessionFixture.getField<android.widget.Button>(activity, "disconnectButton")
        assertEquals(android.view.View.VISIBLE, disconnect.visibility)
        disconnect.performClick()
        assertEquals(1, stops)
    }

    @Test fun launcherDeliveredToExistingSettingsShowsHomeWithoutStoppingSession() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "settings")).create()
        val activity = controller!!.get()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent",
            ReflectionHelpers.ClassParameter(Intent::class.java, launcher()))
        assertEquals("home", HostSessionFixture.getField<String>(activity, "page"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals(0, stops)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent",
            ReflectionHelpers.ClassParameter(Intent::class.java, launcher().putExtra("page", "settings")))
        assertEquals("settings", HostSessionFixture.getField<String>(activity, "page"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun autoEnterIsOffByDefaultAndDoesNotEnableAutoConnect() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(DiPlayPreferences.enterCarPlayOnOpen(app))
        DiPlayPreferences.saveEnterCarPlayOnOpen(app, true)
        assertTrue(DiPlayPreferences.enterCarPlayOnOpen(app))
        assertFalse(DiPlayPreferences.autoConnect(app))
    }

    @Test fun enabledFreshLauncherReturnsToProjectionWithoutStoppingSession() {
        DiPlayPreferences.saveEnterCarPlayOnOpen(RuntimeEnvironment.getApplication(), true)
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, launcher()).create()
        val activity = controller!!.get()
        assertTrue(activity.isFinishing)
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertEquals(0, stops)
    }

    @Test fun enabledReusedLauncherReturnsToProjectionButExplicitSettingsRemainReachable() {
        DiPlayPreferences.saveEnterCarPlayOnOpen(RuntimeEnvironment.getApplication(), true)
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "settings")).create()
        val activity = controller!!.get()
        assertEquals("settings", HostSessionFixture.getField<String>(activity, "page"))
        assertNull(shadowOf(activity).nextStartedActivity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent",
            ReflectionHelpers.ClassParameter(Intent::class.java, launcher()))
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertEquals(0, stops)
    }

    @Test fun enabledButDisconnectedLauncherStillShowsReceiverUi() {
        BackgroundSessionFixture.clear()
        DiPlayPreferences.saveEnterCarPlayOnOpen(RuntimeEnvironment.getApplication(), true)
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, launcher()).create()
        val activity = controller!!.get()
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, stops)
    }

    @Test fun recreationRetainsSettingsInsteadOfRedirectingToProjection() {
        DiPlayPreferences.saveEnterCarPlayOnOpen(RuntimeEnvironment.getApplication(), true)
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, launcher())
            .create(Bundle().apply { putString("page", "settings") })
        val activity = controller!!.get()
        assertFalse(activity.isFinishing)
        assertEquals("settings", HostSessionFixture.getField<String>(activity, "page"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, stops)
    }
}
