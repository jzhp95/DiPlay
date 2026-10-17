package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import com.shilapi.xcertplay.host.R

internal interface AppAppearanceOwner {
    val currentAppNight: Boolean
}

internal fun Context.appDialogTheme(): Int = if (
    (this as? AppAppearanceOwner)?.currentAppNight ?: resolveAppNightNow()
) {
    R.style.Theme_Xcertplay_Dialog_Dark
} else {
    R.style.Theme_Xcertplay_Dialog_Light
}

internal fun Context.appDialogContext(): Context = ContextThemeWrapper(this, appDialogTheme())

internal fun Context.appDialogBuilder(): AlertDialog.Builder = CarDialogBuilder(this)

private class CarDialogBuilder(owner: Context) : AlertDialog.Builder(owner, owner.appDialogTheme()) {
    private val palette = DiPlayPalette.of((owner as? AppAppearanceOwner)?.currentAppNight ?: owner.resolveAppNightNow())

    override fun setView(view: android.view.View): AlertDialog.Builder = super.setView(CarDialogStyle.scrollable(view))

    override fun create(): AlertDialog = super.create().also { CarDialogStyle.install(it, palette) }

    override fun show(): AlertDialog = create().also { it.show() }
}

internal fun Context.styleAppDialog(dialog: Dialog) {
    val night = (this as? AppAppearanceOwner)?.currentAppNight ?: resolveAppNightNow()
    CarDialogStyle.install(dialog, DiPlayPalette.of(night))
}

internal fun Context.appDialog(): Dialog = Dialog(this, appDialogTheme()).also(::styleAppDialog)
