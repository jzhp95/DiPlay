package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.session.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.mockito.Mockito.mock
import java.util.concurrent.Executor

/** Fake native boundary; real Runtime state transitions and retry policy. */
internal class SessionRecoveryHarness(executor:Executor=Executor{it.run()}) {
    class Resource:SessionConnection {
        var released=true;var closes=0
        override val controller=mock(CarPlayController::class.java)
        override val sink=mock(AndroidMediaSink::class.java)
        override fun start(){}
        override fun close(){closes++}
        override fun released()=released
        override fun awaitRelease()=released
    }
    data class Attempt(val listener:AirPlaySessionListener,val report:(CarPlayStatus)->Unit,val resource:Resource)
    private class Timer(val action:()->Unit,var cancelled:Boolean=false)
    private val timers=mutableListOf<Timer>()
    val attempts=mutableListOf<Attempt>()
    var missing:String?=null
    private val factory=object:SessionConnectionFactory {
        override fun readiness(request:SessionStartRequest)=missing
        override fun create(request:SessionStartRequest,listener:AirPlaySessionListener,status:(CarPlayStatus)->Unit,
            stream:(Int,Boolean)->Unit):SessionConnection = Resource().also{attempts.add(Attempt(listener,status,it))}
    }
    val runtime=SessionRuntime(factory,{it()},{_,action->
        val t=Timer(action);timers.add(t)
        val cancel:()->Unit={t.cancelled=true};cancel
    },executor)
    val pendingTimers get()=timers.count{!it.cancelled}
    val request=SessionStartRequest(
        CarPlayRuntimeConfig(mfiTarget=MfiTarget.LOCAL,identification=Iap2IdentificationConfig("test","test","test","test","1","1",3)),
        AirPlayConfig("test","02:00:00:00:00:02","02:00:00:00:00:01","1",AirPlayDisplayConfig(800,480)),
        AirPlayIdentity.generate(),SessionMediaOptions(800,480))
    init {runtime.foregroundReady(true);runtime.start(request)}
    fun advance(){val next=timers.toList();timers.clear();next.filter{!it.cancelled}.forEach{it.action()}}
    fun report(status:CarPlayStatus)=attempts.last().report(status)
}
