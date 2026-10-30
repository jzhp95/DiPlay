package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoTouchTimelineTest {
    @Test fun acceptedEnqueueDoesNotClaimPhoneAcknowledgementOrDisplayLatency() {
        val recorder=VideoTouchTimeline()
        val id=recorder.received(2,1,10,13,100)
        recorder.enqueued(id,true,200)
        val evidence=JSONObject().put("touchTimeline",recorder.drain()).put("touchTraceEvictions",0)
        val result=VideoTouchTimeline.window(JSONObject(),evidence,100,300)
        assertEquals(1,result.getInt("acceptedByLocalQueue"))
        assertEquals(3.0,result.getJSONObject("dispatchLagMillis").getDouble("mean"),0.001)
        assertTrue(result.isNull("touchToPhotonMs"));assertEquals("unavailable",result.getString("causalFrameAssociation"))
        assertEquals(0,recorder.drain().length())
    }
    @Test fun overflowIsBoundedAndEventsOutsideWindowAreExcluded() {
        val recorder=VideoTouchTimeline(2)
        recorder.received(0,1,0,0,10);recorder.received(2,1,0,0,20);recorder.received(1,1,0,0,30)
        val last=JSONObject().put("touchTimeline",recorder.drain()).put("touchTraceEvictions",recorder.evictions())
        val result=VideoTouchTimeline.window(JSONObject(),last,21,40)
        assertEquals(1,result.getJSONArray("events").length());assertEquals(1,result.getInt("evictedDuringWindow"))
        assertEquals(0,result.getInt("acceptedByLocalQueue"))
    }
    @Test fun missingTraceIsUnavailableRatherThanZeroLatency() {
        val result=VideoTouchTimeline.window(JSONObject(),JSONObject(),0,1)
        assertFalse(result.getBoolean("available"))
        assertEquals(0,result.getJSONObject("localEnqueueReturnMillis").getInt("samples"))
    }
}
