package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.WrapperListAdapter
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** App-owned dialog chrome. Keep AlertDialog's callbacks, check states and validation semantics. */
internal object CarDialogStyle {
    private const val MAX_WIDTH_DP = 760
    private const val WINDOW_MARGIN_DP = 16
    private const val CORNER_DP = FlymeAutoStyle.CARD_CORNER_DP
    private const val BUTTON_HEIGHT_DP = FlymeAutoStyle.DIALOG_CONTROL_HEIGHT_DP
    private const val ROW_HEIGHT_DP = 68
    private const val INPUT_HEIGHT_DP = 64
    private const val ACTION_GAP_DP = 12
    private const val BUTTON_PADDING_DP = 20
    private const val TITLE_SP = 26f
    private const val BODY_SP = FlymeAutoStyle.BUTTON_TEXT_SP
    private const val INPUT_SP = 22f

    fun install(dialog: Dialog, palette: DiPlayPalette) {
        val decor = dialog.window?.decorView ?: return
        // Do not take over OnShow: numeric forms install validation handlers there.
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { apply(dialog, palette) }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }

    /** Native custom panels do not consistently scroll when the keyboard reduces the window. */
    fun scrollable(view: View): View = if (view is ScrollView) view else ScrollView(view.context).apply {
        isFillViewport = false
        clipToPadding = false
        addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun apply(dialog: Dialog, palette: DiPlayPalette) {
        val window = dialog.window ?: return
        val decor = window.decorView
        fun dp(value: Int) = (value * decor.resources.displayMetrics.density + .5f).toInt()
        val availableWidth = dp(decor.resources.configuration.screenWidthDp)
        val width = min(dp(MAX_WIDTH_DP), max(1, availableWidth - dp(WINDOW_MARGIN_DP * 2)))
        window.setBackgroundDrawable(shape(palette.dialogSurface, dp(CORNER_DP).toFloat()))
        decor.clipToOutline = true
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = .6f }
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        decorateTree(decor, palette)
        if (dialog !is AlertDialog) return
        val titleId = decor.resources.getIdentifier("alertTitle", "id", "android")
        (if (titleId != 0) decor.findViewById<TextView>(titleId) else null)?.apply {
            textSize = TITLE_SP
            setTextColor(palette.primaryText)
            setTypeface(typeface, Typeface.BOLD)
            isSingleLine = false
            maxLines = Int.MAX_VALUE
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(palette.secondaryText)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        dialog.listView?.let { list ->
            val original = list.adapter
            if (original != null && original !is CarChoiceAdapter) {
                val checked = list.checkedItemPositions?.clone()
                list.adapter = CarChoiceAdapter(original, palette)
                checked?.let { for (i in 0 until it.size()) list.setItemChecked(it.keyAt(i), it.valueAt(i)) }
            }
            list.divider = null
        }
        val buttons = listOf(AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)
            .mapNotNull { dialog.getButton(it) }.filter { it.visibility != View.GONE }
        val originalBar = buttons.firstOrNull()?.parent as? ViewGroup ?: return
        if (buttons.any { it.parent !== originalBar }) return
        val barHolder = originalBar.parent as? ViewGroup ?: return
        val panelId = decor.resources.getIdentifier("parentPanel", "id", "android")
        val parent = (if (panelId != 0) decor.findViewById<ViewGroup>(panelId) else null) ?: return
        // Vendor ButtonBarLayout can reverse or hide actions when it decides to stack.
        // Own this layout, but retain the actual Button objects and their listeners.
        originalBar.removeAllViews()
        val bar = LinearLayout(originalBar.context).apply { id = originalBar.id }
        barHolder.removeView(originalBar)
        ownPanel(parent, bar, dialog.listView != null)
        bar.gravity = Gravity.CENTER
        bar.setPadding(dp(BUTTON_PADDING_DP), dp(ACTION_GAP_DP), dp(BUTTON_PADDING_DP), dp(BUTTON_PADDING_DP))
        buttons.forEach { button ->
            styleButton(button, palette, button === dialog.getButton(AlertDialog.BUTTON_POSITIVE))
            bar.addView(button)
        }
        var lastWidth = -1
        fun arrange() {
            val inside = (if (bar.width > 0) bar.width else width) - bar.paddingLeft - bar.paddingRight
            if (inside <= 0 || inside == lastWidth) return
            lastWidth = inside
            val required = buttons.sumOf { max(dp(140), ceil(it.paint.measureText(it.text.toString())).toInt() + dp(BUTTON_PADDING_DP * 2)) } +
                dp(ACTION_GAP_DP) * (buttons.size - 1)
            val shortWindow = decor.resources.configuration.screenHeightDp < 480
            val canWrapInRow = inside >= dp(140) * buttons.size
            val stacked = required > inside && !(shortWindow && canWrapInRow)
            bar.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            buttons.forEachIndexed { index, button ->
                val horizontalPadding = if (!stacked && buttons.size >= 3) ACTION_GAP_DP else BUTTON_PADDING_DP
                button.setPadding(dp(horizontalPadding), dp(ACTION_GAP_DP), dp(horizontalPadding), dp(ACTION_GAP_DP))
                button.layoutParams = LinearLayout.LayoutParams(
                    if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (stacked) 0f else 1f,
                ).apply {
                    if (index < buttons.lastIndex) {
                        if (stacked) bottomMargin = dp(ACTION_GAP_DP) else marginEnd = dp(ACTION_GAP_DP)
                    }
                }
            }
        }
        arrange()
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            arrange()
            if (bar.orientation == LinearLayout.HORIZONTAL) {
                val tallest = buttons.maxOfOrNull { it.height } ?: 0
                buttons.filter { it.minimumHeight < tallest }.forEach { it.minimumHeight = tallest }
            }
        }
    }

