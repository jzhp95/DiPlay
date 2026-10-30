package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.session.*
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaCodec

/** The service retains native ownership while a Host is rebuilt or a decoder ignores close. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SmoothVideoHostLifecycleTest {
    @Test fun adoptingAChangedPreferenceDoesNotSilentlyReconnectTheLiveSession() {
        for(smooth in listOf(false,true))adoption(savedSmooth=!smooth,liveSmooth=smooth)
    }
    @Test fun aMatchingLiveOutputIsAdoptedWithoutStoppingIt() {
        for(smooth in listOf(false,true))adoption(savedSmooth=smooth,liveSmooth=smooth)
    }
    private fun adoption(savedSmooth:Boolean,liveSmooth:Boolean){
        val host=Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val sink=AndroidMediaSink(videoPacingDelayMillis=if(liveSmooth)80 else 0)
        val controller=mock(CarPlayController::class.java)
        val display=CarPlaySessionDisplay(1920,990,0,true,true,1920,990)
        var stops=0
        BackgroundSessionFixture.store(controller,sink,1920,990,Any(),display){stops++}
        AirPlayPersistence.saveSmoothVideo(host,savedSmooth)
        try {
            val adopted=host.javaClass.getDeclaredMethod("adoptBackgroundSession").apply{isAccessible=true}.invoke(host)
            assertEquals(true,adopted)
            assertSame(controller,HostSessionFixture.getField<CarPlayController>(host,"controller"))
            assertSame(sink,HostSessionFixture.getField<AndroidMediaSink>(host,"sink"))
            assertEquals(liveSmooth,sink.videoPacingEnabled)
            assertEquals(0,stops);verify(controller,never()).close()
        } finally {
            HostSessionFixture.getField<SessionSubscription?>(host,"sessionSubscription")?.close()
            HostSessionFixture.getField<SessionSubscription?>(host,"sessionEvents")?.close()
            HostSessionFixture.getField<Handler>(host,"mainHandler").removeCallbacksAndMessages(null)
            HostSessionFixture.getField<java.util.concurrent.ExecutorService>(host,"airPlayCommandExecutor").shutdownNow()
            sink.close();sink.awaitVideoReleased(5000);BackgroundSessionFixture.clear()
        }
    }
    @Test fun aTimedOutRestartRetainsItsSinkAndSurfaceUntilTheCodecExits()=blockedCodec(restart=true)
    @Test fun aTimedOutShutdownRetainsItsSinkAndSurfaceUntilTheCodecExits()=blockedCodec(restart=false)
    @Test fun stoppingDuringARestartCancelsReplacementWithoutForgettingItsSink()=blockedCodec(restart=true,stopAgain=true)

    private fun blockedCodec(restart:Boolean,stopAgain:Boolean=false){
        val codec=BlockingCodec()
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC,
            ShadowMediaCodec.CodecConfig(64*1024,64*1024,codec))
        val texture=SurfaceTexture(0);val surface=Surface(texture)
        val sink=AndroidMediaSink();val controller=mock(CarPlayController::class.java)
        val executor=PausedExecutorService();val main=Handler(Looper.getMainLooper())
        val request=SessionRecoveryHarness().request
        var created=0
        val resource=object:SessionConnection {
            override val controller=controller
            override val sink=sink
            override fun start(){}
            override fun close(){sink.close()}
            override fun released()=sink.awaitVideoReleased(0) && sink.audioReleased()
            override fun awaitRelease()=sink.awaitVideoReleased(25) && sink.audioReleased()
        }
        val runtime=SessionRuntime(SessionConnectionFactory{_,_,_,_->
            created++
            if(created==1)resource else object:SessionConnection {
                override val controller=mock(CarPlayController::class.java)
                override val sink=mock(AndroidMediaSink::class.java)
                override fun start(){}
                override fun close(){}
                override fun released()=true
                override fun awaitRelease()=true
            }
        },{it()},{delay,action->
            val task=Runnable{action()};main.postDelayed(task,delay)
            val cancel:()->Unit={main.removeCallbacks(task)};cancel
        },executor)
        runtime.foregroundReady(true);runtime.claimUi(1);runtime.start(request)
        val binding=ProjectionSurfaceBinding(runtime.snapshot().identity!!,1,surface,false)
        assertTrue(runtime.bindOutput(binding).accepted)
        try {
            val sps=byteArrayOf(0x67,0x42,0xC0.toByte(),0x1E);val pps=byteArrayOf(0x68,0xCE.toByte())
            val config=byteArrayOf(1,0x42,0xC0.toByte(),0x1E,0xFF.toByte(),0xE1.toByte(),0,sps.size.toByte())+
                sps+byteArrayOf(1,0,pps.size.toByte())+pps
            sink.onVideoConfig(110,config)
            sink.onVideoFrame(110,byteArrayOf(0,0,0,1,0x65,0x88.toByte(),0x84.toByte(),0x21))
            assertTrue("native decoder entered",codec.entered.await(5,TimeUnit.SECONDS))
            if(restart)runtime.reconfigure(request,"test restart")else runtime.stop("test stop"){}
            if(stopAgain)runtime.stop("user cancelled restart"){}
            executor.runAll()
            assertEquals(RuntimePhase.BLOCKED_RELEASE,runtime.snapshot().phase)
            assertSame(sink,runtime.currentConnection?.sink)
            assertFalse(runtime.start(request).accepted)
            val released=CountDownLatch(1)
            assertFalse(runtime.unbindOutput(binding.bindingId,surface,false))
            runtime.releaseOutputWhenReady(surface){released.countDown()}
            assertEquals("unconfirmed output wrapper must remain owned",1L,released.count)
            codec.release();assertTrue(sink.awaitVideoReleased(5000))
            executor.runAll();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));executor.runAll()
            assertTrue(released.await(1,TimeUnit.SECONDS))
            assertEquals(if(restart && !stopAgain)2 else 1,created)
            assertEquals(if(restart && !stopAgain)RuntimePhase.STARTING else RuntimePhase.IDLE,runtime.snapshot().phase)
            runtime.stop("cleanup"){};executor.runAll()
        } finally {
            codec.release();sink.close();sink.awaitVideoReleased(5000)
            main.removeCallbacksAndMessages(null);executor.shutdownNow()
            surface.release();texture.release()
        }
    }
    private class BlockingCodec:ShadowMediaCodec.CodecConfig.Codec {
        val entered=CountDownLatch(1);private val gate=CountDownLatch(1)
        fun release(){gate.countDown()}
        override fun process(input:ByteBuffer,output:ByteBuffer){
            entered.countDown();var interrupted=false
            while(true){try{gate.await();break}catch(_:InterruptedException){interrupted=true}}
            if(interrupted)Thread.currentThread().interrupt()
        }
        override fun onConfigured(format:MediaFormat?,surface:Surface?,crypto:MediaCrypto?,flags:Int)=Unit
    }
}
