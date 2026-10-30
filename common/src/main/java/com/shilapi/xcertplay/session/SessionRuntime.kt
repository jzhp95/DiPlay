package com.shilapi.xcertplay.session

import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Single state-thread owner. Native teardown is serialized away from the state/UI thread. */
internal class SessionRuntime(
    private val factory:SessionConnectionFactory,
    private val dispatch:(()->Unit)->Unit,
    private val schedule:(Long,()->Unit)->(()->Unit),
    private val teardown:Executor=Executors.newSingleThreadExecutor{Thread(it,"session-release").apply{isDaemon=true}},
) {
    private data class Observer(val id:Long,@Volatile var callback:SessionObserver?)
    private val observers=linkedMapOf<Long,Observer>()
    private class Events(@Volatile var listener:AirPlaySessionListener?,@Volatile var status:((CarPlayStatus)->Unit)?,
        @Volatile var stream:((Int,Boolean)->Unit)?)
    private var events:Events?=null
    internal val currentConnection:SessionConnection? get()=connection
    internal val currentRequest:SessionStartRequest? get()=requested
    private var uiOwner:Int?=null
    private var uiGeneration=0L
    private var stopGeneration=0L
    private var configurationPending=false
    val configurationRequired:Boolean get()=configurationPending
    fun claimUi(owner:Int):SessionUiCommand {
        if(uiOwner!=owner){uiOwner=owner;uiGeneration++}
        return SessionUiCommand(owner,uiGeneration,stopGeneration)
    }
    fun commandForUi(owner:Int):SessionUiCommand? = if(uiOwner==owner)SessionUiCommand(owner,uiGeneration,stopGeneration)else null
    fun acceptsUiCommand(command:SessionUiCommand)=uiOwner==command.owner && uiGeneration==command.ownerGeneration && stopGeneration==command.stopGeneration
    fun releaseUi(command:SessionUiCommand){
        if(uiOwner==command.owner && uiGeneration==command.ownerGeneration){uiOwner=null;uiGeneration++}
    }
    fun setConfigurationPending(command:SessionUiCommand,pending:Boolean):CommandReceipt {
        if(!acceptsUiCommand(command))return CommandReceipt(false,"stale_ui_command")
        if(!pending)return CommandReceipt(false,"configuration_requires_snapshot")
        configurationPending=true
        return CommandReceipt(true)
    }
    fun confirmConfigurationFromUi(request:SessionStartRequest,command:SessionUiCommand):CommandReceipt {
        if(!acceptsUiCommand(command) || requested!==request)return CommandReceipt(false,"stale_configuration")
        configurationPending=false
        if(connection==null && !stopRunning && restartWanted && foreground){
            restartWanted=false
            return start(request)
        }
        return CommandReceipt(true)
    }
    fun startFromUi(request:SessionStartRequest,command:SessionUiCommand):CommandReceipt {
        if(!acceptsUiCommand(command))return CommandReceipt(false,"stale_ui_command")
        configurationPending=false
        return start(request)
    }
    fun reconfigureFromUi(request:SessionStartRequest,reason:String,command:SessionUiCommand):CommandReceipt {
        if(!acceptsUiCommand(command))return CommandReceipt(false,"stale_ui_command")
        if(stopRunning && !restartWanted)return CommandReceipt(false,"explicit_stop_in_progress")
        configurationPending=false
        return reconfigure(request,reason)
    }
    fun isUiOwner(owner:Int)=uiOwner==owner
    fun reconfigure(request:SessionStartRequest,reason:String):CommandReceipt {
        if(stopRunning && !restartWanted)return CommandReceipt(false,"explicit_stop_in_progress")
        requested=request;startupBudget.manualRetry();authenticationRetried=false
        restartWanted=true;reconnectCancel?.invoke();reconnectCancel=null
        stopInternal(reason,true){}
        return CommandReceipt(true)
    }
    fun attachEvents(listener:AirPlaySessionListener,status:(CarPlayStatus)->Unit,stream:(Int,Boolean)->Unit):SessionSubscription {
        val binding=Events(listener,status,stream);events=binding
        return SessionSubscription{binding.listener=null;binding.status=null;binding.stream=null;dispatch{if(events===binding)events=null}}
    }
    private inline fun notifyListener(action:(AirPlaySessionListener)->Unit){events?.listener?.let{runCatching{action(it)}}}

    private var output:ProjectionSurfaceBinding?=null
    private class Detach(val request:com.shilapi.xcertplay.media.AndroidMediaSink.SurfaceDetach) {
        val releases=mutableListOf<()->Unit>()
    }
    private val detaching=java.util.IdentityHashMap<android.view.Surface,Detach>()
    private fun beginDetach(surface:android.view.Surface,request:com.shilapi.xcertplay.media.AndroidMediaSink.SurfaceDetach,waitForFramework:Boolean=false) {
        // Keep a single acknowledgement owner for this wrapper, including any pending release callback.
        val previous=detaching[surface]
        val pending=Detach(request)
        previous?.releases?.let{pending.releases.addAll(it)}
        detaching[surface]=pending
        // SurfaceHolder owns its Surface and destroys it when its callback returns. Preserve its
        // existing bounded hand-off; app-owned TextureView wrappers use the asynchronous path.
        if(waitForFramework && runCatching{request.await()}.getOrDefault(false)){
            detaching.remove(surface)
            pending.releases.toList().forEach{it()}
        }else awaitDetach(surface,pending)
    }
    private fun awaitDetach(surface:android.view.Surface,pending:Detach) {
        try {
            teardown.execute {
                val confirmed=runCatching{pending.request.await()}.getOrDefault(false)
                dispatch {
                    if(detaching[surface]!==pending)return@dispatch
                    if(confirmed){
                        detaching.remove(surface)
                        pending.releases.toList().forEach{it()}
                        pending.releases.clear()
                    } else schedule(250){if(detaching[surface]===pending)awaitDetach(surface,pending)}
                }
            }
        }catch(_:java.util.concurrent.RejectedExecutionException){
            // Retain the wrapper when the worker is unavailable; never invent a release receipt.
            schedule(250){if(detaching[surface]===pending)awaitDetach(surface,pending)}
        }
    }
    fun bindOutput(binding:ProjectionSurfaceBinding):CommandReceipt {
        val owner=connection ?: return CommandReceipt(false,"no_connection")
        if(state.identity!=binding.identity || uiOwner!=binding.uiOwner || stopRunning)return CommandReceipt(false,"stale_output_binding")
        val previous=output
        output=binding
        owner.sink.setSurface(110,binding.surface)
        if(binding.mirrorToAlt)owner.sink.setSurface(111,binding.surface)
        if(previous!=null && previous.surface!==binding.surface)
            beginDetach(previous.surface,owner.sink.beginSurfaceDetach(previous.surface,false))
        return CommandReceipt(true)
    }
    fun unbindOutput(bindingId:String,surface:android.view.Surface,park:Boolean,waitForFramework:Boolean=false):Boolean {
        val current=output
        val matches=current?.bindingId==bindingId && current.surface===surface
        if(matches)output=null
        if(matches || waitForFramework){
            val owner=connection
            if(owner!=null)beginDetach(surface,owner.sink.beginSurfaceDetach(surface,park),waitForFramework)
            else if(waitForFramework){
                val pending=detaching[surface]
                if(pending!=null && runCatching{pending.request.await()}.getOrDefault(false)){
                    detaching.remove(surface);pending.releases.toList().forEach{it()}
                }
            }
        }
        return !detaching.containsKey(surface)
    }
    fun releaseOutputWhenReady(surface:android.view.Surface,release:()->Unit) {
        val pending=detaching[surface]
        if(pending==null)release()else pending.releases.add(release)
    }

    private var observerId=0L
    private var generation=0L
    private var foreground=false
    private val startupBudget=com.shilapi.xcertplay.WirelessStartupRetryBudget()
    private var reconnectAttempts=0
    private var activeSession:AirPlaySession?=null
    private var authenticationRetried=false
    @Volatile private var connection:SessionConnection?=null
    @Volatile private var requested:SessionStartRequest?=null
    private var reconnectCancel:(()->Unit)?=null
    private var restartWanted=false
    private var stopRunning=false
    private val stopWaiters=mutableListOf<(StopResult)->Unit>()
    @Volatile private var state=SessionState()
    fun snapshot()=state
    fun foregroundReady(ready:Boolean){foreground=ready}
    fun subscribe(observer:SessionObserver):SessionSubscription {
        val entry=Observer(++observerId,observer);observers[entry.id]=entry
        runCatching{observer.changed(state)}
        return SessionSubscription{entry.callback=null;dispatch{observers.remove(entry.id)}}
    }
    private fun publish(next:SessionState){
        state=next
        com.shilapi.xcertplay.diagnostics.RuntimeEvidence.sessions.record(next.identity?.id,next.identity?.generation,"session_state",
            mapOf("phase" to next.phase.name,"status" to next.protocolStatus?.javaClass?.simpleName))
        observers.values.toList().forEach{entry->runCatching{entry.callback?.changed(next)}}
    }
    fun start(request:SessionStartRequest):CommandReceipt {
        if(!foreground)return CommandReceipt(false,"foreground_not_ready")
        if(state.phase==RuntimePhase.BLOCKED_RELEASE || stopRunning)return CommandReceipt(false,"waiting_for_actual_release")
        if(connection!=null)return if(requested==request)CommandReceipt(true)else CommandReceipt(false,"explicit_reconnect_required")
        reconnectCancel?.invoke();reconnectCancel=null
        requested=request;restartWanted=false
        if(configurationPending){
            restartWanted=true
            publish(SessionState(phase=RuntimePhase.WAITING_PREREQUISITES,failure="display_configuration_pending"))
            return CommandReceipt(false,"display_configuration_pending")
        }
        val missing=try{factory.readiness(request)}catch(error:Exception){
            val failure="readiness_failed:"+error.javaClass.simpleName
            publish(SessionState(phase=RuntimePhase.FAILED,failure=failure))
            return CommandReceipt(false,failure)
        }
        if(missing!=null){
            publish(SessionState(phase=RuntimePhase.WAITING_PREREQUISITES,failure=missing))
            if(missing=="diagnostic_active" && requested===request && state.phase==RuntimePhase.WAITING_PREREQUISITES){
                // One cancellable timer; waiting for a lease never consumes a connection retry.
                reconnectCancel=schedule(500){
                    reconnectCancel=null
                    if(requested===request && state.phase==RuntimePhase.WAITING_PREREQUISITES &&
                        state.failure=="diagnostic_active")start(request)
                }
            }
            return CommandReceipt(false,missing)
        }
        val identity=SessionIdentity(UUID.randomUUID().toString(),++generation)
        publish(SessionState(identity,RuntimePhase.STARTING))
        if(state.identity!=identity || state.phase!=RuntimePhase.STARTING)return CommandReceipt(false,"start_cancelled")
        var authenticationInvalid=false
        val listener=object:AirPlaySessionListener {
            override fun onSessionActive(session:AirPlaySession)=dispatch {
                if(state.identity==identity && !stopRunning && !authenticationInvalid) {
                    reconnectCancel?.invoke();reconnectCancel=null;restartWanted=false
                    activeSession=session;reconnectAttempts=0
                    session.eventWriteTimingObserver={lockNs,writeNs,commandNs->
                        com.shilapi.xcertplay.diagnostics.RuntimeEvidence.sessions.record(identity.id,identity.generation,"event_write_timing",
                            mapOf("lockWaitNs" to lockNs,"writeNs" to writeNs,"commandNs" to commandNs))
                    }
                    publish(state.copy(phase=RuntimePhase.RUNNING))
                    notifyListener{it.onSessionActive(session)}
                }
            }
            override fun onSessionEnded(session:AirPlaySession)=dispatch {
                if(state.identity==identity && !stopRunning && (activeSession==null || activeSession===session)) {
                    activeSession=null;startupBudget.disconnected()
                    notifyListener{it.onSessionEnded(session)}
                    reconnect("airplay_session_ended")
                }
            }
            override fun onTransportError(message:String)=dispatch {
                if(state.identity==identity && !stopRunning) {
                    notifyListener{it.onTransportError(message)}
                    reconnect("transport_error:$message")
                }
            }
            override fun onCornerMask(session:AirPlaySession,png:ByteArray,displayWidth:Int) {
                val copy=png.copyOf()
                dispatch{if(state.identity==identity && !stopRunning)notifyListener{it.onCornerMask(session,copy,displayWidth)}}
            }
            override fun onVideoFrameRendered(session:AirPlaySession)=dispatch {
                if(state.identity==identity && !stopRunning && !authenticationInvalid && activeSession===session) {
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(startupBudget.firstFrame(session,now))schedule(com.shilapi.xcertplay.network.WirelessStartupPolicy.STABLE_SESSION_MILLIS){
                        if(state.identity==identity && !authenticationInvalid && activeSession===session)startupBudget.resetIfStable(session,android.os.SystemClock.elapsedRealtime())
                    }
                    notifyListener{it.onVideoFrameRendered(session)}
                }
            }
            override fun onDebugLog(message:String)=dispatch {
                if(state.identity==identity)notifyListener{it.onDebugLog(message)}
            }
        }
        try {
            val created=factory.create(request,listener,{status->dispatch {
                if(state.identity==identity && !stopRunning && !authenticationInvalid) {
                    if(status==CarPlayStatus.RunningControl || status==CarPlayStatus.RunningWireless ||
                        status==CarPlayStatus.WirelessActive || status==CarPlayStatus.WirelessActiveFallback){
                        reconnectCancel?.invoke();reconnectCancel=null;restartWanted=false
                    }
                    if(status is CarPlayStatus.Failed && status.authenticationFailure)authenticationInvalid=true
                    publish(state.copy(protocolStatus=status))
                    events?.status?.let{runCatching{it(status)}}
                    if(status is CarPlayStatus.Failed && status.wifiResetRequired){
                        stop("wifi_reset_required"){publish(SessionState(phase=RuntimePhase.WAITING_PREREQUISITES,protocolStatus=status,failure="wifi_reset_required"))}
                    } else if(status is CarPlayStatus.Failed && status.authenticationFailure && authenticationRetried) {
                        stop("authentication_retry_exhausted"){publish(state.copy(phase=RuntimePhase.FAILED,protocolStatus=status,failure="authentication_retry_exhausted"))}
                    } else if(status is CarPlayStatus.Failed || status is CarPlayStatus.ControlEnded) {
                        if(status is CarPlayStatus.Failed && status.authenticationFailure)authenticationRetried=true
                        reconnect("protocol_ended")
                    }
                }
            }},{type,active->dispatch{if(state.identity==identity && !stopRunning){
                if(type==111)com.shilapi.xcertplay.MapMirrors.setStreamActive(active){state.identity==identity && !stopRunning}
                events?.stream?.invoke(type,active)
            }}})
            connection=created
            publish(state)
            if(state.identity==identity && state.phase==RuntimePhase.STARTING && !stopRunning)created.start()
        }catch(error:Exception){
            val failure=error.javaClass.simpleName
            if(connection!=null)stop("start_failed:$failure"){}
            else publish(state.copy(phase=RuntimePhase.FAILED,failure=failure))
            return CommandReceipt(false,failure)
        }
        return CommandReceipt(true)
    }
    fun reconnect(reason:String):CommandReceipt {
        if(requested==null)return CommandReceipt(false,"no_start_request")
        if(reconnectCancel!=null || restartWanted)return CommandReceipt(true)
        restartWanted=true
        publish(state.copy(phase=RuntimePhase.RECONNECTING,failure=reason))
        val identity=state.identity
        val failedStatus=state.protocolStatus as? CarPlayStatus.Failed
        val failure=failedStatus?.startupFailure
        if(failure?.retryable==false) {
            restartWanted=false
            stop("wireless_not_retryable"){publish(state.copy(phase=RuntimePhase.FAILED,protocolStatus=failedStatus,failure=reason))}
            return CommandReceipt(false,"wireless_not_retryable")
        }
        val startupDelay=if(failure!=null)startupBudget.nextDelayMillis()else null
        if(failure!=null && startupDelay==null) {
            restartWanted=false
            stop("wireless_retry_budget_exhausted"){publish(state.copy(phase=RuntimePhase.FAILED,protocolStatus=failedStatus,failure=reason))}
            return CommandReceipt(false,"wireless_retry_budget_exhausted")
        }
        val delay=startupDelay ?: if(reason.contains("AirPlay iAP tunnel",true))15_000L else
            (2_000L*(1L shl reconnectAttempts++.coerceAtMost(4))).coerceAtMost(30_000)
        reconnectCancel=schedule(delay){
            reconnectCancel=null
            if(state.identity==identity && restartWanted)stopInternal(reason,true){}
        }
        return CommandReceipt(true)
    }
    fun stop(reason:String,completed:(StopResult)->Unit={}) {
        stopGeneration++
        requested=null;restartWanted=false;configurationPending=false;reconnectCancel?.invoke();reconnectCancel=null
        stopInternal(reason,false,completed)
    }
    private fun stopInternal(reason:String,restart:Boolean,completed:(StopResult)->Unit) {
        if(stopRunning && state.phase==RuntimePhase.BLOCKED_RELEASE){
            completed(StopResult(false,reason,if(connection==null)0 else 1))
            return
        }
        stopWaiters.add(completed)
        if(stopRunning)return
        val old=connection
        if(old==null){finishStop(true,reason,restart);return}
        stopRunning=true
        com.shilapi.xcertplay.MapMirrors.setStreamActive(false){connection===old || connection==null}
        val identity=state.identity
        publish(state.copy(phase=RuntimePhase.STOPPING,failure=reason))
        val releaseStarted=System.nanoTime()
        val closeReturned=java.util.concurrent.atomic.AtomicBoolean(false)
        val deadlineCancel=schedule(6_500){
            if(state.identity==identity && connection===old && state.phase==RuntimePhase.STOPPING){
                com.shilapi.xcertplay.diagnostics.RuntimeEvidence.sessions.record(identity?.id,identity?.generation,"session_stop_timeout",
                    mapOf("elapsedMs" to (System.nanoTime()-releaseStarted)/1_000_000,"result" to "retained"))
                publish(state.copy(phase=RuntimePhase.BLOCKED_RELEASE))
                val waiting=stopWaiters.toList();stopWaiters.clear()
                waiting.forEach{it(StopResult(false,reason,1))}
                observeRetirement(old,identity,reason,restart,closeReturned)
            }
        }
        teardown.execute {
            val released=runCatching{try{old.close()}finally{closeReturned.set(true)};old.awaitRelease()}.getOrDefault(false)
            com.shilapi.xcertplay.diagnostics.RuntimeEvidence.sessions.record(identity?.id,identity?.generation,"session_close_returned",
                mapOf("elapsedMs" to (System.nanoTime()-releaseStarted)/1_000_000,"result" to if(released)"released"else"retained"))
            dispatch {
                if(state.identity!=identity || connection!==old)return@dispatch
                deadlineCancel()
                if(released)finishStop(true,reason,restart)
                else if(state.phase!=RuntimePhase.BLOCKED_RELEASE) {
                    publish(state.copy(phase=RuntimePhase.BLOCKED_RELEASE))
                    val waiting=stopWaiters.toList();stopWaiters.clear()
                    waiting.forEach{it(StopResult(false,reason,1))}
                    observeRetirement(old,identity,reason,restart,closeReturned)
                }
            }
        }
    }
    private fun observeRetirement(old:SessionConnection,identity:SessionIdentity?,reason:String,restart:Boolean,closeReturned:java.util.concurrent.atomic.AtomicBoolean) {
        schedule(250){
            if(connection!==old || state.identity!=identity)return@schedule
            if(closeReturned.get() && runCatching{old.released()}.getOrDefault(false))finishStop(true,reason,restart)else observeRetirement(old,identity,reason,restart,closeReturned)
        }
    }
    private fun finishStop(released:Boolean,reason:String,restart:Boolean) {
        connection=null;activeSession=null;output=null;stopRunning=false
        val wanted=restart && restartWanted
        restartWanted=wanted && configurationPending
        publish(SessionState(phase=if(restartWanted)RuntimePhase.WAITING_PREREQUISITES else RuntimePhase.IDLE,
            failure=if(restartWanted)"display_configuration_pending" else reason))
        val waiting=stopWaiters.toList();stopWaiters.clear()
        waiting.forEach{it(StopResult(released,reason,0))}
        if(wanted && !configurationPending && foreground)requested?.let(::start)
    }
    companion object {
        fun create(factory:SessionConnectionFactory):SessionRuntime {
            val main=Handler(Looper.getMainLooper())
            return SessionRuntime(factory,{action->main.post{action()}},{delay,action->
                val timer=Runnable{action()};main.postDelayed(timer,delay)
                val cancel:()->Unit={main.removeCallbacks(timer)};cancel
            })
        }
    }
}
