package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class FlymePressFeedbackTest {
    private val activityController = Robolectric.buildActivity(Activity::class.java).setup().visible()
    private val activity = activityController.get()
    private val root = FrameLayout(activity).also(activity::setContentView)

    @After fun tearDown() { activityController.pause().stop().destroy() }

    @Test fun styledButtonShrinksWhilePressedAndReturnsAfterRelease() {
        val button = styledButton()
        button.isPressed = true
        button.jumpDrawablesToCurrentState()
        assertEquals(.9f, button.scaleX, .001f)
        assertEquals(.9f, button.scaleY, .001f)
        button.isPressed = false
        button.jumpDrawablesToCurrentState()
        assertEquals(1f, button.scaleX, .001f)
        assertEquals(1f, button.scaleY, .001f)
    }
    @Test fun disablingAPressedButtonRestoresBothAxes() {
        val button = styledButton()
        button.isPressed = true
        button.jumpDrawablesToCurrentState()
        assertScale(button, .9f)
        button.isEnabled = false
        button.jumpDrawablesToCurrentState()
        assertScale(button, 1f)
    }

    @Test fun cancelRestoresScaleWithoutClicking() {
        val button = styledButton()
        var clicks = 0
        button.setOnClickListener { clicks++ }
        touch(button, MotionEvent.ACTION_DOWN)
        button.jumpDrawablesToCurrentState()
        assertScale(button, .9f)
        touch(button, MotionEvent.ACTION_CANCEL)
        button.jumpDrawablesToCurrentState()
        shadowOf(Looper.getMainLooper()).idle()
        assertScale(button, 1f)
        assertEquals(0, clicks)
    }

    @Test fun detachingDuringPressRestoresScaleImmediatelyAndCanBePressedAgain() {
        val button = styledButton()
        assertTrue(button.isAttachedToWindow)
        button.isPressed = true
        button.jumpDrawablesToCurrentState()
        assertScale(button, .9f)
        root.removeView(button)
        assertScale(button, 1f)
        root.addView(button)
        button.isPressed = false
        button.isPressed = true
        button.jumpDrawablesToCurrentState()
        assertScale(button, .9f)
    }

    @Test fun installationPreservesExistingClickAndTouchHandlers() {
        val button = Button(activity)
        var clicks = 0
        val actions = mutableListOf<Int>()
        button.setOnClickListener { clicks++ }
        button.setOnTouchListener { _, event -> actions += event.actionMasked; false }
        root.addView(button, FrameLayout.LayoutParams(300, 100))
        FlymeAutoStyle.button(button, DiPlayPalette.LIGHT)
        layout()
        touch(button, MotionEvent.ACTION_DOWN)
        touch(button, MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions)
        assertEquals(1, clicks)
    }

    @Test fun consumingBusinessTouchHandlerKeepsItsOwnership() {
        val button = Button(activity)
        var clicks = 0
        val actions = mutableListOf<Int>()
        button.setOnClickListener { clicks++ }
        button.setOnTouchListener { _, event -> actions += event.actionMasked; true }
        root.addView(button, FrameLayout.LayoutParams(300, 100))
        FlymeAutoStyle.button(button, DiPlayPalette.LIGHT)
        layout()
        touch(button, MotionEvent.ACTION_DOWN)
        touch(button, MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions)
        assertEquals(0, clicks)
    }

    @Test fun repeatedStylingKeepsOneAnimatorAndDoesNotAccumulateAttachListeners() {
        val button = styledButton()
        val animator = button.stateListAnimator
        val count = attachListenerCount(button)
        repeat(3) {
            FlymePressFeedback.install(button)
            FlymeAutoStyle.button(button, DiPlayPalette.DARK)
        }
        assertSame(animator, button.stateListAnimator)
        assertEquals(count, attachListenerCount(button))
        button.isPressed = true
        button.jumpDrawablesToCurrentState()
        assertScale(button, .9f)
        root.removeView(button)
        assertScale(button, 1f)
    }

    private fun styledButton(): Button = Button(activity).also {
        root.addView(it, FrameLayout.LayoutParams(300, 100))
        FlymeAutoStyle.button(it, DiPlayPalette.LIGHT)
        layout()
    }

    private fun layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 600, 300)
    }

    private fun assertScale(button: Button, expected: Float) {
        assertEquals(expected, button.scaleX, .001f)
        assertEquals(expected, button.scaleY, .001f)
    }

    private fun touch(button: Button, action: Int) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, 10f, 10f, 0).let {
            try { button.dispatchTouchEvent(it) } finally { it.recycle() }
        }
    }

    private fun attachListenerCount(button: Button): Int {
        val info = ReflectionHelpers.getField<Any>(button, "mListenerInfo")
        return ReflectionHelpers.getField<List<*>>(info, "mOnAttachStateChangeListeners").size
    }

}
