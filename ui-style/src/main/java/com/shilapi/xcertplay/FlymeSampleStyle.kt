package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/** Appearance for standalone sample hosts, sharing the main app's presentation sources. */
internal object FlymeSampleStyle {
    fun palette(context: Context) = DiPlayPalette.of(
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)

    fun install(activity: Activity, root: View) {
        val palette = palette(activity)
        activity.window.setBackgroundDrawable(ColorDrawable(palette.background))
        activity.window.statusBarColor = palette.systemBar
        activity.window.navigationBarColor = palette.systemBar
        if (Build.VERSION.SDK_INT >= 30) {
            val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            activity.window.insetsController?.setSystemBarsAppearance(if (palette.systemBarIconsAreDark) appearance else 0, appearance)
            val left = root.paddingLeft; val top = root.paddingTop; val right = root.paddingRight; val bottom = root.paddingBottom
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
                insets
            }
        }
    }

    fun showDialog(builder: AlertDialog.Builder, context: Context) {
        val dialog = builder.create()
        CarDialogStyle.install(dialog, palette(context))
        dialog.show()
    }

    fun dialog(context: Context) = AlertDialog.Builder(context,
        if (palette(context).systemBarIconsAreDark) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert)
}
