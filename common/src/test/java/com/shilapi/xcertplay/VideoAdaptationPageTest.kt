package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class VideoAdaptationPageTest {
    @org.junit.Before fun isolatedDiagnosticStore() {
        DiagnosticStoreFixture.reset()
        DiagnosticStoreFixture.recover(RuntimeEnvironment.getApplication())
    }
    @org.junit.After fun closeDiagnosticStore() { DiagnosticStoreFixture.reset() }

    private var page: VideoAdaptationPage? = null
    @After fun cleanup() {
        page?.close()
        VideoAdaptationRegistry.current()?.let(VideoAdaptationRegistry::finish)
        VideoAdaptationStore.clear(RuntimeEnvironment.getApplication())
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    private fun button(activity: Activity, id: Int) = views(page!!.root).filterIsInstance<Button>()
        .single { it.text == activity.getString(id) }


    @Test fun comparisonRoundAndReferenceAttestationAreExplicitAndDoNotChangeHevc() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val before = AirPlayPersistence.loadHevcEnabled(activity)
        page = VideoAdaptationPage(activity, DiPlayPalette.LIGHT, {}, combined = true)
        button(activity, R.string.video_trouble_round_a).performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("固定地图区域")
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val context = VideoAdaptationStore.read(activity).getJSONObject("troubleshootingContext")
        assertEquals("A", context.getString("round")); assertFalse(context.getBoolean("referenceDisplayed"))
        button(activity, R.string.video_trouble_reference_confirm).performClick()
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(VideoAdaptationStore.read(activity).getJSONObject("troubleshootingContext").getBoolean("referenceDisplayed"))
        assertEquals(before, AirPlayPersistence.loadHevcEnabled(activity))
        assertTrue(views(page!!.root).filterIsInstance<TextView>().any { it.text.contains("跟手性") })
    }

    @Test fun openingIsReadOnlyAndSamplingWithoutAConnectionDoesNotStartCarPlay() {
        val c = RuntimeEnvironment.getApplication()
        VideoAdaptationStore.clear(c); BackgroundSessionFixture.clear()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        page = VideoAdaptationPage(activity, DiPlayPalette.LIGHT, {})
        activity.setContentView(page!!.root); page!!.setVisible(true)
        assertEquals("{}", VideoAdaptationStore.read(c).toString())
        button(activity, R.string.video_adapt_start).performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(VideoAdaptationRegistry.current())
        assertFalse(VideoAdaptationStore.read(c).has("device"))
        assertTrue(views(page!!.root).filterIsInstance<TextView>()
            .any { it.text == activity.getString(R.string.video_adapt_no_session) })
        page!!.close(); controller.pause().stop().destroy()
    }

    @Test fun leavingPreservesTypedPhoneContextAndColorLinkUsesExistingPage() {
        VideoAdaptationStore.clear(RuntimeEnvironment.getApplication())
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get(); var colors = 0
        page = VideoAdaptationPage(activity, DiPlayPalette.LIGHT, { colors++ })
        page!!.setVisible(true)
        val edits = views(page!!.root).filterIsInstance<EditText>().toList()
        edits[0].setText("iPhone 15"); edits[1].setText("26.0")
        button(activity, R.string.video_adapt_modes).performClick()
        assertEquals(1, colors)
        page!!.setVisible(false)
        val phone = VideoAdaptationStore.read(activity).getJSONObject("phone")
        assertEquals("iPhone 15", phone.getString("model")); assertEquals("26.0", phone.getString("ios"))
        page!!.close(); controller.pause().stop().destroy()
    }

    @Test fun mergedDecoderPageHasOneAutomaticAndOneUnifiedExportEntry() {
        val controller = Robolectric.buildActivity(WheelTestActivity::class.java,
            Intent().putExtra("diagnostics_page", "VIDEO")).setup()
        try {
            val root = controller.get().window.decorView
            val buttons = views(root).filterIsInstance<Button>().toList()
            assertEquals(1, buttons.count { it.text == controller.get().getString(R.string.video_auto_start) })
            assertEquals(1, buttons.count { it.text == controller.get().getString(R.string.video_adapt_export) })
            assertEquals(0, buttons.count { it.text == controller.get().getString(R.string.color_test_export) })
            assertEquals(DiagnosticsTestLayout.Page.COLOR, HostSessionFixture.getField<DiagnosticsTestLayout.Page>(controller.get(), "selectedPage"))
            assertTrue(buttons.any { it.text == controller.get().getString(R.string.video_adapt_start) })
            assertTrue(buttons.any { it.text == controller.get().getString(R.string.color_test_start) })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun videoTabIsReachableByIntentAndRestoredAfterRecreation() {
        val controller = Robolectric.buildActivity(WheelTestActivity::class.java,
            Intent().putExtra("diagnostics_page", "VIDEO")).setup()
        try {
            assertEquals(DiagnosticsTestLayout.Page.COLOR,
                HostSessionFixture.getField<DiagnosticsTestLayout.Page>(controller.get(), "selectedPage"))
            controller.recreate()
            assertEquals(DiagnosticsTestLayout.Page.COLOR,
                HostSessionFixture.getField<DiagnosticsTestLayout.Page>(controller.get(), "selectedPage"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun collectorTokenAttachesWithoutReplacingTheControllerAndForgedTokensDoNothing() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setContentView(FrameLayout(activity))
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val controller = mock(CarPlayController::class.java)
        HostSessionFixture.setField(activity, "controller", controller)
        val attach = activity.javaClass.getDeclaredMethod("attachVideoAdaptationOverlay").apply { isAccessible = true }
        activity.intent = Intent().putExtra(VideoAdaptationRegistry.EXTRA, "forged")
        val before = content.childCount; attach.invoke(activity)
        assertEquals(before, content.childCount)
        val token = VideoAdaptationRegistry.begin()
        activity.intent = Intent().putExtra(VideoAdaptationRegistry.EXTRA, token)
        try {
            attach.invoke(activity)
            assertEquals(before + 1, content.childCount)
            assertSame(controller, HostSessionFixture.getField<CarPlayController>(activity, "controller"))
        } finally {
            HostSessionFixture.getField<VideoAdaptationOverlay?>(activity, "videoAdaptationOverlay")?.close()
            HostSessionFixture.getField<android.os.Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        }
    }

    @Test fun livePictureAndDecoderFormatChangesCannotShareASampleIdentity() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val controller = mock(CarPlayController::class.java)
        val sink = mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java)
        val view = android.view.TextureView(activity).apply { layout(0, 0, 1920, 1080) }
        val display = CarPlaySessionDisplay(1280, 720, 0, false, false, 1920, 1080)
        HostSessionFixture.setField(activity, "controller", controller)
        HostSessionFixture.setField(activity, "sink", sink)
        HostSessionFixture.setField(activity, "videoView", view)
        HostSessionFixture.setField(activity, "sessionDisplay", display)
        val output = com.shilapi.xcertplay.media.VideoDecoderColorEvidence("hash", "decoder", false,
            mapOf("width" to 1280, "height" to 720), 1, true, true, "instance")
        `when`(sink.videoColorOutput()).thenReturn(output)
        `when`(sink.liveVideoCounters()).thenReturn(com.shilapi.xcertplay.media.LiveVideoCounters(10, 10, 1000, 10, 1000))
        BackgroundSessionFixture.store(controller, sink, 1280, 720, activity, display) { it() }
        val method = activity.javaClass.getDeclaredMethod("videoAdaptationSample").apply { isAccessible = true }
        fun sample() = method.invoke(activity) as VideoAdaptationSample?
        val prefs = CarPlayPicture.preferences(activity)
        val wasSet = prefs.contains(CarPlayPicture.BRIGHTNESS)
        val before = prefs.getInt(CarPlayPicture.BRIGHTNESS, 0)
        try {
            val first = requireNotNull(sample())
            assertEquals(1280, first.evidence.getInt("requestedCanvasWidth"))
            assertEquals(1920, first.evidence.getInt("viewWidth"))
            prefs.edit().putInt(CarPlayPicture.BRIGHTNESS, if (before == 0) 10 else 0).commit()
            val changedPicture = requireNotNull(sample())
            assertNotEquals(first.key, changedPicture.key)
            `when`(sink.videoColorOutput()).thenReturn(output.copy(format = mapOf("width" to 1920, "height" to 1080)))
            assertNotEquals(changedPicture.key, requireNotNull(sample()).key)
            HostSessionFixture.setField(activity, "picturePanel", View(activity))
            assertNull(sample())
        } finally {
            if (wasSet) prefs.edit().putInt(CarPlayPicture.BRIGHTNESS, before).commit()
            else prefs.edit().remove(CarPlayPicture.BRIGHTNESS).commit()
            BackgroundSessionFixture.clear()
            HostSessionFixture.getField<android.os.Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        }
    }

    @Test @Config(qualifiers = "w320dp-h900dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactLargeFontScreenshot() = screenshot("compact")
    @Test @Config(qualifiers = "w1000dp-h900dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun expandedLargeFontScreenshot() = screenshot("expanded")
    @Test @Config(qualifiers = "ar-ldrtl-w320dp-h900dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun rtlLargeFontScreenshot() = screenshot("compact-rtl")

    private fun screenshot(name: String) {
        VideoAdaptationStore.clear(RuntimeEnvironment.getApplication())
        RuntimeEnvironment.setFontScale(1.3f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        page = VideoAdaptationPage(activity, DiPlayPalette.LIGHT, {})
        activity.setContentView(page!!.root); page!!.setVisible(true)
        shadowOf(Looper.getMainLooper()).idle()
        val width = activity.resources.displayMetrics.widthPixels
        val height = activity.resources.displayMetrics.heightPixels
        page!!.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        page!!.root.layout(0, 0, width, height)
        val content = page!!.root.getChildAt(0)
        for (text in views(content).filterIsInstance<TextView>().filter { it !is EditText }) {
            val layout = text.layout ?: continue
            assertTrue("Clipped text: ${text.text}", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
            for (line in 0 until layout.lineCount) assertEquals("Ellipsized: ${text.text}", 0, layout.getEllipsisCount(line))
        }
        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(DiPlayPalette.LIGHT.background); content.draw(canvas)
            val dir = File("build/reports/video-adaptation-ui").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle(); page!!.close(); controller.pause().stop().destroy() }
    }

    @Test fun fixtureAndLiveButtonsRouteIndependently() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        var fixtures=0;var live=0
        val colors=ColorTestPage(activity,DiPlayPalette.LIGHT,requestAutomatic={fixtures++},
            combined=true,requestLive={live++})
        try {
            views(colors.root).filterIsInstance<Button>().single{it.text==activity.getString(R.string.video_auto_start)}.performClick()
            assertEquals(1,fixtures);assertEquals(0,live)
            views(colors.root).filterIsInstance<Button>().single{it.text==activity.getString(R.string.video_adapt_start)}.performClick()
            val deadline=System.nanoTime()+5_000_000_000L
            while(live==0 && System.nanoTime()<deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                if(live==0)Thread.sleep(10)
            }
            assertEquals(1,fixtures);assertEquals(1,live)
        } finally { colors.close() }
    }
}
