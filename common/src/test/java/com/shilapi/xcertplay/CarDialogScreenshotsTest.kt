package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class CarDialogScreenshotActivity : Activity(), AppAppearanceOwner {
    override var currentAppNight = true
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarDialogScreenshotsTest {
    @Test @Config(qualifiers = "w1280dp-h720dp-mdpi")
    fun fullDarkLargeFont() = render("full-dark", night = true, choice = false)

    @Test @Config(qualifiers = "w640dp-h360dp-mdpi")
    fun compactLightLargeFont() = render("compact-light", night = false, choice = false)

    @Test @Config(qualifiers = "w640dp-h360dp-xhdpi")
    fun compactChoiceHighDensity() = render("compact-choice", night = true, choice = true)

    @Test @Config(qualifiers = "ar-ldrtl-w480dp-h800dp-mdpi")
    fun narrowRtlLargeFont() = render("narrow-rtl", night = false, choice = false)

    private fun render(name: String, night: Boolean, choice: Boolean) {
        val controller = Robolectric.buildActivity(CarDialogScreenshotActivity::class.java).setup()
        val host = controller.get()
        host.currentAppNight = night
        val config = android.content.res.Configuration(host.resources.configuration).apply { fontScale = 1.5f }
        @Suppress("DEPRECATION")
        host.resources.updateConfiguration(config, host.resources.displayMetrics)
        val builder = host.appDialogBuilder().setTitle(if (name.contains("rtl")) "إعدادات العرض" else "CarPlay 显示设置")
        if (choice) {
            builder.setSingleChoiceItems(Array(10) { "音频通道 ${it + 1} · 车机输出" }, 1) { _, _ -> }
        } else {
            val fields = LinearLayout(host.appDialogContext()).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
                addView(TextView(context).apply { text = "调整接收画面的宽度，保存后下次连接生效。"; textSize = 14f })
                addView(EditText(context).apply { setText("1920"); isSingleLine = true })
                addView(TextView(context).apply { text = "恢复默认不会立即保存。确认后才应用设置。"; textSize = 14f })
            }
            builder.setView(fields).setNeutralButton("恢复默认", null)
        }
        val dialog = builder.setPositiveButton("应用并重连", null).setNegativeButton("取消", null).show()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val decor = dialog.window!!.decorView
            val density = host.resources.displayMetrics.density
            val width = dialog.window!!.attributes.width
            val maxHeight = ((config.screenHeightDp - 32) * density).toInt()
            repeat(4) {
                fun force(v: View) { v.forceLayout(); if(v is ViewGroup)for(i in 0 until v.childCount) force(v.getChildAt(i)) }
                force(decor)
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
                decor.layout(0, 0, width, decor.measuredHeight)
            }
            assertTrue(decor.height <= maxHeight)
            for (id in listOf(-1, -2)) {
                val b = dialog.getButton(id)
                val bounds = android.graphics.Rect(0, 0, b.width, b.height)
                (decor as ViewGroup).offsetDescendantRectToMyCoords(b, bounds)
                assertTrue("$name button clipped: $bounds in ${decor.width}x${decor.height}",bounds.top>=0&&bounds.bottom<=decor.height&&bounds.left>=0&&bounds.right<=decor.width)
                assertTrue("$name button ${b.text} height=${b.height} minimum=${b.minimumHeight} density=$density", b.height >= 72 * density)
            }
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            val out = File("build/reports/car-dialogs").apply { mkdirs() }
            File(out,"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            bitmap.recycle()
        } finally { dialog.dismiss(); controller.pause().stop().destroy() }
    }
}
