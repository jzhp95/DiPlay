package com.shilapi.xcertplay

/** End-of-input and decoder completion are independent: a short clip can still be in flight. */
internal class DecoderDiagnosticsCaptureWindow(private val deadlineMs:Long) {
    enum class Action { READ, WAIT, COMPLETE, TIMEOUT }
    var exhausted=false
    fun action(nowMs:Long,rendered:Long):Action=when {
        rendered>0->Action.COMPLETE
        nowMs>=deadlineMs->Action.TIMEOUT
        exhausted->Action.WAIT
        else->Action.READ
    }
}
