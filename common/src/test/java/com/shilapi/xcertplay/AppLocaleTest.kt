package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.view.View
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class AppLocaleTest {
    @Test fun legacyLanguageAndSystemLocaleCannotOverrideChinese() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("app_language", "ar").commit()
        for (tag in listOf("en-US", "ar", "zh-TW", "ja-JP")) {
            val base = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            })
            val wrapped = AppLocale.wrap(base)
            assertEquals("zh", AppLocale.preference(wrapped))
            assertEquals(Locale.SIMPLIFIED_CHINESE, wrapped.resources.configuration.locales[0])
            assertEquals(View.LAYOUT_DIRECTION_LTR, wrapped.resources.configuration.layoutDirection)
        }
    }

    @Test fun changedActivityResourcesAreRestoredToChinese() {
        val app = RuntimeEnvironment.getApplication()
        val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("en-US"))
        })
        assertTrue(AppLocale.enforce(context))
        assertEquals(Locale.SIMPLIFIED_CHINESE, context.resources.configuration.locales[0])
        assertFalse(AppLocale.enforce(context))
    }
}
