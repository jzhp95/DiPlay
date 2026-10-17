package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import java.io.File
import java.util.Locale
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
class FlymeUiScreenshotsTest {
    @Test fun appOwnedSurfacesStayReadableInBothModesAndCarWindows() {
        val base = RuntimeEnvironment.getApplication()
        for ((name, widthDp, heightDp) in listOf(Triple("full", 1100, 700), Triple("short", 900, 400), Triple("compact", 400, 700), Triple("rtl", 400, 700))) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                screenWidthDp = widthDp; screenHeightDp = heightDp; fontScale = 1.5f
                val locale = if (name == "rtl") Locale.forLanguageTag("ar") else Locale.SIMPLIFIED_CHINESE
                setLocale(locale); setLayoutDirection(locale)
            })
            for (night in listOf(false, true)) {
                val palette = DiPlayPalette.of(night)
                val density = context.resources.displayMetrics.density
                fun dp(v: Int) = (v * density + .5f).toInt()
                val home = CarPlayHomeLayout(context, palette,
                    makeButton = { title, primary, click -> Button(context).apply { text = title; FlymeAutoStyle.button(this,palette,primary);setOnClickListener { click() } } },
                    makeLabel = { title, size, color, bold -> TextView(context).apply { text=title;textSize=size.toFloat();setTextColor(color);typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT } })
                val cards = home.cards({}, {}, "蓝牙连接手机后即可开始")
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24),dp(16),dp(24),dp(16))
                    addView(home.header(View(context), {}, {}, {}, {}).root)
                    home.addCardsTo(this,cards)
                }
                val scroll = ScrollView(context).apply { isFillViewport=true;addView(content) }
                val screen = home.screen(scroll,content,cards)
                val settings = LinearLayout(context).apply {
                    orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(24));setBackgroundColor(palette.background)
                    addView(TextView(context).apply { text="连接设置";textSize=28f;setTextColor(palette.primaryText) })
                }
                val card = LinearLayout(context).apply {
                    orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(24));background=FlymeAutoStyle.card(context,palette)
                }
                var saved=0
                val switchRow=SettingsWidgets.createSwitchRow(context,"自动连接 iPhone","打开应用时按照已保存的连接方式连接。",true,SettingsTheme.card(palette),onChanged={saved++})
                card.addView(SettingsWidgets.createCategoryHeader(context,"自动连接",SettingsTheme.card(palette)))
                card.addView(switchRow.rowView)
                assertEquals("constructing visuals must not save preferences",0,saved)
                switchRow.rowView.performClick();assertEquals(1,saved)
                val choice=SettingsWidgets.createChoiceRow(context,"连接方式",listOf("wireless" to "无线连接","usb" to "USB 有线连接"),"wireless",SettingsTheme.card(palette)){}
                card.addView(choice.container)
                card.addView(DiPlayButton(context).apply { text="选择 iPhone";style(DiPlayButtonKind.PRIMARY,palette,false) },LinearLayout.LayoutParams(-1,-2))
                settings.addView(card,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(FlymeAutoStyle.BLOCK_GAP_DP)})
                val settingScroll=ScrollView(context).apply {isFillViewport=true;addView(settings)}
                for ((kind,view) in listOf("home" to screen,"settings-controls" to settingScroll)) {
                    val width=dp(widthDp);val height=dp(heightDp)
                    view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                    view.layout(0,0,width,height)
                    assertTrue(view.measuredWidth>0&&view.measuredHeight>0)
                    for (text in descendants(view).filterIsInstance<TextView>().filter { text ->
                        generateSequence(text as View) { it.parent as? View }.all { it.visibility == View.VISIBLE }
                    }) {
                        val r=android.graphics.Rect(0,0,text.width,text.height);view.offsetDescendantRectToMyCoords(text,r)
                        assertTrue("$kind/$name text outside window: ${text.text}: $r",r.left>=0&&r.right<=width)
                        if(text is Button)assertTrue("$kind/$name button text clipped: ${text.text}", (text.layout?.height?:0)<=text.height-text.compoundPaddingTop-text.compoundPaddingBottom)
                    }
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                    val output=File("build/reports/flyme-app").apply{mkdirs()}
                    File(output,"$kind-$name-${if(night)"dark" else "light"}.png").outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
                }
            }
        }
    }
    private fun descendants(view: View):Sequence<View> = sequence { yield(view);if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(descendants(view.getChildAt(i))) }
}
