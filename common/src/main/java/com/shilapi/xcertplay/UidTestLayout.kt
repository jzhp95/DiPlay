package com.shilapi.xcertplay

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R

internal class UidTestLayout(private val context: Context, private val palette: DiPlayPalette) {
    class Views(val root: ScrollView, val result: TextView, val identity: TextView, val details: TextView, val refresh: Button)
    private fun dp(value: Int) = FlymeAutoStyle.dp(context, value)

    fun build(): Views {
        val gap = dp(FlymeAutoStyle.BLOCK_GAP_DP)
        val padding = dp(if (DiagnosticsTestLayout.detailWidthDp(context.resources.configuration.screenWidthDp) < 480)
            FlymeAutoStyle.CONTROL_GAP_DP else FlymeAutoStyle.CARD_PADDING_DP)
        fun text(id: Int, size: Float, secondary: Boolean = false) = TextView(context).apply {
            setText(id); textSize = size
            setTextColor(if (secondary) palette.secondaryText else palette.primaryText)
            setPadding(0, dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP), 0, dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, gap, padding, padding)
            addView(text(R.string.diagnostics_uid_title, FlymeAutoStyle.PAGE_TITLE_SP))
            addView(text(R.string.diagnostics_uid_description, FlymeAutoStyle.SECONDARY_TEXT_SP, true))
        }
        fun card(title: Int): TextView {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = FlymeAutoStyle.card(context, palette)
                setPadding(padding, padding, padding, padding)
                addView(text(title, FlymeAutoStyle.BUTTON_TEXT_SP))
            }
            val value = text(R.string.diagnostics_mfi_unchecked, FlymeAutoStyle.BODY_TEXT_SP).apply { setTextIsSelectable(true) }
            card.addView(value, LinearLayout.LayoutParams(-1, -2))
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            return value
        }
        val result = card(R.string.diagnostics_uid_read_title)
        val identity = card(R.string.diagnostics_uid_identity_title)
        val details = card(R.string.diagnostics_uid_details_title)
        content.addView(text(R.string.diagnostics_uid_permission_note, FlymeAutoStyle.SECONDARY_TEXT_SP, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        val refresh = Button(context).apply {
            setText(R.string.diagnostics_mfi_refresh)
            FlymeAutoStyle.button(this, palette, primary = true)
        }
        content.addView(refresh, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        val views = Views(ScrollView(context).apply { isFillViewport = true; addView(content) }, result, identity, details, refresh)
        refresh.setOnClickListener { show(views, UidDiagnostic.read(context)) }
        show(views, UidDiagnostic.read(context))
        return views
    }

    fun show(views: Views, state: UidDiagnostic) {
        views.result.text = if (state.processUid != null) context.getString(R.string.diagnostics_uid_read_ok, state.processUid)
            else context.getString(R.string.diagnostics_uid_read_failed)
        views.result.setTextColor(if (state.processUid != null) palette.success else palette.warning)
        views.identity.setText(when {
            state.processUid == null -> R.string.diagnostics_uid_unknown
            state.isSystem -> R.string.diagnostics_uid_system
            else -> R.string.diagnostics_uid_ordinary
        })
        views.identity.setTextColor(if (state.isSystem) palette.success else palette.primaryText)
        views.details.text = context.getString(R.string.diagnostics_uid_details, state.processUid?.toString() ?: "—",
            state.installedUid?.toString() ?: "—", state.appId?.toString() ?: "—", state.packageName,
            context.getString(when {
                state.processUid == null || state.installedUid == null -> R.string.diagnostics_uid_unknown
                state.matchesInstallation -> R.string.diagnostics_uid_matches
                else -> R.string.diagnostics_uid_differs
            }))
    }
}
