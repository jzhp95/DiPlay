package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.session.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.mockito.Mockito.mock
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.util.ReflectionHelpers
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService

/** Inject resources into the real service owner for focused Host UI fixtures. */
internal object HostSessionFixture {
    private val executors=WeakHashMap<CarPlayHostActivity,ExecutorService>()
    private fun runtime(host:CarPlayHostActivity):SessionRuntime?=ReflectionHelpers.getField(host,"sessionRuntime")
    private fun executor(host:CarPlayHostActivity)=executors.getOrPut(host){PausedExecutorService()}
    private fun timer(delay:Long,action:()->Unit):()->Unit {
        val handler=Handler(Looper.getMainLooper());val task=Runnable{action()}
        handler.postDelayed(task,delay);return{handler.removeCallbacks(task)}
    }
    private fun install(host:CarPlayHostActivity,resource:SessionConnection?=null) {
        val production=SessionFactory(host.applicationContext)
        var initial=resource
        val factory=object:SessionConnectionFactory {
            override fun readiness(request:SessionStartRequest)=if(initial!=null)null else production.readiness(request)
            override fun create(request:SessionStartRequest,listener:AirPlaySessionListener,status:(CarPlayStatus)->Unit,
                stream:(Int,Boolean)->Unit):SessionConnection {
                val first=initial;initial=null
                return first ?: production.create(request,listener,status,stream)
            }
        }
        val runtime=SessionRuntime(factory,{it()},::timer,executor(host))
        runtime.foregroundReady(true);runtime.claimUi(System.identityHashCode(host))
        ReflectionHelpers.setField(host,"sessionRuntime",runtime)
        DiPlaySessionService.liveRuntime=runtime
        if(resource!=null)runtime.start(SessionStartRequest(
            CarPlayRuntimeConfig(mfiTarget=MfiTarget.LOCAL,identification=Iap2IdentificationConfig("test","test","test","test","1","1",3)),
            AirPlayConfig("test","02:00:00:00:00:02","02:00:00:00:00:01","1",AirPlayDisplayConfig(1920,990)),
            AirPlayIdentity.generate(),SessionMediaOptions(1920,990),resource.display))
    }
    fun mediaConnection(host:CarPlayHostActivity,width:Int,height:Int,pacing:Int):SessionConnection {
        val type=Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size=type.getDeclaredConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
            .apply{isAccessible=true}.newInstance(width,height)
        val prepared=host.javaClass.getDeclaredMethod("prepareSessionRequest",type)
            .apply{isAccessible=true}.invoke(host,size) as SessionStartRequest
        val request=prepared.copy(media=prepared.media.copy(width=width,height=height,pacingDelayMs=pacing))
        return org.mockito.Mockito.mockConstruction(CarPlayController::class.java).use {
            SessionFactory(host.applicationContext).create(request,object:AirPlaySessionListener{},{},{_,_->})
        }
    }
    @Suppress("UNCHECKED_CAST")
    fun <T> getField(target:Any,name:String):T {
        if(target !is CarPlayHostActivity)return ReflectionHelpers.getField(target,name)
        return when(name){
            "controller"->runtime(target)?.currentConnection?.controller
            "sink"->runtime(target)?.currentConnection?.sink
            "teardownExecutor"->executor(target)
            "startupRetryStopped"->runtime(target)?.snapshot()?.phase==RuntimePhase.FAILED
            "handshakeResetInProgress"->runtime(target)?.snapshot()?.phase in setOf(RuntimePhase.STOPPING,RuntimePhase.BLOCKED_RELEASE)
            else->return ReflectionHelpers.getField(target,name)
        } as T
    }
    fun setField(target:Any,name:String,value:Any?) {
        if(target !is CarPlayHostActivity){ReflectionHelpers.setField(target,name,value);return}
        when(name){
            "teardownExecutor"->{
                executors[target]=value as ExecutorService
                val runtime=runtime(target)
                if(runtime==null)install(target)else ReflectionHelpers.setField(runtime,"teardown",value)
            }
            "controller","sink"->{
                if(value==null){runtime(target)?.stop("fixture_reset"){};return}
                val old=runtime(target)?.currentConnection
                val controller=if(name=="controller")value as CarPlayController else old?.controller ?: mock(CarPlayController::class.java)
                val sink=if(name=="sink")value as AndroidMediaSink else old?.sink ?: mock(AndroidMediaSink::class.java)
                var closed=false
                val fakeController=org.mockito.Mockito.mockingDetails(controller).let{it.isMock && !it.isSpy}
                val fakeSink=org.mockito.Mockito.mockingDetails(sink).let{it.isMock && !it.isSpy}
                val resource=object:SessionConnection {
                    override val controller=controller
                    override val sink=sink
                    override val display=old?.display
                    override fun start(){}
                    override fun close(){try{controller.close()}finally{sink.close();closed=true}}
                    override fun released()=closed && (fakeController || controller.awaitClosed(0)) && (fakeSink || (sink.awaitVideoReleased(0) && sink.audioReleased()))
                    override fun awaitRelease()=closed && (fakeController || controller.awaitClosed(4000)) && (fakeSink || (sink.awaitVideoReleased(2000) && sink.audioReleased()))
                }
                install(target,resource)
            }
            else->ReflectionHelpers.setField(target,name,value)
        }
    }
}