    /** Reserve action and title space first. Only the body gives up space on a short window/IME. */
    private fun ownPanel(nativePanel: ViewGroup, bar: LinearLayout, hasList: Boolean) {
        val holder = nativePanel.parent as? ViewGroup ?: return
        val titleId = nativePanel.resources.getIdentifier("topPanel", "id", "android")
        val title = (if (titleId != 0) nativePanel.findViewById<View>(titleId) else null)?.takeIf { it.parent === nativePanel && it.visibility != View.GONE }
        val buttonPanelId = nativePanel.resources.getIdentifier("buttonPanel", "id", "android")
        val bodyParts = (0 until nativePanel.childCount).map(nativePanel::getChildAt)
            .filter { it !== title && (buttonPanelId == 0 || it.id != buttonPanelId) && it.visibility != View.GONE }
        title?.let(nativePanel::removeView)
        bodyParts.forEach(nativePanel::removeView)
        fun hasScroll(view: View): Boolean = view is ScrollView ||
            (view is ViewGroup && (0 until view.childCount).any { hasScroll(view.getChildAt(it)) })
        val body: View = if (bodyParts.size == 1 && (hasList || hasScroll(bodyParts.single()))) {
            FrameLayout(nativePanel.context).apply {
                addView(bodyParts.single(), FrameLayout.LayoutParams(-1, -1))
            }
        } else {
            // Retain the existing message/custom views and all their listeners.
            ScrollView(nativePanel.context).apply {
                isFillViewport = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    bodyParts.forEach { part ->
                        val old = part.layoutParams as? ViewGroup.MarginLayoutParams
                        addView(part, LinearLayout.LayoutParams(-1, -2).apply {
                            if (old != null) { marginStart = old.marginStart; marginEnd = old.marginEnd }
                        })
                    }
                })
            }
        }
        val replacement = CarDialogPanel(nativePanel.context, title, body, bar).apply { id = nativePanel.id }
        val index = holder.indexOfChild(nativePanel)
        val params = nativePanel.layoutParams
        holder.removeView(nativePanel)
        holder.addView(replacement, index, params)
    }

    private class CarDialogPanel(context: android.content.Context, private val title: View?,
        private val body: View, private val actions: View) : ViewGroup(context) {
        init { title?.let(::addView); addView(body); addView(actions) }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val maxHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
                resources.displayMetrics.heightPixels else MeasureSpec.getSize(heightMeasureSpec)
            val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            actions.measure(exactWidth, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            val remaining = (maxHeight - actions.measuredHeight).coerceAtLeast(0)
            title?.measure(exactWidth, MeasureSpec.makeMeasureSpec(remaining, MeasureSpec.AT_MOST))
            val bodyHeight = (remaining - (title?.measuredHeight ?: 0)).coerceAtLeast(0)
            body.measure(exactWidth, MeasureSpec.makeMeasureSpec(bodyHeight, MeasureSpec.AT_MOST))
            setMeasuredDimension(width, min(maxHeight, actions.measuredHeight + (title?.measuredHeight ?: 0) + body.measuredHeight))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            var y = 0
            title?.let { it.layout(0, y, measuredWidth, y + it.measuredHeight); y += it.measuredHeight }
            body.layout(0, y, measuredWidth, y + body.measuredHeight)
            y += body.measuredHeight
            actions.layout(0, y, measuredWidth, y + actions.measuredHeight)
        }
    }

    private fun decorateTree(view: View, palette: DiPlayPalette) {
        val density = view.resources.displayMetrics.density
        when (view) {
            is ListView -> return // Rows are inflated lazily; decorate them through the adapter.
            is CompoundButton -> {
                enlargeText(view, BODY_SP)
                view.minimumHeight = max(view.minimumHeight, (ROW_HEIGHT_DP * density).toInt())
            }
            is Button -> styleButton(view, palette, primary = false)
            is EditText -> {
                enlargeText(view, INPUT_SP)
                view.minimumHeight = max(view.minimumHeight, (INPUT_HEIGHT_DP * density).toInt())
            }
            is TextView -> {
                enlargeText(view, BODY_SP)
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) decorateTree(view.getChildAt(i), palette)
    }

    private fun enlargeText(view: TextView, minimumSp: Float) {
        val minimum = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minimumSp, view.resources.displayMetrics)
        if (view.textSize < minimum) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, minimum)
    }

    private fun styleButton(button: Button, palette: DiPlayPalette, primary: Boolean) {
        fun dp(value: Int) = (value * button.resources.displayMetrics.density + .5f).toInt()
        enlargeText(button, BODY_SP)
        button.isAllCaps = false
        button.isSingleLine = false
        button.maxLines = Int.MAX_VALUE
        button.gravity = Gravity.CENTER
        button.minimumHeight = max(button.minimumHeight, dp(BUTTON_HEIGHT_DP))
        button.minHeight = button.minimumHeight
        button.minimumWidth = 0
        button.minWidth = 0
        button.setPadding(dp(BUTTON_PADDING_DP), dp(ACTION_GAP_DP), dp(BUTTON_PADDING_DP), dp(ACTION_GAP_DP))
        button.layoutParams?.let { params -> if (params.height >= 0 && params.height < button.minimumHeight) params.height = button.minimumHeight }
        val fill = if (primary) palette.accent else palette.button
        val text = if (primary) palette.onAccent else palette.primaryText
        button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf((text and 0x00ffffff) or 0x66000000, text)))
        button.backgroundTintList = null
        val radius = dp(BUTTON_HEIGHT_DP).toFloat() / 4f
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(fill, radius, dp(2), palette.focusRing))
            addState(intArrayOf(), shape(fill, radius))
        }
        button.background = RippleDrawable(ColorStateList.valueOf(palette.ripple), states, shape(Color.WHITE, radius))
    }

    private fun shape(color: Int, radius: Float, stroke: Int = 0, outline: Int = color) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, outline)
    }

    private class CarChoiceAdapter(private val original: ListAdapter, private val palette: DiPlayPalette) :
        WrapperListAdapter, ListAdapter by original {
        override fun getWrappedAdapter(): ListAdapter = original
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            original.getView(position, convertView, parent).also { row ->
                decorateTree(row, palette)
                val height = (ROW_HEIGHT_DP * row.resources.displayMetrics.density + .5f).toInt()
                row.minimumHeight = max(row.minimumHeight, height)
                row.layoutParams?.let { if (it.height >= 0 && it.height < height) it.height = height }
                row.background = RippleDrawable(ColorStateList.valueOf(palette.ripple), null, shape(Color.WHITE, 0f))
            }
    }
}
