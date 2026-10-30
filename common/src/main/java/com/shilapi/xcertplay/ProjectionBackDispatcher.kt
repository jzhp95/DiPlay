package com.shilapi.xcertplay

/** Local dialogs are handled by the host before this projection-only gesture. */
internal class ProjectionBackDispatcher {
    enum class Result { FORWARDED, DESKTOP, FALLBACK }
    private var firstAt: Long? = null
    private var firstSession: Any? = null
    fun reset() { firstAt = null; firstSession = null }
    fun press(session: Any?, nowMillis: Long, forward: () -> Boolean): Result {
        val at = firstAt
        if (session != null && session === firstSession && at != null && nowMillis - at in 0 until 1500) {
            reset(); return Result.DESKTOP
        }
        reset()
        if (session == null || !forward()) return Result.FALLBACK
        firstAt = nowMillis; firstSession = session
        return Result.FORWARDED
    }
}
