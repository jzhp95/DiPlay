package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** Navigation stays outside the independently scrolling detail pages, including narrow windows. */
internal class DiagnosticsTestLayout(private val context: Context, private val palette: DiPlayPalette) {
    enum class Page { WHEEL, MFI }
    class Views(val root: LinearLayout, private val wheel: ScrollView, private val mfi: ScrollView,
        private val menu: Map<Page, Button>, private val palette: DiPlayPalette) {
        fun select(page: Page) {
            wheel.visibility = if (page == Page.WHEEL) View.VISIBLE else View.GONE
            mfi.visibility = if (page == Page.MFI) View.VISIBLE else View.GONE
            menu.forEach { (item, button) ->
                button.isSelected = item == page
                FlymeAutoStyle.button(button, palette, primary = item == page, compact = true)
                val inset = FlymeAutoStyle.dp(button.context, FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP)
                button.setPadding(inset, button.paddingTop, inset, button.paddingBottom)
            }
        }
    }
    fun build(wheel: ScrollView, mfi: ScrollView, selected: Page, changed: (Page) -> Unit, back: () -> Unit = {}): Views {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // The requested menu is physically on the left, including RTL configuration previews.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(palette.background)
            isBaselineAligned = false
        }
        val rail = LinearLayout(context).apply {
            layoutDirection = context.resources.configuration.layoutDirection
            orientation = LinearLayout.VERTICAL
            val gap = FlymeAutoStyle.dp(context, FlymeAutoStyle.CONTROL_GAP_DP)
            setPadding(gap, gap, 0, gap)
        }
        rail.addView(TextView(context).apply {
            setText(R.string.diagnostics_test_title)
            textSize = FlymeAutoStyle.NAVIGATION_TEXT_SP
            setTextColor(palette.secondaryText)
            val gap = FlymeAutoStyle.dp(context, FlymeAutoStyle.CONTROL_GAP_DP)
            setPadding(0, gap, 0, gap)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        val menu = listOf(Page.WHEEL to R.string.wheel_test_title, Page.MFI to R.string.diagnostics_mfi_title).associate { (page, title) ->
            page to Button(context).apply {
                setText(title)
                setOnClickListener { changed(page) }
                rail.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = FlymeAutoStyle.dp(context, FlymeAutoStyle.CONTROL_GAP_DP)
                })
            }
        }
        rail.addView(Button(context).apply {
            setText(R.string.back)
            FlymeAutoStyle.button(this, palette, compact = true)
            minimumWidth = 0
            minWidth = 0
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = FlymeAutoStyle.dp(context, FlymeAutoStyle.BLOCK_GAP_DP)
        })
        val railScroll = ScrollView(context).apply { isFillViewport = true; addView(rail) }
        root.addView(railScroll, LinearLayout.LayoutParams(FlymeAutoStyle.dp(context, menuWidthDp(context.resources.configuration.screenWidthDp)), -1))
        root.addView(FrameLayout(context).apply {
            layoutDirection = context.resources.configuration.layoutDirection
            addView(wheel, FrameLayout.LayoutParams(-1, -1))
            addView(mfi, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(0, -1, 1f))
        root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val density = context.resources.displayMetrics.density
            val width = ((right - left) / density).toInt()
            val wanted = FlymeAutoStyle.dp(context, menuWidthDp(width))
            if (railScroll.layoutParams.width != wanted) {
                railScroll.layoutParams = (railScroll.layoutParams as LinearLayout.LayoutParams).apply { this.width = wanted }
            }
        }
        return Views(root, wheel, mfi, menu, palette).also { it.select(selected) }
    }
    companion object {
        fun menuWidthDp(widthDp: Int) = minOf(FlymeAutoStyle.CONTROL_HEIGHT_DP * 4,
            maxOf(FlymeAutoStyle.CONTROL_HEIGHT_DP * 2 + FlymeAutoStyle.CONTROL_GAP_DP * 2, widthDp / 3),
            widthDp / 2)
        fun detailWidthDp(widthDp: Int) = widthDp - menuWidthDp(widthDp)
    }
}
