package com.shilapi.xcertplay

import android.view.Surface
import android.graphics.Matrix
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostVideoSurfaceTest {
    private class AttachedTexture(activity: CarPlayHostActivity, private val accelerated: Boolean) : TextureView(activity) {
        override fun isAttachedToWindow() = true
        override fun isHardwareAccelerated() = accelerated
    }

    @Test fun acceleratedWindowKeepsItsTextureAndTheOverlayOrder() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val texture = AttachedTexture(activity, true)
        val root = FrameLayout(activity).apply { addView(texture) }
        set(activity, "videoView", texture)
        observe(activity, texture)
        val probe = field(activity, "videoSurfaceProbe") as ViewTreeObserver.OnPreDrawListener
        assertTrue(probe.onPreDraw())
        assertSame(texture, field(activity, "videoView"))
        assertSame(texture, root.getChildAt(0))
        assertNull(field(activity, "fallbackVideoView"))
        assertNull(field(activity, "videoSurfaceProbe"))
    }

    @Test fun softwareWindowReplacesTextureWithoutShrinkingTheNegotiatedViewport() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val texture = AttachedTexture(activity, false)
        val overlay = CarPlayBlackCornerOverlay(activity).apply { radiusDp = 24 }
        val root = FrameLayout(activity).apply {
            addView(texture, FrameLayout.LayoutParams(-1, -1))
            addView(overlay, FrameLayout.LayoutParams(-1, -1))
        }
        set(activity, "videoView", texture)
        set(activity, "blackCornerOverlay", overlay)
        set(activity, "sessionDisplay", CarPlaySessionDisplay(1920, 990, Surface.ROTATION_0,
            true, true, 1920, 990))
        observe(activity, texture)
        val probe = field(activity, "videoSurfaceProbe") as ViewTreeObserver.OnPreDrawListener
        assertFalse(probe.onPreDraw())
        val viewport = field(activity, "videoView") as FrameLayout
        val fallback = field(activity, "fallbackVideoView") as SurfaceView
        assertNull(texture.parent)
        assertSame(viewport, root.getChildAt(0))
        assertSame(overlay, root.getChildAt(1))
        assertEquals(24, overlay.radiusDp)
        assertSame(fallback, viewport.getChildAt(0))
        viewport.layout(0, 0, 1920, 942)
        val content = CarPlayVideoLayout.fit(1920, 990, 1920, 942)
        val bounds = CarPlaySurfaceBounds.from(content)
        val params = fallback.layoutParams as FrameLayout.LayoutParams
        assertEquals(bounds.width, params.width)
        assertEquals(bounds.height, params.height)
        assertEquals(bounds.left, params.leftMargin)
        assertEquals(bounds.top, params.topMargin)
        assertEquals(1920, viewport.width)
        assertEquals(942, viewport.height)
        assertNull(field(activity, "pictureBinding"))
    }

    private fun observe(activity: CarPlayHostActivity, texture: TextureView) {
        activity.javaClass.getDeclaredMethod("observeVideoWindow", TextureView::class.java)
            .apply { isAccessible = true }.invoke(activity, texture)
    }

    @Test fun reconnectedSessionReplacesTheOldRotationTransformWithoutAnotherWindowResize() {
        for ((oldWidth, oldHeight) in listOf(2800 to 1260, 1260 to 2800)) {
            val width = oldHeight; val height = oldWidth
            val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
            val texture = AttachedTexture(activity, true).apply { layout(0, 0, width, height) }
            set(activity, "videoView", texture)
            set(activity, "sessionDisplay", CarPlaySessionDisplay(oldWidth, oldHeight, 0, true, true, oldWidth, oldHeight))
            updateLayout(activity, width, height)
            assertFalse("old canvas must reproduce the stale letterbox transform", texture.getTransform(Matrix()).isIdentity)
            try {
                BackgroundSessionFixture.store(org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java),
                    org.mockito.Mockito.mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java), width, height, activity,
                    CarPlaySessionDisplay(width, height, 0, true, true, width, height)) { it() }
                assertTrue("new session must fill the already resized window", texture.getTransform(Matrix()).isIdentity)
            } finally { BackgroundSessionFixture.clear() }
        }
    }

    @Test fun reconnectedSessionAlsoReplacesFallbackSurfaceBoundsWithoutAnotherResize() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val viewport = FrameLayout(activity).apply { layout(0, 0, 1260, 2800) }
        val fallback = SurfaceView(activity)
        viewport.addView(fallback, FrameLayout.LayoutParams(-1, -1))
        set(activity, "videoView", viewport)
        set(activity, "fallbackVideoView", fallback)
        set(activity, "sessionDisplay", CarPlaySessionDisplay(2800, 1260, 0, true, true, 2800, 1260))
        updateLayout(activity, 1260, 2800)
        assertTrue((fallback.layoutParams as FrameLayout.LayoutParams).topMargin > 0)
        try {
            BackgroundSessionFixture.store(org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java),
                org.mockito.Mockito.mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java), 1260, 2800, activity,
                CarPlaySessionDisplay(1260, 2800, 0, true, true, 1260, 2800)) { it() }
            val params = fallback.layoutParams as FrameLayout.LayoutParams
            assertEquals(1260, params.width); assertEquals(2800, params.height)
            assertEquals(0, params.leftMargin); assertEquals(0, params.topMargin)
        } finally { BackgroundSessionFixture.clear() }
    }

    private fun updateLayout(activity: CarPlayHostActivity, width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("updateVideoLayout", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, width, height)
    }

    private fun field(activity: CarPlayHostActivity, name: String): Any? =
        HostSessionFixture.getField<Any?>(activity, name)

    private fun set(activity: CarPlayHostActivity, name: String, value: Any) {
        HostSessionFixture.setField(activity, name, value)
    }
}
