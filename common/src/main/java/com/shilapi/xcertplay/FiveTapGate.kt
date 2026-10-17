package com.shilapi.xcertplay

/** A shared sequence for the brand icon and label. Long pauses start a new sequence. */
internal class FiveTapGate {
    private var count = 0
    private var lastTap: Long? = null

    fun tap(nowMillis: Long): Boolean {
        if (lastTap == null || nowMillis - lastTap!! !in 0..1_000L) count = 0
        lastTap = nowMillis
        count++
        if (count < 5) return false
        count = 0
        lastTap = null
        return true
    }
}
