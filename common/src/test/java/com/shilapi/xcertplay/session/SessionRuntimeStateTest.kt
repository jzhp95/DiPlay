package com.shilapi.xcertplay.session

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[28],manifest=org.robolectric.annotation.Config.NONE)
class SessionRuntimeStateTest {
    private class Connection:SessionConnection {
        var starts=0;var closes=0;var released=false
        override val controller:CarPlayController get()=error("not used by lifecycle")
        override val sink:com.shilapi.xcertplay.media.AndroidMediaSink get()=error("not used by lifecycle")
        override fun start(){starts++}
        override fun close(){closes++}
        override fun released()=released
        override fun awaitRelease()=released
    }
    private class Clock {
        data class Timer(val action:()->Unit,var cancelled:Boolean=false)
        val pending=mutableListOf<Timer>()
        fun schedule(delay:Long,action:()->Unit):()->Unit {
            val t=Timer(action);pending.add(t);return{t.cancelled=true}
        }
        fun advance(){val next=pending.toList();pending.clear();next.filter{!it.cancelled}.forEach{it.action()}}
    }
    private fun request()=SessionStartRequest(
        CarPlayRuntimeConfig(mfiTarget=MfiTarget.LOCAL,identification=Iap2IdentificationConfig(
            name="test",modelIdentifier="test",manufacturer="test",serialNumber="test",firmwareVersion="1",hardwareVersion="1",carPlayUsbInterfaceNumber=3)),
        AirPlayConfig(deviceName="test",deviceId="02:00:00:00:00:02",btMac="02:00:00:00:00:01",sourceVersion="1",main=AirPlayDisplayConfig(widthPixels=800,heightPixels=480)),
        AirPlayIdentity.generate(),SessionMediaOptions(800,480))
    @Test fun foregroundGatePreventsResourceCreationAndDuplicateStartIsIdempotent() {
        val connection=Connection();var created=0;val clock=Clock()
        val runtime=SessionRuntime(SessionConnectionFactory{_,_,_,_->created++;connection},{it()},clock::schedule,Executor{it.run()})
        val request=request()
        assertFalse(runtime.start(request).accepted);assertEquals(0,created)
        runtime.foregroundReady(true)
        assertTrue(runtime.start(request).accepted);assertTrue(runtime.start(request).accepted)
        assertEquals(1,created);assertEquals(1,connection.starts)
        connection.released=true;runtime.stop("test"){}
    }
    @Test fun subscriptionCanCancelStartBeforeFactoryCreatesAnything() {
        var created=0;val clock=Clock()
        val runtime=SessionRuntime(SessionConnectionFactory{_,_,_,_->created++;Connection()},{it()},clock::schedule,Executor{it.run()})
        runtime.foregroundReady(true)
        val subscription=runtime.subscribe {state->if(state.phase==RuntimePhase.STARTING)runtime.stop("cancelled"){} }
        assertFalse(runtime.start(request()).accepted)
        assertEquals(0,created);assertEquals(RuntimePhase.IDLE,runtime.snapshot().phase)
        subscription.close()
    }
    @Test fun blockedReleaseRetainsOwnerAndDuplicateStopsDoNotCloseTwice() {
        val connection=Connection();val tasks=mutableListOf<Runnable>();val clock=Clock()
        val runtime=SessionRuntime(SessionConnectionFactory{_,_,_,_->connection},{it()},clock::schedule,Executor{tasks.add(it)})
        runtime.foregroundReady(true);val request=request();runtime.start(request)
        val results=mutableListOf<StopResult>()
        runtime.stop("one"){results.add(it)};runtime.stop("two"){results.add(it)}
        assertEquals(1,tasks.size);assertTrue(results.isEmpty())
        tasks.removeAt(0).run()
        assertEquals(1,connection.closes);assertEquals(2,results.size)
        assertTrue(results.none{it.released})
        assertEquals(RuntimePhase.BLOCKED_RELEASE,runtime.snapshot().phase)
        assertFalse(runtime.start(request).accepted)
        var repeated:StopResult?=null
        runtime.stop("after_deadline"){repeated=it}
        assertNotNull(repeated);assertFalse(repeated!!.released);assertEquals(1,connection.closes)
        connection.released=true;clock.advance()
        assertEquals(RuntimePhase.IDLE,runtime.snapshot().phase)
        assertNull(runtime.snapshot().identity)
    }
    @Test fun explicitStopInvalidatesOldReconnectAndClosedSubscription() {
        val connection=Connection().apply{released=true};val clock=Clock();var created=0;var changes=0
        val runtime=SessionRuntime(SessionConnectionFactory{_,_,_,_->created++;connection},{it()},clock::schedule,Executor{it.run()})
        val subscription=runtime.subscribe{changes++}
        runtime.foregroundReady(true);runtime.start(request());runtime.reconnect("loss")
        subscription.close();val before=changes
        runtime.stop("user"){assertTrue(it.released)}
        clock.advance()
        assertEquals(1,created);assertEquals(before,changes)
        assertEquals(RuntimePhase.IDLE,runtime.snapshot().phase)
    }
    @Test fun diagnosticLeaseWaitResumesInServiceAndExplicitStopCancelsIt() {
        val clock=Clock();var blocked=true;var created=0
        val factory=object:SessionConnectionFactory {
            override fun readiness(request:SessionStartRequest)=if(blocked)"diagnostic_active" else null
            override fun create(request:SessionStartRequest,listener:AirPlaySessionListener,status:(CarPlayStatus)->Unit,
                stream:(Int,Boolean)->Unit):SessionConnection {created++;return Connection().apply{released=true}}
        }
        val runtime=SessionRuntime(factory,{it()},clock::schedule,Executor{it.run()})
        runtime.foregroundReady(true)
        assertFalse(runtime.start(request()).accepted)
        clock.advance();assertEquals(0,created)
        blocked=false;clock.advance();assertEquals(1,created)
        runtime.stop("done"){}
        blocked=true;runtime.start(request());runtime.stop("user"){}
        blocked=false;clock.advance()
        assertEquals(1,created);assertEquals(RuntimePhase.IDLE,runtime.snapshot().phase)
    }
    @Test fun explicitStopRejectsReconfigureUntilItsActualRelease() {
        val clock=Clock();val connection=Connection();val work=mutableListOf<Runnable>();var created=0
        val runtime=SessionRuntime(SessionConnectionFactory{_,_,_,_->created++;connection},{it()},clock::schedule,Executor{work.add(it)})
        runtime.foregroundReady(true);runtime.start(request());runtime.stop("user"){}
        assertFalse(runtime.reconfigure(request(),"settings").accepted)
        work.removeAt(0).run();connection.released=true;clock.advance()
        assertEquals(1,created);assertEquals(RuntimePhase.IDLE,runtime.snapshot().phase)
    }
    @Test fun manualReconfigureCanStartAfterAReadinessFailure() {
        val clock=Clock();var ready=false;var created=0
        val factory=object:SessionConnectionFactory {
            override fun readiness(request:SessionStartRequest)=if(ready)null else "vpn_consent_required"
            override fun create(request:SessionStartRequest,listener:AirPlaySessionListener,status:(CarPlayStatus)->Unit,
                stream:(Int,Boolean)->Unit):SessionConnection {created++;return Connection().apply{released=true}}
        }
        val runtime=SessionRuntime(factory,{it()},clock::schedule,Executor{it.run()})
        runtime.foregroundReady(true);runtime.start(request())
        assertEquals(RuntimePhase.WAITING_PREREQUISITES,runtime.snapshot().phase)
        ready=true;assertTrue(runtime.reconfigure(request(),"settings").accepted)
        assertEquals(1,created);assertEquals(RuntimePhase.STARTING,runtime.snapshot().phase)
        runtime.stop("done"){}
    }

