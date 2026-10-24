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
    enum class Page { WHEEL, MFI, UID }
    class Views(val root: LinearLayout, private val pages: Map<Page, ScrollView>,
        private val menu: Map<Page, Button>, private val palette: DiPlayPalette,
        private val picker: Button? = null, private val titles: Map<Page, Int> = emptyMap()) {
        fun select(page: Page) {
            titles[page]?.let { title -> picker?.text = picker?.context?.getString(title) }
            pages.forEach { (item, view) -> view.visibility = if (item == page) View.VISIBLE else View.GONE }
            menu.forEach { (item, button) ->
                button.isSelected = item == page
                FlymeAutoStyle.button(button, palette, primary = item == page, compact = true)
                val inset = FlymeAutoStyle.dp(button.context, FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP)
                button.setPadding(inset, button.paddingTop, inset, button.paddingBottom)
            }
        }
    }
    fun build(wheel: ScrollView, mfi: ScrollView, selected: Page, changed: (Page) -> Unit, back: () -> Unit = {}, uid: ScrollView = UidTestLayout(context, palette).build().root): Views {
        val pages = linkedMapOf(Page.WHEEL to wheel, Page.MFI to mfi, Page.UID to uid)
        val titles = linkedMapOf(Page.WHEEL to R.string.wheel_test_title, Page.MFI to R.string.diagnostics_mfi_title,
            Page.UID to R.string.diagnostics_uid_title)
        val compact = FlymeAutoStyle.compact(context)
        val root = LinearLayout(context).apply {
            orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
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
            FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.SECTION_TITLE)
            setTextColor(palette.secondaryText)
            val gap = FlymeAutoStyle.dp(context, FlymeAutoStyle.CONTROL_GAP_DP)
            setPadding(0, gap, 0, gap)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        val menu = listOf(Page.WHEEL to R.string.wheel_test_title, Page.MFI to R.string.diagnostics_mfi_title, Page.UID to R.string.diagnostics_uid_title).associate { (page, title) ->
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
        val picker = if (compact) Button(context).apply {
            FlymeAutoStyle.button(this, palette)
            val chevron = context.getDrawable(R.drawable.ic_dp_chevron)?.mutate()?.apply {
                setTint(palette.secondaryText)
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron, null)
            setOnClickListener {
                context.appDialogBuilder().setTitle(R.string.diagnostics_test_title)
                    .setItems(titles.values.map(context::getString).toTypedArray()) { _, index ->
                        changed(titles.keys.elementAt(index))
                    }.setNegativeButton(R.string.cancel, null).show()
            }
        } else null
        if (picker != null) root.addView(LinearLayout(context).apply {
            val inset = FlymeAutoStyle.dp(context, FlymeAutoStyle.CONTROL_GAP_DP)
            setPadding(inset, inset, inset, inset)
            addView(Button(context).apply {
                setText(R.string.back)
                FlymeAutoStyle.action(this, palette, textOnly = true)
                setOnClickListener { back() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = inset })
            addView(picker, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2)) else root.addView(railScroll,
            LinearLayout.LayoutParams(FlymeAutoStyle.dp(context, menuWidthDp(context.resources.configuration.screenWidthDp)), -1))
        root.addView(FrameLayout(context).apply {
            layoutDirection = context.resources.configuration.layoutDirection
            pages.values.forEach { addView(it, FrameLayout.LayoutParams(-1, -1)) }
        }, LinearLayout.LayoutParams(if (compact) -1 else 0, if (compact) 0 else -1, 1f))
        root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val density = context.resources.displayMetrics.density
            val width = ((right - left) / density).toInt()
            val wanted = FlymeAutoStyle.dp(context, menuWidthDp(width))
            if (!compact && railScroll.layoutParams.width != wanted) {
                railScroll.layoutParams = (railScroll.layoutParams as LinearLayout.LayoutParams).apply { this.width = wanted }
            }
        }
        return Views(root, pages, menu, palette, picker, titles).also { it.select(selected) }
    }
    companion object {
        fun menuWidthDp(widthDp: Int) = minOf(FlymeAutoStyle.CONTROL_HEIGHT_DP * 4,
            maxOf(FlymeAutoStyle.CONTROL_HEIGHT_DP * 2 + FlymeAutoStyle.CONTROL_GAP_DP * 2, widthDp / 3),
            widthDp / 2)
        fun detailWidthDp(widthDp: Int) = widthDp - menuWidthDp(widthDp)
    }
}
