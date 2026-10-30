package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One process-local lease. Persistent results survive death; a dead lease never reconnects by itself. */
internal object AutomaticVideoSuite {
    private const val PREFS = "automatic_video_suite_v1"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Synchronized fun read(c: Context): JSONObject {
        val value=runCatching{JSONObject(prefs(c).getString("state","{}")?:"{}")}.getOrDefault(JSONObject())
        if(value.optString("status")=="running" || value.optBoolean("cleanupPending")) {
            value.put("status","interrupted_legacy").put("reason","legacy_execution_retired")
                .put("cleanupPending",false).put("cleanupConfirmed",false)
        }
        return value // Preserve the original record verbatim on disk, including all evidence.
    }
    private fun save(c: Context, v: JSONObject) { prefs(c).edit().putString("state", v.toString()).apply() }
    /** Old runs are evidence only. Reading cannot start or advance a diagnostic. */
    @Synchronized fun active(c: Context): Boolean { read(c); return false }
    @Synchronized fun stop(c: Context, reason: String) { read(c) }
    @Synchronized fun exportFailed(c: Context, id: String, reason: String) {
        val value = read(c)
        if (value.optString("id") == id) save(c, value.put("exportError", reason))
    }
    @Synchronized fun exported(c: Context, id: String, path: String, public: Boolean) {
        val value = read(c)
        if (value.optString("id") == id) {
            value.remove("exportError")
            save(c, value.put("exportPath", path).put("publicDownloads", public))
        }
    }
}
