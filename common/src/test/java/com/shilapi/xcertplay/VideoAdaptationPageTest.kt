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

    @Test fun openingIsReadOnlyAndSamplingWithoutAConnectionDoesNotStartCarPlay() {
        val c = RuntimeEnvironment.getApplication()
        VideoAdaptationStore.clear(c); CarPlayBackgroundSession.clear()
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

    @Test fun videoTabIsReachableByIntentAndRestoredAfterRecreation() {
        val controller = Robolectric.buildActivity(WheelTestActivity::class.java,
            Intent().putExtra("diagnostics_page", "VIDEO")).setup()
        try {
            assertEquals(DiagnosticsTestLayout.Page.VIDEO,
                ReflectionHelpers.getField<DiagnosticsTestLayout.Page>(controller.get(), "selectedPage"))
            controller.recreate()
            assertEquals(DiagnosticsTestLayout.Page.VIDEO,
                ReflectionHelpers.getField<DiagnosticsTestLayout.Page>(controller.get(), "selectedPage"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun collectorTokenAttachesWithoutReplacingTheControllerAndForgedTokensDoNothing() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setContentView(FrameLayout(activity))
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val controller = mock(CarPlayController::class.java)
        ReflectionHelpers.setField(activity, "controller", controller)
        val attach = activity.javaClass.getDeclaredMethod("attachVideoAdaptationOverlay").apply { isAccessible = true }
        activity.intent = Intent().putExtra(VideoAdaptationRegistry.EXTRA, "forged")
        val before = content.childCount; attach.invoke(activity)
        assertEquals(before, content.childCount)
        val token = VideoAdaptationRegistry.begin()
        activity.intent = Intent().putExtra(VideoAdaptationRegistry.EXTRA, token)
        try {
            attach.invoke(activity)
            assertEquals(before + 1, content.childCount)
            assertSame(controller, ReflectionHelpers.getField<CarPlayController>(activity, "controller"))
        } finally {
            ReflectionHelpers.getField<VideoAdaptationOverlay?>(activity, "videoAdaptationOverlay")?.close()
            ReflectionHelpers.getField<android.os.Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        }
    }

    @Test fun livePictureAndDecoderFormatChangesCannotShareASampleIdentity() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val controller = mock(CarPlayController::class.java)
        val sink = mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java)
        val view = android.view.TextureView(activity).apply { layout(0, 0, 1920, 1080) }
        val display = CarPlaySessionDisplay(1280, 720, 0, false, false, 1920, 1080)
        ReflectionHelpers.setField(activity, "controller", controller)
        ReflectionHelpers.setField(activity, "sink", sink)
        ReflectionHelpers.setField(activity, "videoView", view)
        ReflectionHelpers.setField(activity, "sessionDisplay", display)
        val output = com.shilapi.xcertplay.media.VideoDecoderColorEvidence("hash", "decoder", false,
            mapOf("width" to 1280, "height" to 720), 1, true, true, "instance")
        `when`(sink.videoColorOutput()).thenReturn(output)
        `when`(sink.liveVideoCounters()).thenReturn(com.shilapi.xcertplay.media.LiveVideoCounters(10, 10, 1000, 10, 1000))
        CarPlayBackgroundSession.store(controller, sink, 1280, 720, activity, display) { it() }
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
            ReflectionHelpers.setField(activity, "picturePanel", View(activity))
            assertNull(sample())
        } finally {
            if (wasSet) prefs.edit().putInt(CarPlayPicture.BRIGHTNESS, before).commit()
            else prefs.edit().remove(CarPlayPicture.BRIGHTNESS).commit()
            CarPlayBackgroundSession.clear()
            ReflectionHelpers.getField<android.os.Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
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
}
