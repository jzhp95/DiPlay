package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** An editor owns only its draft; the caller owns persistence and final validation. */
internal object SettingsTextEditor {
    fun show(context: Context, title: String, value: String, secret: Boolean = false,
        onDismiss: () -> Unit = {}, onSaved: (String) -> Unit): AlertDialog {
        val palette = DiPlayPalette.of((context as? AppAppearanceOwner)?.currentAppNight ?: false)
        fun dp(n: Int) = FlymeAutoStyle.dp(context, n)
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(FlymeAutoStyle.INPUT_HEIGHT_DP)
            val inset = dp(FlymeAutoStyle.INPUT_PADDING_DP)
            setPadding(inset, 0, inset, 0)
            background = FlymeAutoStyle.inputBackground(context, palette)
            setAddStatesFromChildren(true)
        }
        val input = EditText(context).apply {
            setText(value)
            contentDescription = title
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.VALUE)
            setTextColor(palette.primaryText)
            background = null
            minimumHeight = dp(FlymeAutoStyle.INPUT_HEIGHT_DP)
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        fun icon(resource: Int, description: String, clicked: (ImageButton) -> Unit) = ImageButton(context).apply {
            setImageResource(resource)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.secondaryText)
            contentDescription = description
            background = FlymeAutoStyle.control(context, palette, android.graphics.Color.TRANSPARENT,
                dp(FlymeAutoStyle.INPUT_ACTION_DP))
            val padding = dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP)
            setPadding(padding, padding, padding, padding)
            FlymePressFeedback.install(this)
            setOnClickListener { clicked(this) }
            row.addView(this, LinearLayout.LayoutParams(dp(FlymeAutoStyle.INPUT_ACTION_DP), dp(FlymeAutoStyle.INPUT_ACTION_DP)))
        }
        icon(R.drawable.ic_dp_clear, context.getString(R.string.settings_clear_input)) { input.text.clear() }
        if (secret) icon(R.drawable.ic_dp_eye, context.getString(R.string.settings_hotspot_show_password)) { button ->
            val show = input.transformationMethod != null
            val cursor = input.selectionStart.coerceAtLeast(0)
            input.transformationMethod = if (show) null else PasswordTransformationMethod.getInstance()
            input.setSelection(cursor.coerceAtMost(input.length()))
            button.setImageResource(if (show) R.drawable.ic_dp_eye_off else R.drawable.ic_dp_eye)
            button.contentDescription = context.getString(if (show) R.string.settings_hotspot_hide_password
                else R.string.settings_hotspot_show_password)
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val inset = dp(FlymeAutoStyle.CARD_PADDING_DP)
            setPadding(inset, inset, inset, inset)
            addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = context.appDialogBuilder().setTitle(title).setView(body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ -> onSaved(input.text.toString()) }.create()
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
                true
            } else false
        }
        dialog.setOnDismissListener {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
            onDismiss()
        }
        dialog.show()
        input.requestFocus()
        input.setSelection(input.length())
        return dialog
    }
}
