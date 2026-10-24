package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import org.robolectric.shadows.ShadowAlertDialog

internal fun editorViews(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(editorViews(view.getChildAt(i)))
}
internal fun openHotspotEditor(activity: Activity, root: View, title: Int): AlertDialog {
    editorViews(root).filterIsInstance<Button>().single {
        it.text.startsWith(activity.getString(title) + " · ")
    }.performClick()
    return ShadowAlertDialog.getLatestAlertDialog()
}
internal fun editHotspotDraft(activity: Activity, root: View, title: Int, value: String) {
    val dialog = openHotspotEditor(activity, root, title)
    editorViews(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText(value)
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
}
