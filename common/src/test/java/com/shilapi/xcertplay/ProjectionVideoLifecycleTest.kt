package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectionVideoLifecycleTest {
    private fun invoke(host:CarPlayHostActivity,name:String){
        CarPlayHostActivity::class.java.getDeclaredMethod(name).apply{isAccessible=true}.invoke(host)
    }
    private fun fixture(block:(CarPlayHostActivity,AndroidMediaSink,Surface)->Unit){
        val host=Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val sink=spy(AndroidMediaSink());val texture=SurfaceTexture(0);val surface=Surface(texture)
        HostSessionFixture.setField(host,"sink",sink)
        val owner=HostSessionFixture.getField<CarPlayVideoSurfaceOwner<Surface>>(host,"videoSurfaceOwner")
        owner.replace(surface,false)
        CarPlayHostActivity::class.java.getDeclaredMethod("attachSurface",Surface::class.java)
            .apply{isAccessible=true}.invoke(host,surface)
        try{block(host,sink,surface)}finally{
            HostSessionFixture.getField<Handler>(host,"mainHandler").removeCallbacksAndMessages(null)
            sink.close();surface.release();texture.release()
            for(name in listOf("teardownExecutor","airPlayCommandExecutor"))HostSessionFixture.getField<ExecutorService>(host,name).shutdownNow()
        }
    }
    private fun start(host:CarPlayHostActivity){
        invoke(host,"onStart")
        // Configuration polling is unrelated to this surface lifecycle fixture.
        HostSessionFixture.getField<Handler>(host,"mainHandler").removeCallbacks(
            HostSessionFixture.getField<Runnable>(host,"pollConfiguration"))
    }
    @Test fun shortBackgroundDoesNotDetachAndDelayedParkingIsCancelledOnStart()=fixture{host,sink,surface->
        invoke(host,"onStop");shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(499))
        verify(sink,never()).beginSurfaceDetach(surface,true)
        start(host);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        verify(sink,never()).beginSurfaceDetach(surface,true)
    }
    @Test fun backgroundParksAndForegroundReattachesTheSameSurface()=fixture{host,sink,surface->
        invoke(host,"onStop");shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        verify(sink).beginSurfaceDetach(surface,true)
        assertTrue(HostSessionFixture.getField<Boolean>(host,"videoParked"))
        start(host)
        verify(sink,times(2)).setSurface(110,surface);verify(sink).refreshPicture(110)
        assertFalse(HostSessionFixture.getField<Boolean>(host,"videoParked"))
    }
    @Test fun textureDestroyedHandsCodecOffBeforeReleasingItsSurface()=fixture{host,sink,surface->
        val texture=SurfaceTexture(0)
        try{
            HostSessionFixture.setField(host,"currentSurfaceTexture",texture)
            HostSessionFixture.getField<java.util.IdentityHashMap<SurfaceTexture,Surface>>(host,"textureSurfaces")[texture]=surface
            val listener=HostSessionFixture.getField<TextureView.SurfaceTextureListener>(host,"textureListener")
            assertFalse(listener.onSurfaceTextureDestroyed(texture))
            verify(sink).beginSurfaceDetach(surface,true)
            val owner=HostSessionFixture.getField<CarPlayVideoSurfaceOwner<Surface>>(host,"videoSurfaceOwner")
            assertNull(owner.current)
        }finally{texture.release()}
    }
    @Test fun destroyedTextureIsNotReleasedUntilTheCodecAcknowledgesDetach()=fixture{host,sink,surface->
        val texture=spy(SurfaceTexture(0))
        val receipt=mock(AndroidMediaSink.SurfaceDetach::class.java)
        `when`(receipt.await()).thenReturn(false)
        doReturn(receipt).`when`(sink).beginSurfaceDetach(surface,true)
        HostSessionFixture.setField(host,"currentSurfaceTexture",texture)
        HostSessionFixture.getField<java.util.IdentityHashMap<SurfaceTexture,Surface>>(host,"textureSurfaces")[texture]=surface
        val listener=HostSessionFixture.getField<TextureView.SurfaceTextureListener>(host,"textureListener")
        val executor=HostSessionFixture.getField<org.robolectric.android.util.concurrent.PausedExecutorService>(host,"teardownExecutor")
        try {
            assertFalse(listener.onSurfaceTextureDestroyed(texture))
            verify(texture,never()).release()
            executor.runAll();verify(texture,never()).release()
            `when`(receipt.await()).thenReturn(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));executor.runAll()
            verify(texture).release()
        } finally {texture.release()}
    }

}
