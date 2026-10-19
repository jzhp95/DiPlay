package com.diplay.home

import com.shilapi.xcertplay.FlymeAutoStyle
import com.shilapi.xcertplay.FlymeSampleStyle
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * A column of standard Android widgets: any installed provider, added from a list, bound with the
 * owner's consent, configured when the provider asks, kept in order across restarts, and removed
 * in edit mode.
 */
class WidgetBoard(private val activity: Activity, private val column: LinearLayout) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = AppWidgetHost(activity, HOST_ID)
    private val prefs = activity.getSharedPreferences("widgets", Activity.MODE_PRIVATE)
    private var pendingId: Int? = null
    private val removeButtons = mutableListOf<View>()

    var editing = false
        set(value) {
            field = value
            removeButtons.forEach { it.visibility = if (value) View.VISIBLE else View.GONE }
        }

    fun savePendingState(outState: Bundle) {
        pendingId?.let { outState.putInt(KEY_PENDING_ID, it) }
    }

    fun restorePendingState(state: Bundle?) {
        pendingId = state?.takeIf { it.containsKey(KEY_PENDING_ID) }?.getInt(KEY_PENDING_ID)
    }

    fun restore() {
        val ids = savedIds()
        val kept = ids.filter { id ->
            val info = manager.getAppWidgetInfo(id)
            if (info == null) host.deleteAppWidgetId(id) else addView(id, info)
            info != null
        }
        if (kept != ids) save(kept)
    }

    fun startListening() = runCatching { host.startListening() }

    fun stopListening() = runCatching { host.stopListening() }

    /** Shows every installed widget, by app and name. */
    fun pick() {
        val providers = manager.installedProviders.sortedWith(
            compareBy({ appLabel(it) }, { it.loadLabel(activity.packageManager) }),
        )
        if (providers.isEmpty()) {
            Toast.makeText(activity, R.string.home_no_widgets, Toast.LENGTH_LONG).show()
            return
        }
        val labels = providers.map { "${appLabel(it)} · ${it.loadLabel(activity.packageManager)}" }.toTypedArray()
        FlymeSampleStyle.dialog(activity)
            .setTitle(R.string.home_add_widget)
            .setItems(labels) { _, index -> add(providers[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .let { FlymeSampleStyle.showDialog(it, activity) }
    }

    private fun add(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        if (manager.bindAppWidgetIdIfAllowed(id, info.provider)) {
            configure(id, info)
            return
        }
        val consent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
        try {
            pendingId = id
            @Suppress("DEPRECATION")
            activity.startActivityForResult(consent, REQUEST_BIND)
        } catch (_: ActivityNotFoundException) {
            // BYD has no consent screen; the owner allows binding once over adb.
            pendingId = null
            host.deleteAppWidgetId(id)
            FlymeSampleStyle.dialog(activity)
                .setTitle(R.string.home_allow_widgets)
                .setMessage(
                    activity.getString(R.string.home_allow_widgets_description, activity.packageName),
                )
                .setPositiveButton(android.R.string.ok, null)
                .let { FlymeSampleStyle.showDialog(it, activity) }
        }
    }

    private fun configure(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure == null) {
            commit(id)
            return
        }
        pendingId = id
        try {
            host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null)
        } catch (_: ActivityNotFoundException) {
            commit(id)
        }
    }

    /** Pass the activity's results here. Returns whether it was one of ours. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE) return false
        val id = pendingId ?: return true
        pendingId = null
        if (resultCode != Activity.RESULT_OK) {
            host.deleteAppWidgetId(id)
            return true
        }
        val info = manager.getAppWidgetInfo(id)
        when {
            info == null -> host.deleteAppWidgetId(id)
            requestCode == REQUEST_BIND -> configure(id, info)
            else -> commit(id)
        }
        return true
    }

    private fun commit(id: Int) {
        val info = manager.getAppWidgetInfo(id) ?: return
        save(savedIds() + id)
        addView(id, info)
    }

    private fun addView(id: Int, info: AppWidgetProviderInfo) {
        val density = activity.resources.displayMetrics.density
        val height = maxOf(info.minHeight, (MIN_HEIGHT_DP * density).toInt())
        val widget = host.createView(activity.applicationContext, id, info)
        val remove = android.widget.Button(activity).apply {
            val palette = FlymeSampleStyle.palette(activity)
            FlymeAutoStyle.button(this, palette, compact = true)
            minimumWidth = 0; minWidth = 0
            minHeight = (48 * density).toInt()
            setPadding(0, 0, 0, 0)
            text = "✕"
            contentDescription = activity.getString(R.string.home_remove_widget)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            background = FlymeAutoStyle.control(activity, palette, palette.danger, (48 * density).toInt())
            visibility = if (editing) View.VISIBLE else View.GONE
        }
        val card = FrameLayout(activity).apply {
            background = FlymeAutoStyle.card(activity, FlymeSampleStyle.palette(activity))
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            addView(widget, FrameLayout.LayoutParams(-1, -1))
            addView(remove, FrameLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt(), Gravity.TOP or Gravity.END))
        }
        remove.setOnClickListener {
            column.removeView(card)
            removeButtons.remove(remove)
            host.deleteAppWidgetId(id)
            save(savedIds() - id)
        }
        removeButtons += remove
        column.addView(card, LinearLayout.LayoutParams(-1, height).apply { bottomMargin = FlymeAutoStyle.dp(activity, FlymeAutoStyle.BLOCK_GAP_DP) })
        // Tell the widget its real size once it is laid out.
        widget.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && widget.width > 0 && widget.height > 0) {
                val size = SizeF(widget.width / density, widget.height / density)
                runCatching { widget.updateAppWidgetSize(Bundle(), listOf(size)) }
            }
        }
    }

    private fun appLabel(info: AppWidgetProviderInfo): String = runCatching {
        activity.packageManager.getApplicationLabel(
            activity.packageManager.getApplicationInfo(info.provider.packageName, 0),
        ).toString()
    }.getOrDefault(info.provider.packageName)

    private fun savedIds(): List<Int> =
        prefs.getString(KEY_IDS, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    private fun save(ids: List<Int>) = prefs.edit().putString(KEY_IDS, ids.joinToString(",")).apply()

    companion object {
        private const val HOST_ID = 1024
        private const val REQUEST_BIND = 11
        private const val REQUEST_CONFIGURE = 12
        private const val KEY_IDS = "ids"
        private const val KEY_PENDING_ID = "pending_widget_id"
        private const val MIN_HEIGHT_DP = 140
    }
}
