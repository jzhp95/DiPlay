package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration

/** Stable preference values; never persist enum ordinals. */
enum class AppAppearance(val key: String) {
    DARK("dark"),
    LIGHT("light"),
    AUTO("auto");

    companion object {
        fun fromKey(key: String?): AppAppearance = entries.firstOrNull { it.key == key } ?: DARK
    }
}

/** Legacy arguments are retained for source compatibility; the Android system owns appearance. */
@Suppress("UNUSED_PARAMETER")
internal fun resolveAppNight(
    appearance: AppAppearance,
    carPlayMode: CarPlayNightMode,
    schedule: CarPlayNightSchedule,
    systemNight: Boolean,
    minuteOfDay: Int,
    hostNight: Boolean?,
): Boolean = systemNight

internal fun shouldDeferAppearanceRender(windowKeyLearning: Boolean, serviceKeyLearning: Boolean): Boolean =
    windowKeyLearning || serviceKeyLearning

@Suppress("UNUSED_PARAMETER")
internal fun Context.resolveAppNightNow(hostNight: Boolean? = AppAppearanceRuntime.hostNight()): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/**
 * The resumed CarPlay host owns the live controller result. Identity ownership prevents a stale
 * activity from clearing a newer host after recreation or multi-window task replacement.
 */
internal object AppAppearanceRuntime {
    private val lock = Any()
    private var hostOwner: Any? = null
    private var hostNight: Boolean? = null
    private val observers = linkedMapOf<Any, (Boolean?) -> Unit>()

    fun hostNight(): Boolean? = synchronized(lock) { hostNight }

    fun publishHost(owner: Any, night: Boolean) {
        val callbacks = synchronized(lock) {
            val changed = hostNight != night
            hostOwner = owner
            hostNight = night
            if (changed) observers.values.toList() else emptyList()
        }
        callbacks.forEach { callback -> callback(night) }
    }

    fun clearHost(owner: Any) {
        val callbacks = synchronized(lock) {
            if (hostOwner !== owner) return
            hostOwner = null
            if (hostNight == null) return
            hostNight = null
            observers.values.toList()
        }
        callbacks.forEach { callback -> callback(null) }
    }

    /**
     * Returns an idempotent removal callback. Callers remain responsible for lifecycle cleanup.
     * Observers run on the publisher's thread and must not throw: an exception reaches the host's lifecycle callback.
     */
    fun observeHost(observer: (Boolean?) -> Unit): () -> Unit {
        val token = Any()
        val initial = synchronized(lock) {
            observers[token] = observer
            hostNight
        }
        observer(initial)
        return { synchronized(lock) { observers.remove(token) } }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            hostOwner = null
            hostNight = null
            observers.clear()
        }
    }
}
