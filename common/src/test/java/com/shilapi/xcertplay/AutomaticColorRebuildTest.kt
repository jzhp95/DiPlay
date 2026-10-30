package com.shilapi.xcertplay

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AutomaticColorRebuildTest {
    @Test fun reconstructingPagesNeverResumesAnOldAutomaticRun() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val raw="{\"id\":\"old\",\"status\":\"running\",\"index\":16,\"ready\":true}"
        val prefs=activity.getSharedPreferences("automatic_video_suite_v1",0)
        prefs.edit().putString("state",raw).commit()
        repeat(2) {
            val page=ColorTestPage(activity,DiPlayPalette.LIGHT)
            try {
                page.setVisible(true)
                assertEquals(0,page.run.index)
                assertNull(shadowOf(activity).nextStartedActivity)
                assertFalse(AutomaticVideoSuite.active(activity))
                assertEquals(raw,prefs.getString("state",null))
            } finally {page.close()}
        }
    }
}
