package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarHotspotSettingsUiTest {
    private val profile = CarHotspotProfile("Car", "12345678", ManualHotspotSecurity.WPA3, ManualHotspotBand.GHZ_5, 149)
    private lateinit var activity: Activity
    private lateinit var ui: CarHotspotSettingsUi
    private lateinit var fake: Fake
    private var changes = 0
    private class Fake : CarHotspotOperations {
        @Volatile var applied: CarHotspotProfile? = null
        @Volatile var enabled = false
        var beforeApply: () -> Unit = {}
        var beforeObserve: () -> Unit = {}
        @Volatile var completed = false
        @Volatile var observed: ObservedHotspotInfo? = null
        override fun read(): CarHotspotProfile? = null
        override fun observe(): ObservedHotspotInfo? {
            beforeObserve()
            return observed
        }
        override fun apply(profile: CarHotspotProfile, cancelled: () -> Boolean): CarHotspotControlResult {
            beforeApply()
            if (cancelled()) { completed = true; return CarHotspotControlResult(false, false, null, "cancelled") }
            applied = profile
            completed = true
            return CarHotspotControlResult(true, true, profile, "test-verified")
        }
        override fun enable(cancelled: () -> Boolean): CarHotspotControlResult {
            enabled = true
            return CarHotspotControlResult(true, true, null, "test-started")
        }
    }
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        for (name in listOf("diplay_managed_hotspot", "diplay_car_hotspot", "xcertplay_airplay"))
            app.getSharedPreferences(name, 0).edit().clear().commit()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        fake = Fake()
        ui = CarHotspotSettingsUi(activity, changed = { changes++ }, redraw = {}, controller = fake)
    }
    @After fun cleanup() {
        ui.close()
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        activity.finish()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun hotspotCredentialsUseSeparateDraftEditorsWithoutASeparateManualEntry() {
        val parent = card()
        assertTrue(inputs(parent).isEmpty())
        for (title in listOf(R.string.hotspot_name, R.string.hotspot_password)) {
            val dialog = openHotspotEditor(activity, parent, title)
            assertEquals(1, inputs(dialog.window!!.decorView).size)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
        assertFalse(views(parent).filterIsInstance<Button>().any { it.text == activity.getString(R.string.settings_hotspot_manual) })
        button(parent, R.string.settings_hotspot_save)
        assertFalse(CarHotspotSettings.enabled(activity))
        assertNull(fake.applied)
        assertFalse(fake.enabled)
    }

    @Test fun savingInlineCredentialsDoesNotApplyOrEnableTheSystemHotspot() {
        val parent = card()
        editHotspotDraft(activity, parent, R.string.hotspot_name, "Saved Car")
        editHotspotDraft(activity, parent, R.string.hotspot_password, "abcdefgh")
        button(parent, R.string.settings_hotspot_save).performClick()

        assertEquals("Saved Car", CarHotspotProfiles.resolve(activity, manual = { null })?.profile?.ssid)
        assertNull(fake.applied)
        assertFalse(fake.enabled)
        assertEquals(1, changes)
    }

    @Test fun changingCredentialsPreservesTheSavedRadioIntentAndInterface() {
        CarHotspotProfiles.saveSaved(activity, profile)
        AirPlayPersistence.saveManualHotspotInterface(activity, "eth0.3")
        val parent = card()
        editHotspotDraft(activity, parent, R.string.hotspot_name, "Updated Car")
        editHotspotDraft(activity, parent, R.string.hotspot_password, "abcdefgh")
        button(parent, R.string.settings_hotspot_save).performClick()

        assertEquals(profile.copy(ssid = "Updated Car", passphrase = "abcdefgh"), CarHotspotProfiles.saved(activity))
        assertEquals("eth0.3", AirPlayPersistence.loadManualHotspotInterface(activity))
        assertNull(fake.applied)
    }

    @Test fun cancelledRadioChoiceDoesNotChangeSavedProfileOrStartHotspot() {
        CarHotspotProfiles.saveSaved(activity,profile);val root=card()
        views(root).filterIsInstance<Button>().single{it.text.startsWith(activity.getString(R.string.settings_hotspot_band)+" · ")}.performClick()
        val choice=ShadowAlertDialog.getLatestAlertDialog()
        choice.listView.performItemClick(null,1,1);choice.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        button(root,R.string.settings_hotspot_save).performClick()
        assertEquals(profile,CarHotspotProfiles.saved(activity));assertNull(fake.applied);assertFalse(fake.enabled)
    }
    @Test fun startupUsesExplicitlySavedRadioOptions() {
        CarHotspotProfiles.saveSaved(activity,profile);val root=card()
        select(root,R.string.settings_hotspot_security,"WPA2/WPA3")
        select(root,R.string.settings_hotspot_band,"2.4 GHz");select(root,R.string.settings_hotspot_channel,"6")
        button(root,R.string.settings_hotspot_start).performClick();assertNull(fake.applied)
        button(root,R.string.settings_hotspot_save).performClick();button(root,R.string.settings_hotspot_start).performClick()
        await{fake.applied!=null}
        val expected=profile.copy(security=ManualHotspotSecurity.WPA3_TRANSITION,band=ManualHotspotBand.GHZ_2_4,channel=6)
        assertEquals(expected,fake.applied);assertEquals(expected,CarHotspotProfiles.saved(activity));assertFalse(fake.enabled)
    }
    @Test fun aSessionStartingWhileTheHotspotRequestIsQueuedCancelsTheRequest() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeApply = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val previous = DiPlaySessionService.liveRuntime
        try {
            CarHotspotProfiles.saveSaved(activity,profile)
            button(card(),R.string.settings_hotspot_start).performClick()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val stop: ((() -> Unit) -> Unit) = {}
            BackgroundSessionFixture.storeStop(stop)
            release.countDown()
            await { fake.completed }
            assertNull("A new session must prevent the queued hotspot change", fake.applied)
        } finally {
            release.countDown()
            DiPlaySessionService.liveRuntime = previous
        }
    }

    @Test fun mismatchedSecurityCannotBeSavedOrStarted() {
        CarHotspotProfiles.saveSaved(activity,profile);val root=card()
        select(root,R.string.settings_hotspot_security,activity.getString(R.string.open))
        button(root,R.string.settings_hotspot_save).performClick();button(root,R.string.settings_hotspot_start).performClick()
        assertEquals(profile,CarHotspotProfiles.saved(activity));assertNull(fake.applied)
        assertTrue(views(root).filterIsInstance<TextView>().any{it.text==activity.getString(R.string.settings_hotspot_invalid_security_password)})
    }
    @Test fun invalidInlinePasswordCannotReplaceAValidSavedProfile() {
        CarHotspotProfiles.saveSaved(activity, profile)
        val parent = card()
        editHotspotDraft(activity, parent, R.string.hotspot_password, "short")
        button(parent, R.string.settings_hotspot_save).performClick()
        assertEquals(profile, CarHotspotProfiles.saved(activity))
        assertNull(fake.applied)
        assertEquals(0, changes)
    }

    @Test fun savingAnUnchangedEditorDoesNotBlockStartingTheSavedHotspot() {
        CarHotspotProfiles.saveSaved(activity, profile)
        val parent = card()
        val dialog = openHotspotEditor(activity, parent, R.string.hotspot_name)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        button(parent, R.string.settings_hotspot_start).performClick()
        await { fake.completed }
        assertEquals(profile, fake.applied)
        assertEquals(profile, CarHotspotProfiles.saved(activity))
    }

    @Test fun startRequiresAnExplicitlySavedProfile() {
        button(card(), R.string.settings_hotspot_start).performClick()
        assertNull(fake.applied)
        assertFalse(fake.enabled)
        assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
    }

    @Test fun passwordVisibilityDoesNotChangeTheSavedOrEditedValue() {
        CarHotspotProfiles.saveSaved(activity, profile)
        val parent = card()
        val dialog = openHotspotEditor(activity, parent, R.string.hotspot_password)
        val password = inputs(dialog.window!!.decorView).single()
        val eye = views(dialog.window!!.decorView).filterIsInstance<android.widget.ImageButton>().single {
            it.contentDescription == activity.getString(R.string.settings_hotspot_show_password)
        }
        eye.performClick()
        assertNull(password.transformationMethod)
        assertEquals(profile.passphrase, password.text.toString())
        eye.performClick()
        assertNotNull(password.transformationMethod)
        assertEquals(profile, CarHotspotProfiles.saved(activity))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
    }

    @Test fun cancellingInterfaceEditKeepsTheExistingOverride() {
        CarHotspotProfiles.saveSaved(activity, profile)
        AirPlayPersistence.saveManualHotspotInterface(activity, "eth0.3")
        val parent = card()
        assertFalse(views(parent).filterIsInstance<Button>().any{
            it.text.startsWith(activity.getString(R.string.settings_hotspot_interface_short)+" · ")
        })
        editHotspotDraft(activity,parent,R.string.hotspot_password,"abcdefgh")
        button(parent,R.string.settings_hotspot_save).performClick()
        assertEquals("eth0.3", AirPlayPersistence.loadManualHotspotInterface(activity))
        assertEquals(profile.copy(passphrase="abcdefgh"), CarHotspotProfiles.saved(activity))
    }

    @Test fun syncingSystemHotspotPrepopulatesObservedSsid() {
        val root = LinearLayout(activity)
        var redraws = 0
        lateinit var testUi: CarHotspotSettingsUi
        testUi = CarHotspotSettingsUi(activity, changed = {}, redraw = {
            redraws++
            root.removeAllViews()
            testUi.build(root)
        }, controller = fake)
        fake.observed = ObservedHotspotInfo(ssid = "CarSystemHotspot", passwordAvailable = false)
        testUi.build(root)
        button(root, R.string.settings_hotspot_sync).performClick()
        await { redraws > 0 && views(root).filterIsInstance<Button>().any { it.text.contains("CarSystemHotspot") } }
        assertTrue(views(root).filterIsInstance<Button>().any { it.text.contains("CarSystemHotspot") })
        assertNull(CarHotspotProfiles.saved(activity))
    }

    @Test fun syncingSystemHotspotReplacesExistingSavedProfileInUi() {
        val oldSaved = CarHotspotProfile("OldWIFI", "12345678", ManualHotspotSecurity.WPA2)
        CarHotspotProfiles.saveSaved(activity, oldSaved)
        val root = LinearLayout(activity)
        var redraws = 0
        lateinit var testUi: CarHotspotSettingsUi
        testUi = CarHotspotSettingsUi(activity, changed = {}, redraw = {
            redraws++
            root.removeAllViews()
            testUi.build(root)
        }, controller = fake)
        fake.observed = ObservedHotspotInfo(ssid = "NewCarHotspot", passwordAvailable = false)
        testUi.build(root)
        assertTrue(views(root).filterIsInstance<Button>().any { it.text.contains("OldWIFI") })

        button(root, R.string.settings_hotspot_sync).performClick()
        await { redraws > 0 && views(root).filterIsInstance<Button>().any { it.text.contains("NewCarHotspot") } }
        assertTrue(views(root).filterIsInstance<Button>().any { it.text.contains("NewCarHotspot") })
        assertFalse(views(root).filterIsInstance<Button>().any { it.text.contains("OldWIFI") })
    }

    @Test fun syncingFailsExplicitlyWhenSystemReturnsNull() {
        val root = LinearLayout(activity)
        var redraws = 0
        lateinit var testUi: CarHotspotSettingsUi
        testUi = CarHotspotSettingsUi(activity, changed = {}, redraw = {
            redraws++
            root.removeAllViews()
            testUi.build(root)
        }, controller = fake)
        fake.observed = null
        testUi.build(root)

        button(root, R.string.settings_hotspot_sync).performClick()
        await { redraws > 0 && views(root).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.settings_hotspot_sync_failed) } }
        assertTrue(views(root).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.settings_hotspot_sync_failed) })
    }

    @Test fun syncingFailsAndPreservesDraftWhenSystemReturnsNullEvenIfSavedProfileExists() {
        val oldSaved = CarHotspotProfile("OldSavedCar", "12345678", ManualHotspotSecurity.WPA2)
        CarHotspotProfiles.saveSaved(activity, oldSaved)
        val root = LinearLayout(activity)
        var redraws = 0
        lateinit var testUi: CarHotspotSettingsUi
        testUi = CarHotspotSettingsUi(activity, changed = {}, redraw = {
            redraws++
            root.removeAllViews()
            testUi.build(root)
        }, controller = fake)
        fake.observed = null
        testUi.build(root)
        assertTrue(views(root).filterIsInstance<Button>().any { it.text.contains("OldSavedCar") })

        editHotspotDraft(activity, root, R.string.hotspot_name, "CurrentDraftSSID")
        assertTrue(views(root).filterIsInstance<Button>().any { it.text.contains("CurrentDraftSSID") })

        button(root, R.string.settings_hotspot_sync).performClick()
        await { redraws > 0 && views(root).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.settings_hotspot_sync_failed) } }

        assertTrue(views(root).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.settings_hotspot_sync_failed) })
        assertFalse(views(root).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.settings_hotspot_sync_success_prompt_save) })
        assertTrue("Current draft must be preserved on sync failure", views(root).filterIsInstance<Button>().any { it.text.contains("CurrentDraftSSID") })
        assertFalse("Old saved profile must not overwrite draft on sync failure", views(root).filterIsInstance<Button>().any { it.text.contains("OldSavedCar") })
        assertEquals(oldSaved, CarHotspotProfiles.saved(activity))
    }

    @Test fun editsMadeDuringAsyncSyncAreNotOverwritten() {
        val root = LinearLayout(activity)
        var redraws = 0
        lateinit var testUi: CarHotspotSettingsUi
        testUi = CarHotspotSettingsUi(activity, changed = {}, redraw = {
            redraws++
            root.removeAllViews()
            testUi.build(root)
        }, controller = fake)
        fake.observed = ObservedHotspotInfo(ssid = "LateArrivingSSID", passwordAvailable = false)
        testUi.build(root)

        button(root, R.string.settings_hotspot_sync).performClick()
        editHotspotDraft(activity, root, R.string.hotspot_name, "UserCustomSSID")
        shadowOf(Looper.getMainLooper()).idle()

        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(views(root).filterIsInstance<Button>().any { it.text.contains("UserCustomSSID") })
        assertFalse(views(root).filterIsInstance<Button>().any { it.text.contains("LateArrivingSSID") })
    }

    @Test fun startingHotspotAfterSyncWithoutSavingIsBlockedAndDoesNotApplyOldSavedProfile() {
        val oldSaved = CarHotspotProfile("OldWIFI", "12345678", ManualHotspotSecurity.WPA2)
        CarHotspotProfiles.saveSaved(activity, oldSaved)
        val root = LinearLayout(activity)
        var redraws = 0
        lateinit var testUi: CarHotspotSettingsUi
        testUi = CarHotspotSettingsUi(activity, changed = {}, redraw = {
            redraws++
            root.removeAllViews()
            testUi.build(root)
        }, controller = fake)
        fake.observed = ObservedHotspotInfo(ssid = "NewCarHotspot", passwordAvailable = false)
        testUi.build(root)

        button(root, R.string.settings_hotspot_sync).performClick()
        await { redraws > 0 && views(root).filterIsInstance<Button>().any { it.text.contains("NewCarHotspot") } }

        button(root, R.string.settings_hotspot_start).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("Starting without saving must not apply the old saved profile", fake.applied)
        assertTrue(views(root).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.settings_hotspot_save_first) })

        editHotspotDraft(activity, root, R.string.hotspot_password, "newpassword")
        button(root, R.string.settings_hotspot_save).performClick()
        button(root, R.string.settings_hotspot_start).performClick()
        await { fake.applied != null }
        assertEquals("NewCarHotspot", fake.applied?.ssid)
        assertEquals("newpassword", fake.applied?.passphrase)
    }

    @Test fun initialReadImportsConfirmedProfileAndDisplaysItWhenNoSavedOrManualProfileExists() {
        val root = LinearLayout(activity)
        var redraws = 0
        fake.observed = null
        val confirmedProfile = CarHotspotProfile("ConfirmedCar", "12345678", ManualHotspotSecurity.WPA2)
        CarHotspotProfiles.saveConfirmed(activity, confirmedProfile)
        lateinit var testUi: CarHotspotSettingsUi
        testUi = CarHotspotSettingsUi(activity, changed = {}, redraw = {
            redraws++
            root.removeAllViews()
            testUi.build(root)
        }, controller = fake)
        testUi.build(root)

        await { redraws > 0 && views(root).filterIsInstance<Button>().any { it.text.contains("ConfirmedCar") } }
        assertTrue(views(root).filterIsInstance<Button>().any { it.text.contains("ConfirmedCar") })
        assertEquals("ConfirmedCar", CarHotspotProfiles.saved(activity)?.ssid)
    }

    @Test fun resyncingAfterDraftEditOverwritesDraftWithCarHotspot() {
        val root = LinearLayout(activity)
        var redraws = 0
        lateinit var testUi: CarHotspotSettingsUi
        testUi = CarHotspotSettingsUi(activity, changed = {}, redraw = {
            redraws++
            root.removeAllViews()
            testUi.build(root)
        }, controller = fake)
        testUi.build(root)

        editHotspotDraft(activity, root, R.string.hotspot_name, "TemporaryDraft")
        assertTrue(views(root).filterIsInstance<Button>().any { it.text.contains("TemporaryDraft") })

        fake.observed = ObservedHotspotInfo(ssid = "RealCarHotspot", passwordAvailable = false)
        button(root, R.string.settings_hotspot_sync).performClick()

        await { redraws > 0 && views(root).filterIsInstance<Button>().any { it.text.contains("RealCarHotspot") } }
        assertTrue(views(root).filterIsInstance<Button>().any { it.text.contains("RealCarHotspot") })
        assertFalse(views(root).filterIsInstance<Button>().any { it.text.contains("TemporaryDraft") })
        assertTrue(views(root).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.settings_hotspot_observed_fill_password) })
    }

    @Test fun initialReadBackfillsWhenDialogWasOpenedDuringReadAndThenCancelled() {
        val root = LinearLayout(activity)
        var redraws = 0
        val readStarted = CountDownLatch(1)
        val readRelease = CountDownLatch(1)
        fake.beforeObserve = {
            readStarted.countDown()
            readRelease.await(3, TimeUnit.SECONDS)
        }
        fake.observed = ObservedHotspotInfo(ssid = "SystemCarAp", passwordAvailable = false)
        lateinit var testUi: CarHotspotSettingsUi
        testUi = CarHotspotSettingsUi(activity, changed = {}, redraw = {
            redraws++
            root.removeAllViews()
            testUi.build(root)
        }, controller = fake)
        testUi.build(root)

        assertTrue(readStarted.await(3, TimeUnit.SECONDS))
        val dialog = openHotspotEditor(activity, root, R.string.hotspot_name)
        assertTrue(dialog.isShowing)

        readRelease.countDown()
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        await { redraws > 0 && views(root).filterIsInstance<Button>().any { it.text.contains("SystemCarAp") } }
        assertTrue(views(root).filterIsInstance<Button>().any { it.text.contains("SystemCarAp") })
    }

    private fun card() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; ui.build(this) }
    private fun inputs(root: View) = views(root).filterIsInstance<EditText>().toList()
    private fun button(root: View, id: Int) = views(root).filterIsInstance<Button>().single { it.text == activity.getString(id) }
    private fun select(root: View, title: Int, value: String) {
        views(root).filterIsInstance<Button>().single {
            it.text.startsWith(activity.getString(title) + " · ")
        }.performClick()
        val choice = ShadowAlertDialog.getLatestAlertDialog()
        val index = (0 until choice.listView.adapter.count).single { choice.listView.adapter.getItem(it).toString() == value }
        choice.listView.performItemClick(null, index, index.toLong())
        choice.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun await(predicate: () -> Boolean) {
        val end = System.nanoTime() + 3_000_000_000L
        while (!predicate() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Background operation did not complete", predicate())
    }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
