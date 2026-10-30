package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

/** The overview no longer changes the provisioned authentication backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
class MfiTargetSettingsTest {
    private lateinit var controller: ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity
    private lateinit var page: LinearLayout

    @Before fun setUp() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        BackgroundSessionFixture.clear()
        controller.setup().visible()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        BackgroundSessionFixture.clear()
    }

    @Test fun overviewKeepsExistingAuthenticationChoiceWithoutOfferingTheRetiredSelector() {
        for(target in listOf(MfiTarget.USB_CH341,MfiTarget.LOCAL,MfiTarget.AUTO,MfiTarget.REMOTE)){
            AirPlayPersistence.saveMfiTarget(activity,target)
            renderConnection()
            assertTrue(views(page).filterIsInstance<Button>().none {
                it.text.startsWith(activity.getString(R.string.mfi_certificate_signing_target))
            })
            assertEquals(target,AirPlayPersistence.loadMfiTarget(activity))
            assertTrue(!CarPlayBackgroundSession.hasSession())
        }
    }

    private fun renderConnection() {
        ReflectionHelpers.setField(activity, "page", "settings")
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.CONNECTION)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        page = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll").getChildAt(0) as LinearLayout
    }

    private fun targetButton() = views(page).filterIsInstance<Button>().single {
        it.text.startsWith(activity.getString(R.string.mfi_certificate_signing_target) + " · ")
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
