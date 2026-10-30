package com.shilapi.xcertplay

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.network.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The retired BYD ADB switch is replaced by the current Flyme managed-hotspot preference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class CarHotspotSwitchTest {
    @Test fun bootSwitchPersistsWithoutApplyingOrStartingHotspot() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        CarHotspotSettings.setEnabled(activity,false)
        var operations=0
        val fake=object:CarHotspotOperations {
            override fun read():CarHotspotProfile?=null
            override fun apply(profile:CarHotspotProfile,cancelled:()->Boolean):CarHotspotControlResult {operations++;error("toggle must not apply")}
            override fun enable(cancelled:()->Boolean):CarHotspotControlResult {operations++;error("toggle must not start")}
        }
        val ui=CarHotspotSettingsUi(activity,{},controller=fake)
        val root=LinearLayout(activity)
        try {
            ui.build(root)
            val toggle=views(root).filterIsInstance<Switch>().single()
            assertFalse(toggle.isChecked)
            toggle.performClick();assertTrue(CarHotspotSettings.enabled(activity))
            toggle.performClick();assertFalse(CarHotspotSettings.enabled(activity))
            assertEquals(0,operations)
        } finally{ui.close();activity.finish()}
    }
    private fun views(view:View):Sequence<View> = sequence {
        yield(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(views(view.getChildAt(i)))
    }
}
