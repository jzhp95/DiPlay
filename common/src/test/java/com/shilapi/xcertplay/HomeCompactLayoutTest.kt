package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeCompactLayoutTest {
    @Config(qualifiers = "en-w900dp-h400dp-land")
    @Test fun compactLandscapeKeepsWirelessAndUsbInSeparateColumns() {
        verifyCompactArrangement(landscape = true)
    }

    @Config(qualifiers = "en-w400dp-h900dp-port")
    @Test fun compactPortraitStacksWirelessBeforeUsb() {
        verifyCompactArrangement(landscape = false)
    }

    private fun verifyCompactArrangement(landscape: Boolean) {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val config = activity.resources.configuration
        val width = (config.screenWidthDp * metrics.density).toInt()
        val height = (config.screenHeightDp * metrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        fun text(id: Int) = descendants(root).filterIsInstance<TextView>()
            .single { it.text == activity.getString(id) }
        val connect = text(R.string.connect_phone)
        val usb = text(R.string.connect_with_usb)
        val connectPosition = IntArray(2).also(connect::getLocationOnScreen)
        val usbPosition = IntArray(2).also(usb::getLocationOnScreen)
        if (landscape) assertTrue(usbPosition[0] >= connectPosition[0] + connect.width)
        else assertTrue(usbPosition[1] >= connectPosition[1] + connect.height)
        assertTrue(text(R.string.car_home).visibility == View.VISIBLE)
        assertFalse(descendants(root).filterIsInstance<TextView>().any {
            it.text == activity.getString(R.string.a_familiar_drive)
        })
        assertTrue(text(R.string.home_wired_connection).visibility == View.VISIBLE)
        assertTrue(text(R.string.choose_iphone).visibility == View.VISIBLE)
        controller.pause().stop().destroy()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
