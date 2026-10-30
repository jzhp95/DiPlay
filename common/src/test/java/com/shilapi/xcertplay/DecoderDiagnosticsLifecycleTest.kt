package com.shilapi.xcertplay

import android.content.Intent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DecoderDiagnosticsLifecycleTest {
    @org.junit.Before fun isolatedDiagnosticStore() {
        DiagnosticStoreFixture.reset()
        DiagnosticStoreFixture.recover(RuntimeEnvironment.getApplication())
    }
    @org.junit.After fun closeDiagnosticStore() { DiagnosticStoreFixture.reset() }

    @Test fun destroyedServiceCannotContinueALateBackgroundOperation() {
        val controller=Robolectric.buildService(DecoderDiagnosticsService::class.java).create()
        val service=controller.get()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val thread=java.util.concurrent.atomic.AtomicReference<Thread>()
        var advanced=false
        val work:()->JSONObject={
            thread.set(Thread.currentThread());entered.countDown()
            check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));JSONObject()
        }
        val done:(JSONObject)->Unit={advanced=true}
        ReflectionHelpers.callInstanceMethod<Void>(service,"background",
            ReflectionHelpers.ClassParameter.from(String::class.java,"controlled"),
            ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType,5000L),
            ReflectionHelpers.ClassParameter.from(Function0::class.java,work),
            ReflectionHelpers.ClassParameter.from(Function1::class.java,done))
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            controller.destroy();release.countDown();thread.get().join(5000)
            assertFalse(thread.get().isAlive)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(advanced)
        } finally {release.countDown()}
    }
    @Test fun originalPictureSettingsAreRecordedWithoutChangingThem() {
        val controller=Robolectric.buildService(DecoderDiagnosticsService::class.java).create();val service=controller.get()
        val prefs=CarPlayPicture.preferences(service)
        prefs.edit().putInt(CarPlayPicture.BRIGHTNESS,20).commit()
        val snapshot=ReflectionHelpers.callInstanceMethod<JSONObject>(service,"settings")
        assertEquals(20,snapshot.getJSONObject("picture").getInt(CarPlayPicture.BRIGHTNESS))
        assertEquals(20,prefs.getInt(CarPlayPicture.BRIGHTNESS,0));controller.destroy()
    }
    @Test fun privateCopyIsNotPresentedAsPublicDelivery() {
        val c=RuntimeEnvironment.getApplication();val r=DecoderDiagnosticsRun.create(c,JSONObject())
        r.put("status","interrupted").put("publication","private_only").put("publicPath","/private/result.zip")
        DecoderDiagnosticsRun.save(c,r)
        val text=DecoderDiagnosticsRun.describe(r,false)
        assertTrue(text.contains("仅应用内副本"));assertTrue(text.contains("/private/result.zip"));assertTrue(text.contains("尚未保存"))
        assertFalse(text.contains("已保存并校验"))
    }
    @Test fun staleRunIsRecoverableInsteadOfLookingFinished() {
        val c=RuntimeEnvironment.getApplication();val r=DecoderDiagnosticsRun.create(c,JSONObject())
        assertEquals("preflight",r.getString("status"))
        assertTrue(DecoderDiagnosticsRun.describe(r,false).contains("中断"))
    }
    @Test fun mismatchedAndLateWorkerMessagesCannotAdvanceRun() {
        val controller=Robolectric.buildService(DecoderDiagnosticsService::class.java).create()
        val service=controller.get()
        val r=DecoderDiagnosticsRun.create(service,JSONObject())
        adoptAfterRecovery(service,r)
        ReflectionHelpers.setField(service,"request","current")
        val reply=ReflectionHelpers.getField<android.os.Messenger>(service,"reply")
        reply.send(android.os.Message.obtain(null,DecoderDiagnosticsWorkerService.RESULT).apply{data=android.os.Bundle().apply{
            putString("run",r.getString("id"));putString("request","expired");putString("result","{\"cleanupConfirmed\":true}")
        }})
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("current",ReflectionHelpers.getField<String>(service,"request"))
        assertEquals(0,r.getInt("position"));controller.destroy()
    }
    @Test fun publicationFailureImmediatelyUpdatesMemoryAndKeepsRawDirectoryForRetry() {
        val controller=Robolectric.buildService(DecoderDiagnosticsService::class.java).create();val service=controller.get()
        val r=DecoderDiagnosticsRun.create(service,JSONObject())
        adoptAfterRecovery(service,r)
        ReflectionHelpers.callInstanceMethod<Void>(service,"publicationFailed",ReflectionHelpers.ClassParameter.from(String::class.java,"download_write_deadline"))
        val saved=DecoderDiagnosticsRun.coordinator(service).snapshot()
        assertEquals("failed",saved.getString("publication"));assertTrue(saved.getString("publicationError").contains("deadline"))
        assertTrue(DecoderDiagnosticsRun.directory(service,r.getString("id")).isDirectory)
        controller.destroy()
    }
    private fun adoptAfterRecovery(service:DecoderDiagnosticsService,run:JSONObject){
        val coordinator=DecoderDiagnosticsRun.coordinator(service)
        var recovered=false
        coordinator.recover{recovered=true}
        val deadline=System.nanoTime()+5_000_000_000L
        while(!recovered && System.nanoTime()<deadline){
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("recovery must finish before adopting a new run",recovered)
        assertFalse(coordinator.recovering)
        coordinator.adopt(run)
    }

}
