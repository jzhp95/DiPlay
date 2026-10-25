package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One process-local lease. Persistent results survive death; a dead lease never reconnects by itself. */
internal object AutomaticVideoSuite {
    private const val PREFS = "automatic_video_suite_v1"
    private var lease: String? = null
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Synchronized fun read(c: Context): JSONObject =
        runCatching { JSONObject(prefs(c).getString("state", "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun save(c: Context, v: JSONObject) { prefs(c).edit().putString("state", v.toString()).apply() }
    @Synchronized fun active(c: Context): Boolean {
        val value = read(c)
        val status = value.optString("status")
        if (value.optString("id").isNotEmpty() && value.optString("id") != lease &&
            (status == "running" || value.optBoolean("cleanupPending"))) {
            value.put("status", "interrupted").put("reason", "process_restarted")
                .put("cleanupPending", false).put("cleanupConfirmed", false)
            save(c, value); return false
        }
        return status == "running"
    }
    @Synchronized fun begin(c: Context): String {
        check(!active(c) && !AutomaticVideoCleanup.blocked())
        val id = UUID.randomUUID().toString(); lease = id
        save(c, JSONObject().put("id", id).put("status", "running").put("index", 0)
            .put("startedAt", System.currentTimeMillis()).put("results", JSONArray())
            .put("total", ColorTestRun.cases.size).put("visualColorVerdict", "not_automatically_measured")
            .put("realMapMotion", "not_automatically_controlled_or_verified"))
        return id
    }
    @Synchronized fun record(c: Context, id: String, index: Int, evidence: JSONObject, verdict: String): Boolean {
        if (!active(c)) return false
        val value = read(c)
        if (id != lease || value.optInt("index", -1) != index) return false
        val results = value.getJSONArray("results")
        results.put(JSONObject().put("index", index).put("title", ColorTestRun.cases[index].title)
            .put("verdict", verdict).put("evidence", JSONObject(evidence.toString())).put("time", System.currentTimeMillis()))
        value.put("index", index + 1)
        if (index + 1 >= ColorTestRun.cases.size) value.put("status", "completed").put("endedAt", System.currentTimeMillis())
        save(c, value); return true
    }
    @Synchronized fun stop(c: Context, reason: String) {
        val value = read(c)
        if (value.optString("status") == "running") save(c, value.put("status", "interrupted")
            .put("reason", reason).put("endedAt", System.currentTimeMillis()))
    }
    @Synchronized fun ready(c: Context, id: String) {
        if (active(c) && read(c).optString("id") == id) {
            val value = read(c).put("ready", true); save(c, value)
        }
    }
    @Synchronized fun cleanup(c: Context, id: String, pending: Boolean, confirmed: Boolean) {
        val value = read(c)
        if (value.optString("id") == id) save(c, value.put("cleanupPending", pending).put("cleanupConfirmed", confirmed))
    }
    @Synchronized fun cleanupFailed(c: Context, id: String) {
        val value = read(c)
        if (value.optString("id") == id) save(c, value.put("cleanupPending", false).put("cleanupConfirmed", false)
            .put("status", "interrupted").put("reason", "decoder_cleanup_unconfirmed").put("endedAt", System.currentTimeMillis()))
    }
    @Synchronized fun exported(c: Context, id: String, path: String, public: Boolean) {
        val value = read(c)
        if (value.optString("id") == id) save(c, value.put("exportPath", path).put("publicDownloads", public))
    }
}
