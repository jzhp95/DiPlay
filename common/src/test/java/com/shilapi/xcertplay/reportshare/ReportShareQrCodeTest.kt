package com.shilapi.xcertplay.reportshare

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ReportShareQrCodeTest {
    @Test
    fun rendersRoundedWhiteCardBehindBlackModules() {
        val size = 240
        val bitmap = ReportShareQrCode.encode("http://192.168.99.1:8899/r/0123456789abcdef/", size)
        assertEquals(size, bitmap.width)
        assertEquals(size, bitmap.height)
        assertEquals(0, bitmap.getPixel(0, 0) ushr 24)
        assertEquals(255, bitmap.getPixel(size / 2, 2) ushr 24)
        var black = 0
        for (y in 0 until size step 3) {
            for (x in 0 until size step 3) {
                if (bitmap.getPixel(x, y) == Color.BLACK) black++
            }
        }
        assertTrue(black > 100)
    }
}
