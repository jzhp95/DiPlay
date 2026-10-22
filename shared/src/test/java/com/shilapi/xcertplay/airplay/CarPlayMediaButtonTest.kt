package com.shilapi.xcertplay.airplay

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class CarPlayMediaButtonTest {
    @Test fun standardMediaKeysKeepTheirCarPlayMappings() {
        assertEquals(4, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(5, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        for (code in listOf(85, 126, 127, 79)) assertEquals(3, CarPlayMediaButton.forKeyCode(code))
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
    }
    @Test fun retiredVendorKeysHaveNoImplicitMediaOrSiriMapping() {
        for (code in listOf(304, 312, 327, 328, 331, 353)) {
            assertNull("media code=$code", CarPlayMediaButton.forKeyCode(code))
            assertFalse("voice code=$code", CarPlayMediaButton.opensSiri(code))
        }
    }
    @Test fun flymeVoiceKeysRemainAvailableWithoutBecomingMediaKeys() {
        for (code in listOf(KeyEvent.KEYCODE_VOICE_ASSIST, 200231)) {
            assertTrue(CarPlayMediaButton.opensSiri(code))
            assertNull(CarPlayMediaButton.forKeyCode(code))
        }
    }
    @Test fun foregroundTrackKeysRespectSessionAndCallOwnership() {
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
            assertNotNull(CarPlayMediaButton.forCarPlayWindow(code, true, false))
            assertNull(CarPlayMediaButton.forCarPlayWindow(code, false, false))
            assertNull(CarPlayMediaButton.forCarPlayWindow(code, true, true))
        }
        assertNull(CarPlayMediaButton.forCarPlayWindow(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true, false))
    }
}
