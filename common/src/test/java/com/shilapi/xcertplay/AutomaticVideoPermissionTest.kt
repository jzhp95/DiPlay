package com.shilapi.xcertplay

import android.Manifest
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutomaticVideoPermissionTest {
    @Test fun oneClickRequestsPublicStorageAndDenialContinuesWithExplicitPrivateFallback() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("automatic_video_suite_v1", 0).edit().clear().commit()
        shadowOf(context).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val controller = Robolectric.buildActivity(WheelTestActivity::class.java,
            Intent().putExtra("diagnostics_page", "VIDEO")).setup()
        try {
            val activity = controller.get()
            val page = ReflectionHelpers.getField<VideoAdaptationPage>(activity, "videoPage")
            fun descendants(view: View): Sequence<View> = sequence {
                yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
            }
            descendants(page.root).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.video_auto_start) }.performClick()
            val request = shadowOf(activity).lastRequestedPermission
            assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), request.requestedPermissions.toList())
            assertFalse(AutomaticVideoSuite.active(activity))
            val original = ReflectionHelpers.getField<ColorTestPage>(activity, "colorPage")
            val fake = org.mockito.Mockito.mock(ColorTestPage::class.java)
            ReflectionHelpers.setField(activity, "colorPage", fake)
            try {
                activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(-1))
                org.mockito.Mockito.verify(fake).startAutomatic()
            } finally { ReflectionHelpers.setField(activity, "colorPage", original) }
        } finally { controller.pause().stop().destroy() }
    }
}
