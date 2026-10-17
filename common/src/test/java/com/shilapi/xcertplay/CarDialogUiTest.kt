package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.util.TypedValue
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29, 33], manifest = Config.NONE, qualifiers = "w960dp-h540dp-mdpi")
class CarDialogUiTest {
    private val controllers = mutableListOf<org.robolectric.android.controller.ActivityController<Activity>>()
    private fun activity() = Robolectric.buildActivity(Activity::class.java).also(controllers::add).setup().get()
    @After fun releaseWindows() {
        controllers.forEach { it.pause().stop().destroy() }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun settle(dialog: AlertDialog) {
        shadowOf(Looper.getMainLooper()).idle()
        val decor = dialog.window!!.decorView
        val width = dialog.window!!.attributes.width
        repeat(3) {
            fun force(view: View) {
                view.forceLayout()
                if (view is ViewGroup) for (i in 0 until view.childCount) force(view.getChildAt(i))
            }
            force(decor)
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.AT_MOST))
            decor.layout(0, 0, width, decor.measuredHeight)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun confirmationUsesLargeRealButtonsAndKeepsItsCallback() {
        val host = activity()
        var confirmed = 0
        val dialog = host.appDialogBuilder().setTitle("断开 CarPlay")
            .setMessage("断开连接后将释放媒体焦点。")
            .setPositiveButton("断开连接") { _, _ -> confirmed++ }
            .setNegativeButton("取消", null).show()
        settle(dialog)
        val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        assertTrue("touch target must be 72dp", button.minimumHeight >= 72)
        assertTrue("button text must be 20sp", button.textSize >= TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, host.resources.displayMetrics))
        assertTrue("message text must be 20sp", dialog.findViewById<TextView>(android.R.id.message).textSize >= 20f)
        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, confirmed)
        assertFalse(dialog.isShowing)
        host.finish()
    }

    @Test fun createThenShowPreservesValidationAndOnShowListener() {
        val host = activity()
        val input = EditText(host.appDialogContext()).apply { setText("无效") }
        val dialog = host.appDialogBuilder().setTitle("分辨率").setView(input)
            .setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        var shown = 0
        dialog.setOnShowListener {
            shown++
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString() != "160") input.error = "请输入有效数值"
                else dialog.dismiss()
            }
        }
        dialog.show(); settle(dialog)
        assertEquals(1, shown)
        assertTrue(input.textSize >= TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, host.resources.displayMetrics))
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        assertNotNull(input.error)
        assertTrue(dialog.isShowing)
        input.setText("160")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
        host.finish()
    }

    @Test fun choiceRowsAreLargeAndKeepSelectionUntilSave() {
        val host = activity()
        var selected = 1
        var saved = -1
        val dialog = host.appDialogBuilder().setTitle("音频通道")
            .setSingleChoiceItems(arrayOf("通道一", "通道二", "通道三"), selected) { _, index -> selected = index }
            .setPositiveButton("保存") { _, _ -> saved = selected }.setNegativeButton("取消", null).show()
        settle(dialog)
        val list = dialog.listView
        val row = list.adapter.getView(2, null, list)
        assertTrue("entire choice row must be easy to touch", row.minimumHeight >= 68)
        assertTrue(list.isItemChecked(1))
        list.performItemClick(row, 2, list.adapter.getItemId(2))
        assertEquals(2, selected)
        assertTrue(dialog.isShowing)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, saved)
        host.finish()
    }

    @Test @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun narrowDialogsStackLongActionsWithoutLosingButtons() {
        val host = activity()
        val dialog = host.appDialogBuilder().setTitle("显示设置").setMessage("选择应用设置的方式。")
            .setNeutralButton("恢复默认设置", null).setNegativeButton("取消本次修改", null)
            .setPositiveButton("应用并重新连接", null).show()
        settle(dialog)
        val buttons = listOf(-3, -2, -1).map { dialog.getButton(it) }
        assertTrue(dialog.window!!.attributes.width <= 360)
        assertTrue(buttons.joinToString { "${it.text}: ${it.width}x${it.height} y=${it.top} parent=${it.parent.javaClass.name}" }, buttons.all { it.height >= 72 && it.width > 0 })
        assertTrue(buttons.zipWithNext().all { (a, b) -> a.bottom <= b.top })
        dialog.dismiss(); host.finish()
    }
    @Test fun cancelAndDismissCallbacksKeepTheirDistinctMeaning() {
        val host = activity()
        var selected = -1
        var cancelled = 0
        var dismissed = 0
        val dialog = host.appDialogBuilder().setTitle("选择 iPhone")
            .setItems(arrayOf("手机一", "手机二")) { _, index -> selected = index }
            .setNegativeButton("取消", null).setOnCancelListener { cancelled++ }
            .setOnDismissListener { dismissed++ }.show()
        settle(dialog)
        dialog.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(-1, selected)
        assertEquals(1, cancelled)
        assertEquals(1, dismissed)
        host.finish()
    }

    @Test fun timePickerKeepsItsTimeCallbackWithLargeActions() {
        val host = activity()
        var selected = -1
        val dialog = TimePickerDialog(host, host.appDialogTheme(), { _, hour, minute -> selected = hour * 60 + minute }, 18, 30, true)
        host.styleAppDialog(dialog)
        dialog.show(); settle(dialog)
        assertTrue(dialog.getButton(-1).minimumHeight >= 72)
        dialog.getButton(-1).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(18 * 60 + 30, selected)
        host.finish()
    }

}
