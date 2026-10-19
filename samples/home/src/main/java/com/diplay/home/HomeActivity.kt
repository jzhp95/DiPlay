package com.diplay.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.FlymeAutoStyle
import com.shilapi.xcertplay.FlymeSampleStyle
import android.widget.Button
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * DiPlay Home: the live CarPlay map, any Android widgets and the app list on one screen. An
 * ordinary app, so it can be the home screen without BYD's system privileges; BYD's own home
 * stays installed and one button away.
 */
class HomeActivity : ComponentActivity() {
    private lateinit var mapPanel: DiPlayMapPanel
    private lateinit var board: WidgetBoard
    private lateinit var appsOverlay: View
    private lateinit var appsAdapter: AppsAdapter
    private lateinit var editButton: TextView
    private val palette get() = FlymeSampleStyle.palette(this)
    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mapPanel = DiPlayMapPanel(this)
        val widgetColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        board = WidgetBoard(this, widgetColumn)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && resources.configuration.screenWidthDp >= 840 && resources.configuration.screenHeightDp >= 500

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(clock())
            addView(AspectFrame(context, 8f / 3).apply { addView(mapPanel, FrameLayout.LayoutParams(-1, -1)) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(FlymeAutoStyle.BLOCK_GAP_DP) })
            if (landscape) addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                fun actionParams() = if (landscape) weighted() else LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(FlymeAutoStyle.CONTROL_GAP_DP)
                }
                addView(pill(getString(R.string.home_apps)) { showApps(true) }, actionParams())
                addView(pill("CarPlay") { mapPanel.openCarPlay() }, actionParams())
                addView(pill(getString(R.string.home_byd_home)) { openBydHome() }, actionParams())
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(FlymeAutoStyle.BLOCK_GAP_DP) })
        }
        editButton = pill(getString(R.string.home_edit)) { setEditing(!board.editing) }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(getString(R.string.home_widgets), FlymeAutoStyle.PAGE_TITLE_SP), LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(editButton, LinearLayout.LayoutParams(0, -1, 1f))
                addView(pill(getString(R.string.home_add)) { board.pick() }, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    marginStart = dp(FlymeAutoStyle.CONTROL_GAP_DP)
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(FlymeAutoStyle.CONTROL_GAP_DP) })
            if (landscape) addView(ScrollView(context).apply { addView(widgetColumn) },
                LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(FlymeAutoStyle.BLOCK_GAP_DP) })
            else addView(widgetColumn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(FlymeAutoStyle.BLOCK_GAP_DP) })
        }
        val content = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            val inset = dp(FlymeAutoStyle.CARD_PADDING_DP)
            setPadding(inset, inset, inset, inset)
            if (landscape) {
                addView(left, LinearLayout.LayoutParams(0, -1, 3f))
                addView(right, LinearLayout.LayoutParams(0, -1, 2f).apply { marginStart = dp(FlymeAutoStyle.CARD_PADDING_DP) })
            } else {
                addView(left, LinearLayout.LayoutParams(-1, -2))
                addView(right, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(FlymeAutoStyle.CARD_PADDING_DP) })
            }
        }
        appsAdapter = AppsAdapter(this)
        appsOverlay = appsScreen()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(palette.background)
            val homeContent = if (landscape) content else ScrollView(context).apply { isFillViewport = true; addView(content) }
            addView(homeContent, FrameLayout.LayoutParams(-1, -1))
            addView(appsOverlay, FrameLayout.LayoutParams(-1, -1))
        })
        FlymeSampleStyle.install(this, findViewById(android.R.id.content))
        board.restorePendingState(savedInstanceState)
        board.restore()
        if (savedInstanceState?.getBoolean("editing") == true) setEditing(true)
        if (savedInstanceState?.getBoolean("apps") == true) showApps(true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    appsOverlay.visibility == View.VISIBLE -> showApps(false)
                    board.editing -> setEditing(false)
                    // Home has nowhere to go back to.
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        board.savePendingState(outState)
        outState.putBoolean("editing", board.editing)
        outState.putBoolean("apps", appsOverlay.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        mapPanel.start()
        board.startListening()
    }

    override fun onStop() {
        mapPanel.stop()
        board.stopListening()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while home is open: back to the main page.
        showApps(false)
        setEditing(false)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        if (!board.onActivityResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }

    private fun setEditing(editing: Boolean) {
        board.editing = editing
        editButton.setText(if (editing) R.string.home_done else R.string.home_edit)
    }

    private fun openBydHome() {
        val byd = Intent().setClassName(BYD_HOME_PACKAGE, BYD_HOME_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(byd) }.isFailure) {
            Toast.makeText(this, R.string.home_byd_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun showApps(show: Boolean) {
        if (show) appsAdapter.load()
        appsOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun appsScreen(): View = FrameLayout(this).apply {
        visibility = View.GONE
        setBackgroundColor(palette.background)
        isClickable = true // keep taps from reaching the home page underneath
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val inset = dp(FlymeAutoStyle.CARD_PADDING_DP)
            setPadding(inset, inset, inset, inset)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(getString(R.string.home_apps), FlymeAutoStyle.PAGE_TITLE_SP), LinearLayout.LayoutParams(0, -2, 1f))
                addView(pill(getString(R.string.home_close)) { showApps(false) }, LinearLayout.LayoutParams(dp(140), -2))
            })
            addView(GridView(context).apply {
                numColumns = GridView.AUTO_FIT
                columnWidth = dp(150)
                verticalSpacing = dp(20)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                adapter = appsAdapter
                setOnItemClickListener { _, _, position, _ ->
                    showApps(false)
                    runCatching { startActivity(appsAdapter.launchIntent(position)) }
                }
            }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(FlymeAutoStyle.CARD_PADDING_DP) })
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun clock() = LinearLayout(this).apply {
        orientation = if (resources.configuration.screenWidthDp < 840) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(TextClock(context).apply {
            setTextColor(palette.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
        })
        addView(TextClock(context).apply {
            val datePattern = android.text.format.DateFormat.getBestDateTimePattern(resources.configuration.locales[0], "EEEEdMMMM")
            format12Hour = datePattern
            format24Hour = datePattern
            setTextColor(palette.secondaryText)
            FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.BODY)
            setPadding(if (resources.configuration.screenWidthDp < 840) 0 else dp(FlymeAutoStyle.BLOCK_GAP_DP), 0, 0, dp(FlymeAutoStyle.BUTTON_PADDING_VERTICAL_DP))
        })
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        setTextColor(palette.primaryText)
        FlymeAutoStyle.text(this, if (size >= FlymeAutoStyle.PAGE_TITLE_SP)
            FlymeAutoStyle.TextRole.PAGE_TITLE else FlymeAutoStyle.TextRole.BODY)
    }

    private fun pill(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(palette.primaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        FlymeAutoStyle.button(this, palette, compact = true)
        minimumWidth = 0
        minWidth = 0
        setOnClickListener { onClick() }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(FlymeAutoStyle.CONTROL_GAP_DP) }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val BYD_HOME_PACKAGE = "com.android.launcher3"
        const val BYD_HOME_ACTIVITY = "com.android.launcher3.home.MainActivity"
    }
}

/** Keeps its child at a fixed width:height ratio, as wide as it may be. */
class AspectFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = MeasureSpec.getSize(widthMeasureSpec)
        var height = (width / ratio).toInt()
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && height > maxHeight) {
            height = maxHeight
            width = (height * ratio).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}

/** Every app with a launcher entry, sorted by name. */
private class AppsAdapter(private val context: Context) : BaseAdapter() {
    private data class App(val label: String, val packageName: String, val activity: String, val icon: Drawable)

    private var apps = emptyList<App>()

    fun load() {
        Thread {
            val pm = context.packageManager
            val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name, it.loadIcon(pm)) }
                .sortedBy { it.label.lowercase() }
            (context as Activity).runOnUiThread {
                apps = found
                notifyDataSetChanged()
            }
        }.start()
    }

    fun launchIntent(position: Int): Intent = apps[position].let { app ->
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    override fun getCount() = apps.size
    override fun getItem(position: Int): Any = apps[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val density = context.resources.displayMetrics.density
        val cell = convertView as? LinearLayout ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context), LinearLayout.LayoutParams((72 * density).toInt(), (72 * density).toInt()))
            addView(TextView(context).apply {
                setTextColor(FlymeSampleStyle.palette(context).primaryText)
                FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.SECONDARY)
                gravity = Gravity.CENTER
                maxLines = 2
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * density).toInt() })
        }
        val app = apps[position]
        (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
        (cell.getChildAt(1) as TextView).text = app.label
        return cell
    }
}
