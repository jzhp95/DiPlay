package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject

/** Main-thread diagnostic recorder; acceptance means local enqueue, never phone acknowledgement. */
internal class VideoTouchTimeline(private val capacity: Int = 1024) {
    private val rows = ArrayDeque<JSONObject>()
    private var sequence = 0L
    private var evicted = 0L
    init { require(capacity > 0) }
    fun received(action: Int, pointers: Int, eventUptimeMs: Long, receiveUptimeMs: Long, nowNs: Long): Long {
        val id = ++sequence
        if (rows.size == capacity) { rows.removeFirst(); evicted++ }
        rows.addLast(JSONObject().put("id", id).put("action", action).put("pointers", pointers)
            .put("receivedNs", nowNs).put("androidEventUptimeMs", eventUptimeMs)
            .put("dispatchLagMs", (receiveUptimeMs - eventUptimeMs).coerceAtLeast(0))
            .put("enqueueReturnedNs", JSONObject.NULL).put("enqueueAccepted", JSONObject.NULL))
        return id
    }
    fun enqueued(id: Long, accepted: Boolean, nowNs: Long) {
        rows.lastOrNull { it.optLong("id") == id }?.apply {
            put("enqueueReturnedNs", nowNs); put("enqueueAccepted", accepted)
        }
    }
    fun snapshot(): JSONArray = JSONArray().also { out ->
        rows.forEach { out.put(JSONObject(it.toString())) }
    }
    fun drain(): JSONArray = snapshot().also { rows.clear() }
    fun evictions(): Long = evicted
    companion object {
        fun window(first: JSONObject, last: JSONObject, startNs: Long, endNs: Long,
            events: JSONArray? = last.optJSONArray("touchTimeline")): JSONObject {
            val source = events
            val selected = JSONArray()
            if (source != null) for (i in 0 until source.length()) {
                val row = source.optJSONObject(i) ?: continue
                if (row.optLong("receivedNs", -1) in startNs..endNs) selected.put(JSONObject(row.toString()))
            }
            val dispatch = ArrayList<Double>(); val enqueue = ArrayList<Double>()
            var accepted = 0; var rejected = 0
            for (i in 0 until selected.length()) {
                val row = selected.getJSONObject(i)
                dispatch.add(row.optDouble("dispatchLagMs"))
                if (!row.isNull("enqueueReturnedNs")) {
                    val duration = row.getLong("enqueueReturnedNs") - row.getLong("receivedNs")
                    if (duration >= 0) enqueue.add(duration / 1e6)
                }
                if (!row.isNull("enqueueAccepted")) { if (row.getBoolean("enqueueAccepted")) accepted++ else rejected++ }
            }
            return JSONObject().put("available", source != null).put("events", selected)
                .put("dispatchLagMillis", DecoderDiagnosticsMetrics.distribution(dispatch))
                .put("localEnqueueReturnMillis", DecoderDiagnosticsMetrics.distribution(enqueue))
                .put("acceptedByLocalQueue", accepted).put("rejectedByLocalQueue", rejected)
                .put("evictedDuringWindow", if (source != null)
                    (last.optLong("touchTraceEvictions") - first.optLong("touchTraceEvictions")).coerceAtLeast(0) else JSONObject.NULL)
                .put("clock", "receivedNs/enqueueReturnedNs=System.nanoTime; androidEventUptimeMs=uptimeMillis")
                .put("scope", "local_input_dispatch_and_enqueue_return; no_wire_send_or_phone_ack")
                .put("touchToPhotonMs", JSONObject.NULL).put("causalFrameAssociation", "unavailable")
        }
    }
}
