package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper

/** A stalled decoder must not overlap the next connection, even after its page is recreated. */
internal object AutomaticVideoCleanup {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private class Ticket(val context: Context, val id: String, var done: (() -> Unit)?) {
        var timedOut = false
        var timeout: Runnable? = null
    }
    private var pending: Ticket? = null
    fun blocked(): Boolean = pending != null
    fun await(context: Context, id: String, enqueue: (() -> Unit) -> Unit, done: () -> Unit) {
        check(pending == null)
        val ticket = Ticket(context.applicationContext, id, done)
        pending = ticket
        ticket.timeout = Runnable { complete(ticket, false) }.also { main.postDelayed(it, 10_000) }
        try { enqueue { main.post { complete(ticket, true) } } }
        catch (_: Exception) { complete(ticket, false) }
    }
    private fun complete(ticket: Ticket, released: Boolean) {
        if (pending !== ticket) return
        if (released) {
            pending = null; ticket.timeout?.let(main::removeCallbacks)
        } else {
            if (ticket.timedOut) return
            ticket.timedOut = true
            // Keep the process guard until actual release, but drop the Activity callback now.
        }
        val done = ticket.done; ticket.done = null; done?.invoke()
    }
}