    @Test fun readinessExceptionDoesNotEscapeTheServiceOrCreateResources() {
        val clock=Clock();var created=0
        val factory=object:SessionConnectionFactory {
            override fun readiness(request:SessionStartRequest):String?=throw SecurityException("OEM refused")
            override fun create(request:SessionStartRequest,listener:AirPlaySessionListener,status:(CarPlayStatus)->Unit,
                stream:(Int,Boolean)->Unit):SessionConnection {created++;return Connection()}
        }
        val runtime=SessionRuntime(factory,{it()},clock::schedule,Executor{it.run()})
        runtime.foregroundReady(true)
        assertFalse(runtime.start(request()).accepted)
        assertEquals(0,created);assertEquals(RuntimePhase.FAILED,runtime.snapshot().phase)
        assertEquals("readiness_failed:SecurityException",runtime.snapshot().failure)
    }

    @Test fun windowConfigurationGateStartsOnlyTheLatestSnapshotAfterActualRelease() {
        val clock=Clock();val tasks=mutableListOf<Runnable>();val requests=mutableListOf<SessionStartRequest>()
        val old=Connection().apply{released=true}
        val runtime=SessionRuntime(SessionConnectionFactory{value,_,_,_->requests.add(value);if(requests.size==1)old else Connection().apply{released=true}},
            {it()},clock::schedule,Executor{tasks.add(it)})
        runtime.foregroundReady(true);val ui=runtime.claimUi(1);val initial=request();runtime.start(initial)
        assertTrue(runtime.setConfigurationPending(ui,true).accepted)
        runtime.reconfigure(initial.copy(media=initial.media.copy(width=900)),"first resize")
        tasks.removeAt(0).run()
        assertEquals(1,requests.size)
        assertEquals("display_configuration_pending",runtime.snapshot().failure)
        val latest=initial.copy(media=initial.media.copy(width=1200))
        runtime.reconfigure(latest,"settled resize")
        assertFalse(runtime.setConfigurationPending(ui.copy(owner=2),false).accepted);assertEquals(1,requests.size)
        assertFalse(runtime.setConfigurationPending(ui,false).accepted)
        runtime.reconfigureFromUi(latest,"commit settled window",ui)
        assertEquals(2,requests.size);assertEquals(1200,requests.last().media.width)
        assertEquals(1,old.closes)
        runtime.stop("done"){};tasks.removeAt(0).run()
    }
    @Test fun explicitStopCancelsAReplacementWaitingForTheWindow() {
        val clock=Clock();var created=0
        val runtime=SessionRuntime(SessionConnectionFactory{_,_,_,_->created++;Connection().apply{released=true}},
            {it()},clock::schedule,Executor{it.run()})
        runtime.foregroundReady(true);val ui=runtime.claimUi(1);runtime.start(request())
        runtime.setConfigurationPending(ui,true);runtime.reconfigure(request(),"resize")
        assertEquals("display_configuration_pending",runtime.snapshot().failure)
        runtime.stop("user"){};runtime.setConfigurationPending(ui,false);clock.advance()
        assertEquals(1,created);assertEquals(RuntimePhase.IDLE,runtime.snapshot().phase)
    }

