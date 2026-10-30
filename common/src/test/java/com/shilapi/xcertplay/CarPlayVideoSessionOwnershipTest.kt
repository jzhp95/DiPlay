package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayVideoListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayVideoSessionOwnershipTest {
    private fun attach(controller:CarPlayController):CarPlayVideoListener {
        CarPlayVideo.attach(RuntimeEnvironment.getApplication(),controller)
        val capture=ArgumentCaptor.forClass(CarPlayVideoListener::class.java)
        verify(controller).videoListener=capture.capture()
        return capture.value
    }
    @Test fun queuedOldEndCannotClearTheNewSessionsPlayback() {
        val a=mock(CarPlayController::class.java);val b=mock(CarPlayController::class.java)
        try {
            val old=attach(a);shadowOf(Looper.getMainLooper()).idle()
            val current=attach(b)
            current.onVideoMessage(2,mapOf("type" to "setRate","rate" to 1.0))
            old.onVideoSessionEnded()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(CarPlayVideo.playing)
            CarPlayVideo.detach(a)
            assertTrue(CarPlayVideo.playing)
        } finally {CarPlayVideo.detach(b);shadowOf(Looper.getMainLooper()).idle()}
    }
    @Test fun newAttachResetsPlaybackAndInvalidatesOldResourceLoader() {
        val a=mock(CarPlayController::class.java);val b=mock(CarPlayController::class.java)
        try {
            val old=attach(a);shadowOf(Looper.getMainLooper()).idle()
            old.onVideoMessage(1,mapOf("type" to "setRate","rate" to 1.0))
            shadowOf(Looper.getMainLooper()).idle();assertTrue(CarPlayVideo.playing)
            val resolver=CarPlayVideo.sessionUrlResolver()
            val previousPlayer=mock(CarPlayVideoActivity::class.java)
            CarPlayVideo.activity=previousPlayer
            CarPlayVideo.detach(a);attach(b)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(CarPlayVideo.playing);assertNull(CarPlayVideo.activity)
            verify(previousPlayer).finish()
            assertNull(resolver("test://old-session"))
        } finally {CarPlayVideo.detach(b);shadowOf(Looper.getMainLooper()).idle()}
    }
}
