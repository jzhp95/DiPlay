package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.session.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.util.concurrent.Executor
import org.mockito.Mockito.mock

/** Test-only fake native owner for the consumer fixtures that formerly populated a global resource map. */
internal object BackgroundSessionFixture {
    private var listener:AirPlaySessionListener?=null
    fun store(controller:CarPlayController,sink:AndroidMediaSink,width:Int,height:Int,owner:Any,
        display:CarPlaySessionDisplay,stop:(()->Unit)->Unit) {
        var released=false
        val resource=object:SessionConnection {
            override val controller=controller
            override val sink=sink
            override val display=display
            override fun start(){}
            override fun close(){
                try{controller.close()}finally{try{sink.close()}finally{stop{released=true}}}
            }
            override fun released()=released
            override fun awaitRelease()=released
        }
        val main=Handler(Looper.getMainLooper())
        val production=SessionFactory(org.robolectric.RuntimeEnvironment.getApplication())
        var initial=true
        val factory=object:SessionConnectionFactory {
            override fun readiness(request:SessionStartRequest)=if(initial)null else production.readiness(request)
            override fun create(request:SessionStartRequest,events:AirPlaySessionListener,status:(CarPlayStatus)->Unit,
                stream:(Int,Boolean)->Unit):SessionConnection {
                listener=events
                if(initial){initial=false;return resource}
                return production.create(request,events,status,stream)
            }
        }
        val executor=if(owner is CarPlayHostActivity)HostSessionFixture.getField<java.util.concurrent.ExecutorService>(owner,"teardownExecutor")else Executor{it.run()}
        val runtime=SessionRuntime(factory,{action->if(Looper.myLooper()==Looper.getMainLooper())action()else main.post{action()}},{delay,action->
            val timer=Runnable{action()};main.postDelayed(timer,delay)
            val cancel:()->Unit={main.removeCallbacks(timer)};cancel
        },executor)
        runtime.foregroundReady(true);runtime.claimUi(System.identityHashCode(owner))
        DiPlaySessionService.liveRuntime=runtime
        runtime.start(SessionStartRequest(CarPlayRuntimeConfig(mfiTarget=MfiTarget.LOCAL,
            identification=Iap2IdentificationConfig(name="test",modelIdentifier="test",manufacturer="test",serialNumber="test",firmwareVersion="1",hardwareVersion="1",carPlayUsbInterfaceNumber=3)),
            AirPlayConfig(deviceName="test",deviceId="02:00:00:00:00:02",btMac="02:00:00:00:00:01",sourceVersion="1",
                main=AirPlayDisplayConfig(widthPixels=width.coerceAtLeast(1),heightPixels=height.coerceAtLeast(1))),
            AirPlayIdentity.generate(),SessionMediaOptions(width,height),display))
        if(owner is CarPlayHostActivity)owner.javaClass.getDeclaredMethod("bindSessionRuntime",SessionRuntime::class.java)
            .apply{isAccessible=true}.invoke(owner,runtime)
    }
    fun storeStop(stop:(()->Unit)->Unit) {
        store(mock(CarPlayController::class.java),mock(AndroidMediaSink::class.java),800,480,Any(),
            CarPlaySessionDisplay(800,480,0,false,false,800,480),stop)
    }
    fun clear(expected:CarPlayController?=null,keepOwner:Boolean=false) {
        if(expected!=null && DiPlaySessionService.liveRuntime?.currentConnection?.controller!==expected)return
        DiPlaySessionService.liveRuntime=null;listener=null
    }
    fun deliverActive(session:AirPlaySession){listener?.onSessionActive(session)}
    fun setActive(value:Boolean) {
        if(value)listener?.onSessionActive(mock(AirPlaySession::class.java))
    }
    fun claimOwner(owner:Any){DiPlaySessionService.liveRuntime?.claimUi(System.identityHashCode(owner))}
    fun retainAuthenticationTeardown(controller:CarPlayController?){
        if(controller!=null)DiPlaySessionService.liveRuntime?.stop("fixture_authentication_teardown"){}
    }
    data class Snapshot(val controller:CarPlayController,val sink:AndroidMediaSink,val width:Int,val height:Int,val display:CarPlaySessionDisplay)
    fun snapshot():Snapshot? {
        val resource=DiPlaySessionService.liveRuntime?.currentConnection ?: return null
        val display=resource.display ?: return null
        return Snapshot(resource.controller,resource.sink,display.windowWidth,display.windowHeight,display)
    }
}
