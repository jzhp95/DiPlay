package com.shilapi.xcertplay.diagnostics

import org.json.JSONObject
import org.json.JSONArray
import java.util.ArrayDeque

/** Bounded immutable event payloads. Only explicitly allowed technical fields can enter the ledger. */
internal class RuntimeEvidenceLedger(private val capacity:Int=256) {
    private val events=ArrayDeque<String>()
    private var dropped=0L
    private val allowed=setOf("phase","status","planId","decoder","software","generation","output","fallbackReason","format","elapsedMs","revision","operation","result","pending","coalescedMoves","staleDropped","overflow","queueWaitNs","sendNs","oldestAgeNs","lockWaitNs","writeNs","writeAndFlushNs","commandNs","operationId","written","accepted","phoneAcknowledged","rejectReason")
    @Synchronized fun record(sessionId:String?,generation:Long?,event:String,
        fields:Map<String,Any?> = emptyMap(),runId:String? = null) {
        require(capacity>0)
        val row=JSONObject().put("event",event.take(80)).put("monotonicNs",System.nanoTime())
            .put("clock","System.nanoTime").put("runId",runId?:JSONObject.NULL).put("sessionId",sessionId?:JSONObject.NULL).put("generation",generation?:JSONObject.NULL)
        fields.filterKeys{it in allowed}.forEach{(key,value)->row.put(key,JSONObject.wrap(value)?:JSONObject.NULL)}
        if(events.size==capacity){events.removeFirst();dropped++}
        events.addLast(row.toString())
    }
    @Synchronized fun snapshot():Pair<String,Long> = events.joinToString("\n",postfix=if(events.isEmpty())""else"\n") to dropped
}
internal object RuntimeEvidence {
    val sessions=RuntimeEvidenceLedger()
    val video=RuntimeEvidenceLedger()
    val storage=RuntimeEvidenceLedger()
    fun capture(runId:String):Map<String,String> {
        val state=com.shilapi.xcertplay.DiPlaySessionService.liveRuntime?.snapshot()
        val connection=com.shilapi.xcertplay.DiPlaySessionService.liveRuntime?.currentConnection
        val plan=connection?.sink?.videoPipelinePlan
        val sessionEvents=sessions.snapshot();val videoEvents=video.snapshot();val storageEvents=storage.snapshot()
        val captured=System.nanoTime()
        fun identity(value:JSONObject)=value.put("capturedMonotonicNs",captured).put("clock","System.nanoTime").put("runId",runId).put("sessionId",state?.identity?.id?:JSONObject.NULL)
            .put("generation",state?.identity?.generation?:JSONObject.NULL).put("architectureSchemaVersion",1)
        val ownership=identity(JSONObject().put("phase",state?.phase?.name?:"unknown")
            .put("activeConnection",connection!=null).put("retirement",if(state?.phase==com.shilapi.xcertplay.session.RuntimePhase.BLOCKED_RELEASE)"retained"else"not_blocked")
            .put("sessionEventsDropped",sessionEvents.second).put("videoEventsDropped",videoEvents.second).put("storageEventsDropped",storageEvents.second))
        connection?.sink?.resourceOwnershipCounts()?.let{ownership.put("registryCounts",JSONObject(it))}
        ownership.put("registryCountsAreAtomicSnapshot",false)
        fun collected(jsonl:String)=jsonl.lineSequence().filter{it.isNotBlank()}
            .joinToString("\n",postfix=if(jsonl.isEmpty())""else"\n"){JSONObject(it).put("collectionRunId",runId).toString()}
        val request=identity(JSONObject(plan?.request?.let{mapOf("codec" to it.codec.name,"width" to it.width,"height" to it.height,
            "fps" to it.fps,"purpose" to it.purpose.name,"smooth" to it.smooth,"direct" to it.direct,
            "softwareDirect" to it.softwareDirect,"hardwareYuv" to it.hardwareYuv)}?:mapOf("availability" to "no_production_video_plan")))
        val gaps=identity(JSONObject().put("gaps",JSONArray().put("physical_touch_to_panel_latency_not_measured")
            .put("hardware_decoder_not_inferred_from_user_preferences").put("native_output_type_unknown_unless_reported")
            .put("registry_counts_do_not_prove_native_handle_release_or_include_all_mirrors")))
        return linkedMapOf("runtime/session-events.jsonl" to collected(sessionEvents.first),"runtime/resource-ownership.json" to ownership.toString(),
            "video/request.json" to request.toString(),"video/plan.json" to identity(JSONObject(plan?.report()?:mapOf("availability" to "unknown"))).toString(),
            "video/output-events.jsonl" to collected(videoEvents.first),"diagnostics/storage-events.jsonl" to collected(storageEvents.first),
            "validation/evidence-gaps.json" to gaps.toString())
    }
}
