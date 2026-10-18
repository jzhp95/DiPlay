package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.ConnectionSettingsSection
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.mfi.LocalMfiDiagnostic
import com.shilapi.xcertplay.mfi.MfiCoprocessorDiagnostic

internal class MfiTestLayout(private val context: Context, private val palette: DiPlayPalette) {
    data class Views(val root: ScrollView, val local: TextView, val usb: TextView, val chip: TextView,
        val refresh: Button, val probe: Button)
    private fun dp(value: Int) = FlymeAutoStyle.dp(context, value)
    private val contentPadding get() = dp(if (DiagnosticsTestLayout.detailWidthDp(
        context.resources.configuration.screenWidthDp) < 480) FlymeAutoStyle.CONTROL_GAP_DP else FlymeAutoStyle.CARD_PADDING_DP)
    private fun text(id: Int, size: Float = FlymeAutoStyle.BODY_TEXT_SP) = TextView(context).apply {
        setText(id); textSize = size; setTextColor(palette.primaryText)
        val gap = dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP)
        setPadding(0, gap, 0, gap)
    }
    fun build(refresh: () -> Unit, probe: () -> Unit, back: () -> Unit, authenticationChanged: () -> Unit = {}): Views {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = contentPadding
            setPadding(padding, dp(FlymeAutoStyle.BLOCK_GAP_DP), padding, padding)
        }
        fun button(id: Int, action: () -> Unit) = Button(context).apply {
            setText(id); FlymeAutoStyle.button(this, palette); setOnClickListener { action() }
        }
        // Stack the header and actions so large fonts remain usable in a narrow detail panel.
        content.addView(text(R.string.diagnostics_mfi_title, FlymeAutoStyle.PAGE_TITLE_SP))
        content.addView(text(R.string.diagnostics_mfi_description, FlymeAutoStyle.SECONDARY_TEXT_SP).apply { setTextColor(palette.secondaryText) })
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = FlymeAutoStyle.card(context, palette)
            val padding = contentPadding
            setPadding(padding, padding, padding, padding)
            addView(ConnectionSettingsSection.createMfiTargetChoice(
                context, AirPlayPersistence.loadMfiTarget(context), SettingsTheme.card(palette),
            ) { target ->
                AirPlayPersistence.saveMfiTarget(context, target)
                authenticationChanged()
            }.container)
            addView(text(R.string.settings_mfi_target_description, FlymeAutoStyle.SECONDARY_TEXT_SP).apply {
                setTextColor(palette.secondaryText)
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(FlymeAutoStyle.BLOCK_GAP_DP) })
        fun card(title: Int): TextView {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = FlymeAutoStyle.card(context, palette)
                val padding = contentPadding
                setPadding(padding, padding, padding, padding)
                addView(text(title, FlymeAutoStyle.BUTTON_TEXT_SP))
            }
            val result = text(R.string.diagnostics_mfi_unchecked)
            card.addView(result)
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(FlymeAutoStyle.BLOCK_GAP_DP) })
            return result
        }
        val local = card(R.string.diagnostics_mfi_local_title)
        val usb = card(R.string.diagnostics_mfi_usb_title)
        val chip = card(R.string.diagnostics_mfi_chip_title)
        val refreshButton = button(R.string.diagnostics_mfi_refresh, refresh)
        val probeButton = button(R.string.diagnostics_mfi_probe, probe)
        listOf(refreshButton, probeButton).forEach { content.addView(it,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(FlymeAutoStyle.CONTROL_GAP_DP) }) }
        return Views(ScrollView(context).apply { isFillViewport = true; addView(content) }, local, usb, chip, refreshButton, probeButton)
    }

    fun show(views: Views, state: MfiTestController.State) {
        views.local.text = state.local?.let { local ->
            val validation = when (local.status) {
                LocalMfiDiagnostic.Status.MISSING -> context.getString(R.string.diagnostics_mfi_materials_missing)
                LocalMfiDiagnostic.Status.INVALID -> context.getString(R.string.diagnostics_mfi_materials_invalid)
                LocalMfiDiagnostic.Status.READY -> context.getString(R.string.diagnostics_mfi_materials_ready, local.certificateBytes ?: 0)
            }
            fun presence(present: Boolean) = context.getString(if (present) R.string.diagnostics_mfi_present else R.string.diagnostics_mfi_missing)
            context.getString(R.string.diagnostics_mfi_materials,
                context.getString(if (local.source == LocalMfiDiagnostic.Source.INSTALLED) R.string.diagnostics_mfi_installed else R.string.diagnostics_mfi_bundled),
                presence(local.certificatePresent), presence(local.privateKeyPresent), validation)
        } ?: context.getString(R.string.diagnostics_mfi_unchecked)
        views.usb.text = when {
            state.usbAvailable == null -> context.getString(R.string.diagnostics_mfi_unchecked)
            state.usbAvailable == false -> context.getString(R.string.diagnostics_mfi_usb_unavailable)
            state.devices.isEmpty() -> context.getString(R.string.diagnostics_mfi_usb_absent)
            else -> context.getString(R.string.diagnostics_mfi_usb_devices, state.devices.size, state.devices.joinToString("\n"),
                context.getString(when (state.permission) {
                    MfiTestController.Permission.UNKNOWN -> R.string.diagnostics_mfi_unchecked
                    MfiTestController.Permission.MISSING -> R.string.diagnostics_mfi_permission_missing
                    MfiTestController.Permission.GRANTED -> R.string.diagnostics_mfi_permission_granted
                    MfiTestController.Permission.DENIED -> R.string.diagnostics_mfi_permission_denied
                }))
        }
        val operationMessage = when (state.operation) {
            MfiTestController.Operation.REFRESHING -> R.string.diagnostics_mfi_refreshing
            MfiTestController.Operation.WAITING_PERMISSION -> R.string.diagnostics_mfi_permission_waiting
            MfiTestController.Operation.PROBING -> R.string.diagnostics_mfi_probing
            MfiTestController.Operation.SESSION_BUSY -> R.string.diagnostics_mfi_session_busy
            MfiTestController.Operation.FAILED -> R.string.diagnostics_mfi_failed
            MfiTestController.Operation.CANCELLED -> R.string.diagnostics_mfi_cancelled
            MfiTestController.Operation.PERMISSION_TIMEOUT -> R.string.diagnostics_mfi_permission_timeout
            MfiTestController.Operation.IDLE -> null
        }
        views.chip.text = if (operationMessage != null) context.getString(operationMessage) else state.chip?.let { chip ->
            val address = chip.address7Bit?.let { "0x%02X".format(it) } ?: "—"
            when (chip.status) {
                MfiCoprocessorDiagnostic.Status.NOT_FOUND -> context.getString(R.string.diagnostics_mfi_chip_missing)
                MfiCoprocessorDiagnostic.Status.CERTIFICATE_UNREADABLE -> context.getString(R.string.diagnostics_mfi_chip_unreadable, address)
                MfiCoprocessorDiagnostic.Status.READABLE -> context.getString(R.string.diagnostics_mfi_chip_ready, address,
                    chip.protocolMajor?.toString() ?: "—", chip.certificateBytes ?: 0)
                MfiCoprocessorDiagnostic.Status.CANCELLED -> context.getString(R.string.diagnostics_mfi_cancelled)
            }
        } ?: context.getString(R.string.diagnostics_mfi_not_probed)
        views.refresh.isEnabled = !state.busy
        views.probe.isEnabled = !state.busy && state.devices.isNotEmpty()
    }
}
