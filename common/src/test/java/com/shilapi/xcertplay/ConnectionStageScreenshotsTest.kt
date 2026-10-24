package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionStageScreenshotsTest {
    @Test fun currentPhaseFitsTheActualWaitingScreenInDayNightCompactFullAndShortViews() {
        for ((width, height, rtl) in listOf(Triple(400, 800, false), Triple(1400, 900, false),
            Triple(900, 400, false), Triple(400, 800, true))) for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("${if (rtl) "ar-rSA-ldrtl" else "zh-rCN"}-w${width}dp-h${height}dp-${if (night) "night" else "notnight"}-mdpi")
            RuntimeEnvironment.setFontScale(1.5f)
            CarPlayBackgroundSession.clear()
            val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
            try {
                ReflectionHelpers.setField(activity, "appNight", night)
                ReflectionHelpers.setField(activity, "wirelessEnabled", true)
                val root = ReflectionHelpers.callInstanceMethod<ViewGroup>(activity, "buildContentView")
                val reporter = ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity,
                    "createStatusReporter", ClassParameter.from(Int::class.javaPrimitiveType,
                        ReflectionHelpers.getField<Int>(activity, "restartGeneration")))
                reporter(CarPlayStatus.OpeningDataPaths)
                repeat(3) {
                    forceLayout(root)
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                }
                val phase = ReflectionHelpers.getField<TextView>(activity, "connectionStageView")
                assertEquals(activity.getString(R.string.connection_stage_current,
                    activity.getString(R.string.opening_usb_data_paths)), phase.text.toString())
                assertEquals(View.VISIBLE, phase.visibility)
                assertTrue("phase text clipped", phase.layout.height <= phase.height - phase.compoundPaddingTop - phase.compoundPaddingBottom)
                for (line in 0 until phase.layout.lineCount) assertEquals(0, phase.layout.getEllipsisCount(line))
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image))
                val folder = File("build/reports/connection-stage-ui").apply { mkdirs() }
                File(folder, "stage-${width}x$height-${if (rtl) "rtl" else "ltr"}-${if (night) "night" else "day"}.png")
                    .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            } finally {
                ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
                ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
                ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
                ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
                CarPlayBackgroundSession.clear()
            }
        }
    }
    /** Run complete traversals after the host adjusts text sizes in its layout listener. */
    private fun forceLayout(view: View) {
        view.forceLayout()
        if (view is ViewGroup) for (index in 0 until view.childCount) forceLayout(view.getChildAt(index))
    }
}
