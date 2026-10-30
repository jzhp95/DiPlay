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
    @org.junit.Before fun isolatedDiagnosticStore() {
        DiagnosticStoreFixture.reset()
        DiagnosticStoreFixture.recover(RuntimeEnvironment.getApplication())
    }
    @org.junit.After fun closeDiagnosticStore() { DiagnosticStoreFixture.reset() }

    @Test fun manualCollectorAlsoRequestsStorageBeforeLeavingTheMergedPage() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("automatic_video_suite_v1", 0).edit().clear().commit()
        shadowOf(context).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val session = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        val sink = com.shilapi.xcertplay.media.AndroidMediaSink()
        BackgroundSessionFixture.store(session, sink, 1920, 1080, Any(),
            CarPlaySessionDisplay(1920, 1080, 0, false, false, 1920, 1080)) {}
        val controller = Robolectric.buildActivity(WheelTestActivity::class.java,
            Intent().putExtra("diagnostics_page", "VIDEO")).setup()
        try {
            fun descendants(view: View): Sequence<View> = sequence {
                yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
            }
            val activity = controller.get()
            descendants(activity.window.decorView).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.video_adapt_start) }.performClick()
            val deadline=System.nanoTime()+5_000_000_000L
            while(shadowOf(activity).lastRequestedPermission==null && System.nanoTime()<deadline) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                if(shadowOf(activity).lastRequestedPermission==null)Thread.sleep(10)
            }
            assertNotNull("Storage permission must be requested after local decoder cleanup",shadowOf(activity).lastRequestedPermission)
            assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), shadowOf(activity).lastRequestedPermission.requestedPermissions.toList())
            assertNull(VideoAdaptationRegistry.current())
        } finally { BackgroundSessionFixture.clear(); sink.close(); controller.pause().stop().destroy() }
    }
    @Test fun republishRequestsPermissionWithoutRestartingMeasurements() {
        val context=RuntimeEnvironment.getApplication();shadowOf(context).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val controller=Robolectric.buildActivity(WheelTestActivity::class.java,Intent().putExtra("diagnostics_page","COLOR")).setup()
        try{
            val activity=controller.get()
            ReflectionHelpers.callInstanceMethod<Void>(activity,"requestDecoderRepublish")
            assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),shadowOf(activity).lastRequestedPermission.requestedPermissions.toList())
            assertEquals("republish",ReflectionHelpers.getField<String>(activity,"pendingStorageAction"))
        }finally{controller.pause().stop().destroy()}
    }
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
            ReflectionHelpers.callInstanceMethod<Unit>(activity,"requestAutomaticVideo")
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
