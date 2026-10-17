package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnLayout
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** Presentation only. Session, permissions, preferences and navigation belong to DiPlayActivity. */
internal class CarPlayHomeLayout(
    private val context: Context,
    private val palette: DiPlayPalette,
    private val makeButton: (String, Boolean, () -> Unit) -> Button,
    private val makeLabel: (String, Int, Int, Boolean) -> TextView,
) {
    data class Header(val root: LinearLayout, val disconnect: Button)
    data class Cards(
        val root: LinearLayout,
        val wireless: LinearLayout,
        val usb: LinearLayout,
        val status: TextView,
        val usbStatus: TextView,
        val hint: TextView,
        val connect: Button,
        val usbConnect: Button,
        val footer: LinearLayout,
        val notice: TextView,
    )

    private val compact get() = context.resources.configuration.screenHeightDp < 500 ||
        context.resources.configuration.screenWidthDp < 600
    private val shortScreen get() = context.resources.configuration.screenHeightDp < 500
    private val night get() = !palette.systemBarIconsAreDark
    private val gap get() = if (compact) 12 else 24
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun space(size: Int) = View(context).apply {
        // A bare WRAP_CONTENT View fills an AT_MOST height during ScrollView's
        // fillViewport pass. Give spacers an exact height so they cannot consume the cards.
        layoutParams = LinearLayout.LayoutParams(1, dp(size))
    }
    private fun params(top: Int = 0, height: Int = -2) = LinearLayout.LayoutParams(-1, height).apply {
        topMargin = dp(top)
    }
    private fun label(id: Int, size: Int, muted: Boolean = false) =
        makeLabel(context.getString(id), size, if (muted) palette.secondaryText else palette.primaryText, !muted)
    private fun action(id: Int, hero: Boolean = false, click: () -> Unit) =
        makeButton(context.getString(id), hero, click).apply {
            // The shared button factory also styles on layout; register after it so these home-only
            // pill shapes win, without changing settings buttons or their keyboard focus handling.
            fun style() {
                val shape = if (hero) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(-15299340, -16750637)) else GradientDrawable().apply { setColor(palette.surface) }
                shape.cornerRadius = dp(if (hero) 36 else 32).toFloat()
                shape.setStroke(dp(1), if (hero) -16753735 else palette.outline)
                background = RippleDrawable(ColorStateList.valueOf(palette.ripple), shape, null)
                if (hero) setTextColor(Color.WHITE)
            }
            textSize = if (shortScreen) 15f else if (compact) 16f else 17f
            minHeight = dp(if (hero) (if (shortScreen) 48 else if (compact) 56 else 64) else 54)
            style()
            doOnLayout { style() }
        }

    fun header(appearance: View, choose: () -> Unit, disconnect: () -> Unit,
        settings: () -> Unit, carHome: () -> Unit): Header {
        val root = column()
        val top = row()
        val logoSize = if (compact) 36 else 48
        top.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = context.getString(R.string.carplay)
        }, LinearLayout.LayoutParams(dp(logoSize), dp(logoSize)))
        val brand = label(R.string.carplay, if (compact) 22 else 30).apply {
            setPadding(dp(12), 0, dp(8), 0)
            setSingleLine(true)
        }
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(appearance, LinearLayout.LayoutParams(dp(48), dp(48)))
        val disconnectButton = action(R.string.disconnect, click = disconnect).apply { visibility = View.GONE }
        fun addAction(target: LinearLayout, view: View, weight: Boolean = false) {
            target.addView(view, LinearLayout.LayoutParams(if (weight) 0 else -2, -2, if (weight) 1f else 0f).apply {
                marginStart = dp(gap)
            })
        }
        val width = context.resources.configuration.screenWidthDp
        val chooseButton = action(R.string.choose_iphone, click = choose)
        val settingsButton = action(R.string.settings, click = settings)
        val carHomeButton = action(R.string.car_home, click = carHome)
        fun fits(vararg buttons: Button): Boolean {
            val natural = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            val actionWidth = buttons.sumOf { it.measure(natural, natural); it.measuredWidth }
            val brandWidth = android.text.Layout.getDesiredWidth(brand.text, brand.paint).roundToInt()
            return actionWidth + brandWidth + dp(logoSize + 20 + 48 + gap * buttons.size) <= dp(width - 64)
        }
        val allInHeader = width >= 850 && fits(chooseButton, disconnectButton, settingsButton, carHomeButton)
        if (allInHeader) {
            addAction(top, chooseButton)
            addAction(top, disconnectButton)
        }
        // Narrow windows use a separate action row so large text cannot squeeze the brand or buttons.
        val actions = if (width >= 850 && fits(settingsButton, carHomeButton)) top else row()
        addAction(actions, settingsButton, actions !== top)
        addAction(actions, carHomeButton, actions !== top)
        root.addView(top, params())
        if (actions !== top) root.addView(actions, params(gap))
        if (!allInHeader) root.addView(row().apply {
            addView(chooseButton, LinearLayout.LayoutParams(0, -2, 1f))
            addAction(this, disconnectButton, true)
        }, params(gap))
        return Header(root, disconnectButton)
    }

    fun background() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        if (night) intArrayOf(-15721682, -15718869, -15001815)
        else intArrayOf(-2627588, -1444623, -923396))

    private fun connectionCard(icon: Int, title: Int) = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        val padding = if (shortScreen) 16 else if (compact) 20 else 32
        val verticalPadding = if (shortScreen) 16 else if (compact) 24 else 36
        setPadding(dp(padding), dp(verticalPadding), dp(padding), dp(verticalPadding))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (night) intArrayOf(-15062464, -14801351) else intArrayOf(-656385, -131841)).apply {
            cornerRadius = dp(34).toFloat()
            setStroke(dp(1), palette.outline)
        }
        val iconSize = if (shortScreen) 40 else if (compact) 56 else 76
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(palette.accent)
            contentDescription = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = dp(if (shortScreen) 8 else 16)
            setPadding(inset, inset, inset, inset)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (night) -14072733 else -1838593)
                setStroke(dp(1), palette.outline)
            }
        }, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
        addView(label(title, if (shortScreen) 20 else if (compact) 24 else 28).apply { gravity = Gravity.CENTER },
            params(if (shortScreen) 10 else if (compact) 16 else 28))
    }

    fun cards(connect: () -> Unit, usbConnect: () -> Unit, hint: String): Cards {
        val root = column()
        val wireless = connectionCard(R.drawable.ic_home_wifi, R.string.wireless_carplay)
        val usb = connectionCard(R.drawable.ic_home_link, R.string.home_usb_carplay)
        val state = label(R.string.ready_when_you_are, if (shortScreen) 15 else 17, true).apply { gravity = Gravity.CENTER }
        wireless.addView(state, params(if (shortScreen) 10 else 18))
        val connectButton = action(R.string.connect_phone, true, connect)
        wireless.addView(connectButton, params(if (shortScreen) 14 else 24))
        val hintView = makeLabel(hint, 14, palette.secondaryText, false).apply { gravity = Gravity.CENTER }
        wireless.addView(hintView, params(if (shortScreen) 10 else 18))
        val usbState = label(R.string.connect_your_iphone_with_a_usb_cable, if (shortScreen) 15 else 17, true).apply {
            gravity = Gravity.CENTER
        }
        usb.addView(usbState, params(if (shortScreen) 10 else 18))
        val usbButton = action(R.string.connect_with_usb, true, usbConnect)
        usb.addView(usbButton, params(if (shortScreen) 14 else 24))
        usb.addView(label(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y, 14, true).apply {
            gravity = Gravity.CENTER
        }, params(if (shortScreen) 10 else 18))
        val wide = context.resources.configuration.screenWidthDp >= 850
        val connections = if (wide) object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Measure wrapped text first; MATCH_PARENT in a weighted, wrap-content row can
                // yield zero-height descendants during its uniform-height pass.
                wireless.layoutParams.height = -2
                usb.layoutParams.height = -2
                val boundedWidth = View.MeasureSpec.makeMeasureSpec(
                    minOf(View.MeasureSpec.getSize(widthMeasureSpec), dp(1220)),
                    View.MeasureSpec.getMode(widthMeasureSpec))
                super.onMeasure(boundedWidth, heightMeasureSpec)
                val cardHeight = maxOf(wireless.measuredHeight, usb.measuredHeight)
                wireless.layoutParams.height = cardHeight
                usb.layoutParams.height = cardHeight
                super.onMeasure(boundedWidth, heightMeasureSpec)
            }
        }.apply {
            gravity = Gravity.TOP
            addView(wireless, LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(context), LinearLayout.LayoutParams(dp(24), 1))
            addView(usb, LinearLayout.LayoutParams(0, -2, 1f))
        } else column().apply {
            addView(wireless, params())
            addView(usb, params(20))
        }
        root.addView(connections, LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        val notice = makeLabel("", 14, palette.warning, false).apply {
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            minLines = 1
            visibility = View.INVISIBLE
        }
        val footer = column().apply { addView(notice, params()) }
        return Cards(root, wireless, usb, state, usbState, hintView, connectButton, usbButton, footer, notice)
    }

    /** Fill unused viewport space equally above/below the cards, without constraining scroll height. */
    fun addCardsTo(content: LinearLayout, cards: Cards) {
        fun flexibleSpace() = View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(flexibleSpace(), LinearLayout.LayoutParams(1, 0, 1f))
        content.addView(cards.root, params())
        content.addView(flexibleSpace(), LinearLayout.LayoutParams(1, 0, 1f))
    }

    /** Keep the status at the screen's bottom left even when short windows scroll the cards. */
    fun screen(scroll: ScrollView, content: LinearLayout, cards: Cards): LinearLayout = column().apply {
        background = background()
        scroll.setBackgroundColor(Color.TRANSPARENT)
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        cards.footer.setPadding(content.paddingLeft, 0, content.paddingRight, dp(if (compact) 12 else 24))
        addView(cards.footer, params())
    }
}
