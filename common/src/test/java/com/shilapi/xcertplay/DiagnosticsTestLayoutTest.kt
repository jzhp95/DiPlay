package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.mfi.LocalMfiDiagnostic
import com.shilapi.xcertplay.mfi.MfiCoprocessorDiagnostic
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsTestLayoutTest {
    @Test fun fixedMenusAndScrollingDetailsRemainReadableWithLargeFonts() {
        val base = RuntimeEnvironment.getApplication()
        for (width in listOf(900, 400)) for (densityDpi in listOf(160, 320))
            for (night in listOf(false, true)) for (rtl in listOf(false, true)) {
            val height = if (width == 900) 500 else 800
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                screenWidthDp = width; screenHeightDp = height; fontScale = 1.5f
                this.densityDpi = densityDpi
                setLayoutDirection(if (rtl) java.util.Locale.forLanguageTag("ar") else java.util.Locale.SIMPLIFIED_CHINESE)
            })
            val palette = DiPlayPalette.of(night)
            val wheel = WheelTestLayout(context, palette).build(false, {}, {},
                availableWidthDp = DiagnosticsTestLayout.detailWidthDp(width))
            wheel.event.text = context.getString(R.string.wheel_test_event, "下一曲", "接口接收", 123)
            wheel.status.text = context.getString(R.string.wheel_test_status, "已开启", "已获得", "已获得")
            wheel.counts.text = context.getString(R.string.wheel_test_count, 123, 2, "Flyme EAS")
            wheel.logs.text = "Android focus granted\nFlyme EAS media=4 accepted=true"
            val mfiLayout = MfiTestLayout(context, palette)
            val mfi = mfiLayout.build({}, {}, {})
            mfiLayout.show(mfi, MfiTestController.State(
                local = LocalMfiDiagnostic(LocalMfiDiagnostic.Source.BUNDLED, true, true, LocalMfiDiagnostic.Status.READY, 650),
                usbAvailable = true, devices = listOf("1a86:5512 /dev/bus/usb/001/005"),
                permission = MfiTestController.Permission.GRANTED,
                chip = MfiCoprocessorDiagnostic(MfiCoprocessorDiagnostic.Status.READABLE, 0x11, 2, 650)))
            val clicks = mutableListOf<DiagnosticsTestLayout.Page>()
            var backs = 0
            val uid = UidTestLayout(context, palette).build()
            val color = ScrollView(context).apply { addView(TextView(context).apply { text = context.getString(R.string.color_test_title) }) }
            val video = ScrollView(context).apply { addView(TextView(context).apply { text = context.getString(R.string.video_adapt_title) }) }
            val ui = DiagnosticsTestLayout(context, palette).build(wheel.root, mfi.root, DiagnosticsTestLayout.Page.WHEEL, clicks::add, back = { backs++ }, uid = uid.root, color = color, video = video)
            val density = context.resources.displayMetrics.density
            val w = (width * density).toInt(); val h = (height * density).toInt()
            val prefix = "$width-$densityDpi-1.5-${if (night) "dark" else "light"}${if (rtl) "-rtl" else ""}"
            for (page in DiagnosticsTestLayout.Page.entries) {
                ui.select(page)
                measure(ui.root, w, h)
                val detail = when (page) {
                    DiagnosticsTestLayout.Page.WHEEL -> wheel.root
                    DiagnosticsTestLayout.Page.MFI -> mfi.root
                    DiagnosticsTestLayout.Page.UID -> uid.root
                    DiagnosticsTestLayout.Page.COLOR -> color
                    DiagnosticsTestLayout.Page.VIDEO -> video
                }
                val menus = visible(ui.root).filterIsInstance<Button>().filter {
                    it.text == "方控测试" || it.text == "MFi 认证测试" || it.text == "UID 检测" || it.text == "画面颜色" || it.text == "视频适配"
                }.toList()
                assertEquals(if (width < 600) 1 else 5, menus.size)
                menus.forEach { menu ->
                    val menuBounds = bounds(ui.root, menu)
                    val detailBounds = bounds(ui.root, detail)
                    if (width >= 600) {
                        assertTrue("Wide menu must stay on the left", menuBounds.right <= detailBounds.left)
                        menu.performClick()
                    } else {
                        assertTrue("Compact picker must stay above details", menuBounds.bottom <= detailBounds.top)
                        menu.performClick()
                        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                        for (index in DiagnosticsTestLayout.Page.entries.indices) {
                            dialog.listView.performItemClick(null, index, index.toLong())
                        }
                        dialog.dismiss()
                    }
                }
                screenshot(ui.root, w, h, "$prefix-${page.name.lowercase()}-top")
                assertReadable(ui.root, w)
                val menuTop = bounds(ui.root, menus.first()).top
                detail.scrollTo(0, (detail.getChildAt(0).height - detail.height).coerceAtLeast(0))
                assertEquals("Scrolling details cannot move the menu", menuTop, bounds(ui.root, menus.first()).top)
                screenshot(ui.root, w, h, "$prefix-${page.name.lowercase()}-bottom")
            }
            assertEquals(DiagnosticsTestLayout.Page.entries.toSet(), clicks.toSet())
            visible(ui.root).filterIsInstance<Button>().single { it.text == context.getString(R.string.back) }.performClick()
            assertEquals(1, backs)
        }
    }
    private fun measure(root: View, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }
    private fun assertReadable(root: ViewGroup, width: Int) {
        visible(root).filterIsInstance<TextView>().forEach { text ->
            val rect = bounds(root, text)
            assertTrue("Text outside width: ${text.text}", rect.left >= 0 && rect.right <= width)
            text.layout?.let { layout ->
                assertTrue("Clipped height: ${text.text}", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                for (line in 0 until layout.lineCount) {
                    assertEquals("Ellipsized text: ${text.text}", 0, layout.getEllipsisCount(line))
                    val available = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
                    val lineText = text.text.subSequence(layout.getLineStart(line), layout.getLineEnd(line))
                    assertTrue("Clipped line: ${text.text}; line=$line '$lineText' advance=${layout.getLineMax(line)} available=$available textSize=${text.textSize}",
                        layout.getLineMax(line) <= available + 1)
                }
            }
        }
    }
    private fun bounds(root: ViewGroup, view: View) = android.graphics.Rect(0, 0, view.width, view.height).also {
        root.offsetDescendantRectToMyCoords(view, it)
    }
    private fun visible(view: View): Sequence<View> = sequence {
        if (view.visibility != View.VISIBLE) return@sequence
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(visible(view.getChildAt(i)))
    }
    private fun screenshot(root: View, width: Int, height: Int, name: String) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val directory = File("build/reports/diagnostics-test-ui").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
