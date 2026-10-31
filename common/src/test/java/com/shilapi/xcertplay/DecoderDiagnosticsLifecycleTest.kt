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
    @Test fun caseErrorWithConfirmedCleanupRecordsGapAndAdvancesToNextCase() {
        val controller=Robolectric.buildService(DecoderDiagnosticsService::class.java).create()
        val service=controller.get()
        val r=DecoderDiagnosticsRun.create(service,JSONObject())
        val case1=JSONObject().put("caseId","case-1").put("asset","asset-1").put("warmupMs",100L).put("measureMs",100L)
        val case2=JSONObject().put("caseId","case-2").put("asset","asset-2").put("warmupMs",100L).put("measureMs",100L)
        r.put("plan",org.json.JSONArray().put(case1).put(case2)).put("position",0).put("status","standard")
        adoptAfterRecovery(service,r)
        val hashes=JSONObject().put("asset-1","hash-1").put("asset-2","hash-2")
        ReflectionHelpers.setField(service,"sourceHashes",hashes)

        val dispatched=mutableListOf<JSONObject>()
        val replyMessenger=ReflectionHelpers.getField<android.os.Messenger>(service,"reply")
        val workerHandler=android.os.Handler(android.os.Looper.getMainLooper()) { msg ->
            val data=msg.data
            val req=data.getString("request")?:""
            val runId=data.getString("run")?:""
            val spec=JSONObject(data.getString("spec")?:"{}")
            dispatched.add(spec)
            if (dispatched.size==1) {
                val result=JSONObject().put("error","codec_fault").put("cleanupConfirmed",true)
                replyMessenger.send(android.os.Message.obtain(null,DecoderDiagnosticsWorkerService.RESULT).apply {
                    this.data=android.os.Bundle().apply {
                        putString("run",runId);putString("request",req);putString("result",result.toString())
                    }
                })
            }
            true
        }
        ReflectionHelpers.setField(service,"worker",android.os.Messenger(workerHandler))
        ReflectionHelpers.setField(service,"workerPid",1234)
        ReflectionHelpers.setField(service,"bound",true)

        ReflectionHelpers.callInstanceMethod<Void>(service,"nextCase")
        val deadline=System.nanoTime()+5_000_000_000L
        while (dispatched.size<2 && System.nanoTime()<deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }

        assertEquals(2,dispatched.size)
        assertEquals("case-1",dispatched[0].getString("caseId"))
        assertEquals("case-2",dispatched[1].getString("caseId"))
        val snapshot=DecoderDiagnosticsRun.coordinator(service).snapshot()
        val gaps=snapshot.getJSONArray("gaps")
        assertTrue((0 until gaps.length()).any { gaps.getString(it).contains("case_error:case-1:codec_fault") })
        assertFalse(ReflectionHelpers.getField<Boolean>(service,"closing"))
        assertEquals(1,snapshot.getInt("position"))
        controller.destroy()
    }
    @Test fun caseErrorWithUnconfirmedCleanupAbortsRun() {
        val controller=Robolectric.buildService(DecoderDiagnosticsService::class.java).create()
        val service=controller.get()
        val r=DecoderDiagnosticsRun.create(service,JSONObject())
        val case1=JSONObject().put("caseId","case-1").put("asset","asset-1").put("warmupMs",100L).put("measureMs",100L)
        val case2=JSONObject().put("caseId","case-2").put("asset","asset-2").put("warmupMs",100L).put("measureMs",100L)
        r.put("plan",org.json.JSONArray().put(case1).put(case2)).put("position",0).put("status","standard")
        adoptAfterRecovery(service,r)
        val hashes=JSONObject().put("asset-1","hash-1").put("asset-2","hash-2")
        ReflectionHelpers.setField(service,"sourceHashes",hashes)

        val dispatched=mutableListOf<JSONObject>()
        val replyMessenger=ReflectionHelpers.getField<android.os.Messenger>(service,"reply")
        val workerHandler=android.os.Handler(android.os.Looper.getMainLooper()) { msg ->
            val data=msg.data
            val req=data.getString("request")?:""
            val runId=data.getString("run")?:""
            val spec=JSONObject(data.getString("spec")?:"{}")
            dispatched.add(spec)
            val result=JSONObject().put("error","codec_hang").put("cleanupConfirmed",false)
            replyMessenger.send(android.os.Message.obtain(null,DecoderDiagnosticsWorkerService.RESULT).apply {
                this.data=android.os.Bundle().apply {
                    putString("run",runId);putString("request",req);putString("result",result.toString())
                }
            })
            true
        }
        ReflectionHelpers.setField(service,"worker",android.os.Messenger(workerHandler))
        ReflectionHelpers.setField(service,"workerPid",1234)
        ReflectionHelpers.setField(service,"bound",true)

        ReflectionHelpers.callInstanceMethod<Void>(service,"nextCase")
        val deadline=System.nanoTime()+5_000_000_000L
        while (!ReflectionHelpers.getField<Boolean>(service,"closing") && System.nanoTime()<deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }

        assertEquals(1,dispatched.size)
        assertEquals("case-1",dispatched[0].getString("caseId"))
        assertTrue(ReflectionHelpers.getField<Boolean>(service,"closing"))
        val snapshot=DecoderDiagnosticsRun.coordinator(service).snapshot()
        assertEquals("codec_hang",snapshot.getString("reason"))
        controller.destroy()
    }
    @Test fun activityMismatchedTicketFinishesActivityAndPreservesCurrentTicket() {
        val reply=android.os.Messenger(android.os.Handler(android.os.Looper.getMainLooper()))
        val ticket=DecoderDiagnosticsWorkerService.Ticket("expected-req","run-1",JSONObject().put("caseId","c1"),reply)
        DecoderDiagnosticsWorkerService.ticket=ticket
        try {
            val intent=Intent(RuntimeEnvironment.getApplication(),DecoderDiagnosticsActivity::class.java).putExtra("request","wrong-req")
            val activityController=Robolectric.buildActivity(DecoderDiagnosticsActivity::class.java,intent).create()
            assertTrue(activityController.get().isFinishing)
            assertSame(ticket,DecoderDiagnosticsWorkerService.ticket)
        } finally {
            DecoderDiagnosticsWorkerService.ticket=null
        }
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
