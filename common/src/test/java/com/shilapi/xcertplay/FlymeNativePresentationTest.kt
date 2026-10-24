package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Source-backed presentation contract: preserve visible controls without inventing normal outlines. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlymeNativePresentationTest {
    @Test fun settingsActionsUseNativeFillWithoutAPermanentOutline() {
        val context = RuntimeEnvironment.getApplication()
        listOf(DiPlayPalette.LIGHT to Color.rgb(242,247,252), DiPlayPalette.DARK to Color.rgb(40,44,49)).forEach { (palette, fill) ->
            val button = Button(context)
            FlymeAutoStyle.settingsButton(button, palette, compact = false)
            val image = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
            try {
                button.background.state = intArrayOf(android.R.attr.state_enabled)
                button.background.setBounds(0, 0, 300, 100)
                button.background.draw(Canvas(image))
                assertEquals("Normal setting fill must stand apart from the open page", fill, image.getPixel(150,50))
                assertEquals("The native normal state has no permanent outline", fill, image.getPixel(150,0))
            } finally { image.recycle() }
        }
    }

    @Test fun titleBodyAndSupportingTextRetainTheirDistinctNativeRoles() {
        val context = RuntimeEnvironment.getApplication()
        val sp = context.resources.displayMetrics.scaledDensity
        for ((compact, expectedBody, expectedSupport, expectedNavigation) in listOf(
            listOf(false,26f,22f,28f), listOf(true,20f,16f,22f))) {
            val body = TextView(context)
            FlymeAutoStyle.text(body, FlymeAutoStyle.TextRole.BODY, compact as Boolean)
            assertEquals((expectedBody as Float)*sp, body.textSize,.1f)
            val support = TextView(context)
            FlymeAutoStyle.text(support, FlymeAutoStyle.TextRole.SECONDARY, compact)
            assertEquals((expectedSupport as Float)*sp,support.textSize,.1f)
            val navigation = TextView(context)
            FlymeAutoStyle.text(navigation, FlymeAutoStyle.TextRole.NAVIGATION, compact)
            assertEquals((expectedNavigation as Float)*sp,navigation.textSize,.1f)
            assertTrue("Supporting text must be visually subordinate",support.textSize<body.textSize)
        }
    }
}
