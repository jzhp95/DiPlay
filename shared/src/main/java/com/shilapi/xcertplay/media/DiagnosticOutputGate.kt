package com.shilapi.xcertplay.media

import java.util.concurrent.atomic.AtomicBoolean

/** Explicit diagnostic-only freeze: render exactly the first output, discard all later outputs. */
internal class DiagnosticOutputGate {
    @Volatile private var requested=false
    private val shown=AtomicBoolean(false)
    fun request(){requested=true}
    fun allow():Boolean = !requested || shown.compareAndSet(false,true)
}
