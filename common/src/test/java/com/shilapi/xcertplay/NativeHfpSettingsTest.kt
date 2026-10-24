package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class NativeHfpSettingsTest {
    @Test fun nativePhonePolicyDefaultsOnAndDoesNotEnableBluetoothMusicOrOtherTuning() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(AirPlayPersistence.loadNativeHfpCalls(context))
        assertFalse(AirPlayPersistence.loadCarBluetoothAudio(context))
        AirPlayPersistence.saveNativeHfpCalls(context, false)
        assertFalse(AirPlayPersistence.loadNativeHfpCalls(context))
        assertFalse(AirPlayPersistence.loadCarBluetoothAudio(context))
        AirPlayPersistence.saveNativeHfpCalls(context, true)
        assertTrue(AirPlayPersistence.loadNativeHfpCalls(context))
    }

    @Test fun independentPhoneSwitchFitsCompactAndFullSettingsAtLargeFont() {
        for (width in listOf(400, 1400)) {
            RuntimeEnvironment.setQualifiers("zh-rCN-w${width}dp-h900dp-mdpi")
            RuntimeEnvironment.setFontScale(1.5f)
            val context = RuntimeEnvironment.getApplication()
            val owner = org.robolectric.Robolectric.buildActivity(DiPlayActivity::class.java,
                android.content.Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
            val activity = owner.get()
            try {
                org.robolectric.util.ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.ADVANCED)
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 900)
                fun all(v: android.view.View): List<android.view.View> = listOf(v) +
                    if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
                val title = all(root).filterIsInstance<android.widget.TextView>()
                    .single { it.text.toString() == activity.getString(com.shilapi.xcertplay.host.R.string.settings_native_hfp_calls) }
                val row = title.parent.parent as android.view.View
                val description = all(row).filterIsInstance<android.widget.TextView>()
                    .single { it.text.toString() == activity.getString(com.shilapi.xcertplay.host.R.string.settings_native_hfp_calls_description) }
                assertTrue(description.layout.height <= description.height - description.compoundPaddingTop - description.compoundPaddingBottom)
                val bitmap = android.graphics.Bitmap.createBitmap(row.width, row.height, android.graphics.Bitmap.Config.ARGB_8888)
                row.draw(android.graphics.Canvas(bitmap))
                val folder = java.io.File("build/reports/native-hfp-ui").apply { mkdirs() }
                java.io.File(folder, "hfp-$width.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            } finally { owner.pause().stop().destroy() }
        }
    }
}