    @Test fun oldUiCannotRestartAfterExplicitStopOrReleaseAnUnpreparedNewOwner() {
        val clock=Clock();var created=0
        val runtime=SessionRuntime(SessionConnectionFactory{_,_,_,_->created++;Connection().apply{released=true}},
            {it()},clock::schedule,Executor{it.run()})
        runtime.foregroundReady(true);val old=runtime.claimUi(1);val value=request()
        runtime.startFromUi(value,old);runtime.setConfigurationPending(old,true)
        runtime.reconfigure(value,"resize")
        runtime.releaseUi(old);val current=runtime.claimUi(2)
        assertEquals(1,created);assertEquals("display_configuration_pending",runtime.snapshot().failure)
        assertFalse(runtime.reconfigureFromUi(value,"old layout",old).accepted)
        runtime.stop("notification"){}
        assertFalse(runtime.startFromUi(value,current).accepted)
        assertFalse(runtime.reconfigureFromUi(value,"late layout",current).accepted)
        assertEquals(1,created)
    }

    @Test fun diagnosticReadinessRetryCannotBypassAnUnsettledWindow() {
        val clock=Clock();var blocked=true;var created=0
        val factory=object:SessionConnectionFactory {
            override fun readiness(request:SessionStartRequest)=if(blocked)"diagnostic_active" else null
            override fun create(request:SessionStartRequest,listener:AirPlaySessionListener,status:(CarPlayStatus)->Unit,
                stream:(Int,Boolean)->Unit):SessionConnection {created++;return Connection().apply{released=true}}
        }
        val runtime=SessionRuntime(factory,{it()},clock::schedule,Executor{it.run()})
        runtime.foregroundReady(true);val ui=runtime.claimUi(1);val value=request()
        runtime.startFromUi(value,ui);runtime.setConfigurationPending(ui,true)
        blocked=false;clock.advance();assertEquals(0,created)
        assertEquals("display_configuration_pending",runtime.snapshot().failure)
        runtime.confirmConfigurationFromUi(value,ui);assertEquals(1,created)
        runtime.stop("done"){}
    }

}
