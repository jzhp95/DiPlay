package com.shilapi.xcertplay

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class AutomaticVideoSuiteTest {
    private val c get()=RuntimeEnvironment.getApplication()
    private val prefs get()=c.getSharedPreferences("automatic_video_suite_v1",0)
    @Test fun unfinishedLegacyRunIsReadOnlyInterruptedAndKeepsEvidence() {
        val raw=JSONObject().put("id","old").put("status","running").put("cleanupPending",true)
            .put("index",17).put("results",JSONArray().put(JSONObject().put("index",16).put("evidence","kept"))).toString()
        prefs.edit().putString("state",raw).commit()
        assertFalse(AutomaticVideoSuite.active(c))
        val restored=AutomaticVideoSuite.read(c)
        assertEquals("interrupted_legacy",restored.getString("status"))
        assertFalse(restored.getBoolean("cleanupPending"))
        assertEquals("kept",restored.getJSONArray("results").getJSONObject(0).getString("evidence"))
        assertEquals(17,restored.getInt("index"))
        assertEquals(raw,prefs.getString("state",null))
    }
    @Test fun completedEvidenceRemainsReadableWithoutStartingAnything() {
        val raw="{\"id\":\"old\",\"status\":\"completed\",\"results\":[{\"index\":3}]}"
        prefs.edit().putString("state",raw).commit()
        assertEquals("completed",AutomaticVideoSuite.read(c).getString("status"))
        assertFalse(AutomaticVideoSuite.active(c))
        assertEquals(raw,prefs.getString("state",null))
    }
    @Test fun staleExportCannotAttachOldArchiveToAnotherLegacyRecord() {
        prefs.edit().putString("state","{\"id\":\"new\",\"status\":\"completed\"}").commit()
        AutomaticVideoSuite.exported(c,"old","/old.zip",true)
        assertFalse(AutomaticVideoSuite.read(c).has("exportPath"))
        AutomaticVideoSuite.exported(c,"new","/new.zip",true)
        assertEquals("/new.zip",AutomaticVideoSuite.read(c).getString("exportPath"))
    }
}
