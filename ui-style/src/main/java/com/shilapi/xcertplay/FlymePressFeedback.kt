package com.shilapi.xcertplay

import android.animation.Animator
import android.animation.StateListAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/** Drawable state drives a real spring; business click/touch handlers retain ownership. */
internal object FlymePressFeedback {
    private const val PRESSED_SCALE = .9f
    private const val STIFFNESS = 250f
    private const val DAMPING = .89f
    private const val MINIMUM_CHANGE = .002f

    fun install(view: View) {
        if (view.stateListAnimator is PressStateAnimator) return
        view.stateListAnimator = PressStateAnimator()
    }

    private class PressStateAnimator : StateListAnimator() {
        private val motion = Motion(this)
        init {
            addState(intArrayOf(-android.R.attr.state_enabled), Trigger(motion, 1f, true))
            addState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed), Trigger(motion, PRESSED_SCALE))
            addState(intArrayOf(), Trigger(motion, 1f))
        }
        fun reset() = motion.snap(1f)
    }

    private class Motion(private val animator: PressStateAnimator) : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (view.stateListAnimator !== animator) unbind()
        }
        override fun onViewDetachedFromWindow(view: View) {
            snap(1f)
            if (view.stateListAnimator !== animator) unbind()
        }
        private fun unbind() {
            snap(1f)
            view?.removeOnAttachStateChangeListener(this)
            view = null
            x = null
            y = null
        }
        private var view: View? = null
        private var x: SpringAnimation? = null
        private var y: SpringAnimation? = null
        private var owner: Trigger? = null
        fun bind(next: View?) {
            if (view === next) return
            snap(1f)
            view?.removeOnAttachStateChangeListener(this)
            view = next
            next?.addOnAttachStateChangeListener(this)
            fun animation(property: androidx.dynamicanimation.animation.FloatPropertyCompat<View>) =
                next?.let { target -> SpringAnimation(target, property).apply {
                    spring = SpringForce(1f).setStiffness(STIFFNESS).setDampingRatio(DAMPING)
                    setMinimumVisibleChange(MINIMUM_CHANGE)
                    addUpdateListener { _, _, _ ->
                        if (target.stateListAnimator !== animator) unbind()
                    }
                    addEndListener { _, _, _, _ ->
                        if (x?.isRunning != true && y?.isRunning != true) {
                            val completed = owner
                            owner = null
                            completed?.complete()
                        }
                    }
                } }
            x = animation(DynamicAnimation.SCALE_X)
            y = animation(DynamicAnimation.SCALE_Y)
        }
        fun start(trigger: Trigger, target: Float, immediate: Boolean) {
            owner = trigger
            if (view == null || immediate || !ValueAnimator.areAnimatorsEnabled()) {
                snap(target)
                trigger.complete()
            } else {
                // animateToFinalPosition keeps velocity when the next state arrives mid-flight.
                x?.animateToFinalPosition(target)
                y?.animateToFinalPosition(target)
            }
        }
        fun release(trigger: Trigger) { if (owner === trigger) owner = null }
        fun snap(target: Float) {
            val previous = owner
            owner = null
            x?.cancel(); y?.cancel()
            view?.scaleX = target
            view?.scaleY = target
            previous?.complete()
        }
    }

    /** Animator adapter lets StateListAnimator arbitrate press/cancel without a TouchListener. */
    private class Trigger(private val motion: Motion, private val target: Float, private val immediate: Boolean = false) : Animator() {
        private var running = false
        private var delay = 0L
        override fun setTarget(target: Any?) {
            // StateListAnimator clears each completed/cancelled trigger's target. The shared
            // spring still belongs to the View, including while it is being retargeted.
            if (target is View) motion.bind(target)
        }
        override fun getStartDelay() = delay
        override fun setStartDelay(startDelay: Long) { delay = startDelay.coerceAtLeast(0) }
        override fun getDuration() = 0L // Settling is determined by the spring, not a fixed duration.
        override fun setDuration(duration: Long): Animator = this
        override fun setInterpolator(value: TimeInterpolator?) = Unit
        override fun isRunning() = running
        override fun isStarted() = running
        override fun start() {
            running = true
            listeners?.toList()?.forEach { it.onAnimationStart(this) }
            motion.start(this, target, immediate)
        }
        override fun cancel() {
            if (!running) return
            // StateListAnimator cancels the old trigger immediately before starting the next.
            // Leave the springs alive for retargeting, preserving their current velocity.
            motion.release(this)
            listeners?.toList()?.forEach { it.onAnimationCancel(this) }
            complete()
        }
        override fun end() { motion.snap(target); complete() }
        fun complete() {
            if (!running) return
            running = false
            listeners?.toList()?.forEach { it.onAnimationEnd(this) }
        }
    }
}
