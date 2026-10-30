package com.shilapi.xcertplay.diagnostics

import java.lang.ref.WeakReference
import java.util.UUID

/** One main-process diagnostic owner, including retirement after a timeout. No disk or Binder calls. */
internal object DiagnosticLease {
    data class Ticket internal constructor(val runId: String,val sessionGeneration: Long,
        val sessionToken: Any?,val leaseId: String,val blocksProduction:Boolean)
    private data class Held(val ticket: Ticket,var released: (() -> Boolean)? = null)
    private var held: Held? = null
    private var generation=0L
    private var lastSession=WeakReference<Any>(null)
    @Synchronized fun acquire(runId: String, sessionToken: Any?, blocksProduction:Boolean=true): Ticket? {
        reap()
        val phase=com.shilapi.xcertplay.DiPlaySessionService.liveRuntime?.snapshot()?.phase
        if(phase==com.shilapi.xcertplay.session.RuntimePhase.STOPPING || phase==com.shilapi.xcertplay.session.RuntimePhase.BLOCKED_RELEASE)return null
        if(held!=null)return null
        if(sessionToken==null || lastSession.get() !== sessionToken){generation++;lastSession=WeakReference(sessionToken)}
        return Ticket(runId,generation,sessionToken,UUID.randomUUID().toString(),blocksProduction).also{held=Held(it)}
    }
    data class Production(val controller:com.shilapi.xcertplay.orchestration.CarPlayController,
        val sink:com.shilapi.xcertplay.media.AndroidMediaSink,val width:Int,val height:Int)
    @Synchronized fun production(ticket:Ticket):Production? {
        val runtime=com.shilapi.xcertplay.DiPlaySessionService.liveRuntime ?: return null
        val connection=runtime.currentConnection ?: return null
        if(!owns(ticket,connection.controller))return null
        val request=runtime.currentRequest ?: return null
        return Production(connection.controller,connection.sink,request.media.width,request.media.height)
    }
    @Synchronized fun owns(ticket: Ticket, sessionToken: Any?): Boolean =
        held?.ticket === ticket && ticket.sessionToken === sessionToken
    @Synchronized fun release(ticket: Ticket, confirmed: Boolean, actualRelease: (() -> Boolean)? = null) {
        val current=held ?: return
        if(current.ticket !== ticket)return
        if(confirmed)held=null else current.released=actualRelease
    }
    @Synchronized fun blocksProduction():Boolean {reap();return held?.ticket?.blocksProduction==true}
    @Synchronized fun busy(): Boolean {reap();return held!=null}
    private fun reap() {
        val current=held ?: return
        if(runCatching{current.released?.invoke()}.getOrDefault(false)==true && held===current)held=null
    }
}
