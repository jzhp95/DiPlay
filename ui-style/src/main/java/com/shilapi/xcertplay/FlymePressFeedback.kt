package com.shilapi.xcertplay

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/** Visual feedback only. Drawable state retains ownership of press/cancel and click dispatch. */
internal object FlymePressFeedback {
    fun install(view: View) {
        if (view.stateListAnimator is PressStateAnimator) return
        view.stateListAnimator = PressStateAnimator()
        // A single stateless listener also permits reinstallation after an external animator change.
        // It stores no View reference; neither a global map nor a captured View extends its lifetime.
        view.removeOnAttachStateChangeListener(detachReset)
        view.addOnAttachStateChangeListener(detachReset)
    }

    private val detachReset = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            val animator = view.stateListAnimator as? PressStateAnimator ?: return
            animator.jumpToCurrentState()
            view.scaleX = 1f
            view.scaleY = 1f
        }
    }

    private class PressStateAnimator : StateListAnimator() {
        init {
            addState(intArrayOf(-android.R.attr.state_enabled), scaleTo(1f, 0))
            addState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed),
                scaleTo(.9f, 120).apply { interpolator = DecelerateInterpolator() })
            addState(intArrayOf(), scaleTo(1f, 220).apply {
                // Platform-only spring approximation: this is an overshoot curve, not the
                // original Flyme SpringAnimation's physical stiffness/damping simulation.
                interpolator = OvershootInterpolator(1.1f)
            })
        }
    }

    private fun scaleTo(scale: Float, durationMillis: Long) = ObjectAnimator().apply {
        setValues(PropertyValuesHolder.ofFloat(View.SCALE_X, scale),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, scale))
        duration = durationMillis
    }
}
