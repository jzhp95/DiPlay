package com.shilapi.xcertplay

import android.os.Handler
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** The stage line must not fall back to "Getting CarPlay ready" just because the UI is not English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayStageTextTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        BackgroundSessionFixture.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        HostSessionFixture.setField(activity, "stageStatusView", TextView(activity))
        HostSessionFixture.setField(activity, "connectionStageView", TextView(activity))
    }

    @After fun tearDown() {
        HostSessionFixture.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        HostSessionFixture.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        HostSessionFixture.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        HostSessionFixture.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        BackgroundSessionFixture.clear()
    }

    @Test @Config(qualifiers = "en") fun englishStages() = stagesFollowTheConnection()

    @Test @Config(qualifiers = "zh-rCN") fun chineseStages() {
        assertNotEquals("Connection interrupted. Retrying…", activity.getString(R.string.connection_interrupted_retrying))
        stagesFollowTheConnection()
    }

    private fun stagesFollowTheConnection() {
        assertStage(R.string.connection_interrupted_retrying, CarPlayStatus.Failed("Invalid argument"))
        assertStage(R.string.iphone_usb_mode_rejected,
            CarPlayStatus.Failed("CarPlay configuration request transferred -1 of 1 bytes"))
        assertStage(R.string.connect_your_iphone_with_a_usb_cable, CarPlayStatus.WaitingForIphone)
        assertStage(R.string.allow_the_connection_permission_to_continue, CarPlayStatus.RequestingIphonePermission)
        assertStage(R.string.looking_for_your_paired_iphone, CarPlayStatus.WaitingForPairedIphone)
        assertStage(R.string.connecting_to_your_iphone, CarPlayStatus.ConnectingBluetooth)
        assertStage(R.string.opening_carplay, CarPlayStatus.RunningControl)
        assertStage(R.string.reconnecting_to_your_iphone, CarPlayStatus.ControlEnded)
        assertStage(R.string.waiting_for_mfi_coprocessor, CarPlayStatus.WaitingForMfi)
        assertStage(R.string.getting_carplay_ready, CarPlayStatus.OpeningDataPaths)
    }

    private fun assertStage(expected: Int, status: CarPlayStatus) {
        val reporter = ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(
            activity, "createStatusReporter",
            ClassParameter.from(Int::class.javaPrimitiveType, HostSessionFixture.getField<Int>(activity, "restartGeneration")),
        )
        reporter(status)
        val shown = HostSessionFixture.getField<TextView>(activity, "stageStatusView").text.toString()
        assertEquals(status.toString(), activity.getString(expected), shown)
    }

    @Test fun actualStatusReporterPublishesSpecificPhaseAndRejectsAnOldController() {
        val generation = HostSessionFixture.getField<Int>(activity, "restartGeneration")
        fun reporter(value: Int) = ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(
            activity, "createStatusReporter", ClassParameter.from(Int::class.javaPrimitiveType, value))
        reporter(generation)(CarPlayStatus.OpeningDataPaths)
        val caption = HostSessionFixture.getField<TextView>(activity, "connectionStageView")
        assertEquals(activity.getString(R.string.connection_stage_current,
            activity.getString(R.string.opening_usb_data_paths)), caption.text.toString())
        reporter(generation - 1)(CarPlayStatus.MfiReady)
        assertEquals(activity.getString(R.string.connection_stage_current,
            activity.getString(R.string.opening_usb_data_paths)), caption.text.toString())
    }
    @Test fun protocolProgressUpdatesTheRealCaptionButAnOldControllerCannotOverwriteIt() {
        val generation = HostSessionFixture.getField<Int>(activity, "restartGeneration")
        fun listener(value: Int) = ReflectionHelpers.callInstanceMethod<com.shilapi.xcertplay.airplay.AirPlaySessionListener>(
            activity, "createSessionListener", ClassParameter.from(Int::class.javaPrimitiveType, value))
        val active = listener(generation)
        active.onDebugLog("iap2 authentication accepted")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val caption = HostSessionFixture.getField<TextView>(activity, "connectionStageView")
        val authenticated = activity.getString(R.string.connection_stage_current,
            activity.getString(R.string.connection_stage_authenticated))
        assertEquals(authenticated, caption.text.toString())
        active.onDebugLog("airplay rx SETUP / cseq=5 body=100")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val media = activity.getString(R.string.connection_stage_current,
            activity.getString(R.string.connection_stage_media))
        assertEquals("real protocol metadata is redacted from logs but its fixed phase must still display",
            media, caption.text.toString())
        listener(generation - 1).onDebugLog("airplay rx RECORD / cseq=1 body=0")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(media, caption.text.toString())
        HostSessionFixture.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        active.onDebugLog("airplay rx RECORD / cseq=6 body=0")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(media, caption.text.toString())
    }
}
