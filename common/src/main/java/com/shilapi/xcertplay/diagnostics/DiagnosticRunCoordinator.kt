package com.shilapi.xcertplay.diagnostics

import org.json.JSONObject
import java.util.UUID

/** State-thread owner. I/O owns only serialized snapshots. The writer outlives Service recreation. */
internal class DiagnosticRunCoordinator(
    private val store: DiagnosticRunStore,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
) {
    var run = JSONObject(); private set
    var recovering = false; private set
    var unavailable = false; private set
    var onStorageFailure: ((String) -> Unit)? = null
    private var loaded = false
    private var revision = 0L
    private var pending: String? = null
    private var cancelTimer: (() -> Unit)? = null
    private val recoveryWaiters = mutableListOf<() -> Unit>()
    @Volatile private var published = "{}"

    fun snapshot(): JSONObject = JSONObject(published)
    fun publishMemory() { published = run.toString() }

    fun recover(done: () -> Unit = {}) {
        if (loaded || unavailable) { done(); return }
        recoveryWaiters.add(done)
        if (recovering) return
        recovering = true
        perform("recover", 15_000, { _, callback -> store.loadLatest(UUID.randomUUID().toString(), callback) }) { payload ->
            if (!payload.isNullOrEmpty()) adopt(JSONObject(payload))
            loaded = true; recovering = false
            val callbacks = recoveryWaiters.toList(); recoveryWaiters.clear()
            callbacks.forEach { it() }
        }
    }

    fun adopt(value: JSONObject) {
        cancelPending()
        run = JSONObject(value.toString())
        revision = maxOf(run.optLong("storageRevision", 0),run.optLong("storageRevisionFloor",0))
        run.remove("storageRevisionFloor")
        store.beginRun(run.getString("id"))
        loaded = true; publishMemory()
    }

    fun checkpoint(done: () -> Unit = {}) {
        if (unavailable) return
        check(!recovering)
        revision++
        run.put("storageRevision", revision).put("architectureSchemaVersion", 1)
        publishMemory()
        val bytes = run.toString().toByteArray()
        perform("checkpoint", 5_000, { op, callback -> store.checkpoint(op, bytes, callback) }) { done() }
    }

    fun read(relative: String, done: (JSONObject) -> Unit) {
        perform("read", 15_000, { op, callback -> store.readDocument(op, relative, callback) }) {
            done(if (it == null) JSONObject() else JSONObject(it))
        }
    }

    fun mirrorDevice(context: android.content.Context, device: JSONObject, done: () -> Unit) {
        val payload = device.toString()
        perform("mirror_inventory", 5_000, { op, callback -> store.mirrorDevice(op, context, payload, callback) }) { done() }
    }

    fun seal(done: () -> Unit) {
        perform("seal", 5_000, { op, callback -> store.sealSnapshot(op, callback) }) {
            // Compatibility filename consumed by the existing independent publication worker.
            write("sealed-run.json", checkNotNull(it), done)
        }
    }

    fun write(relative: String, payload: String, done: () -> Unit) {
        perform("write", 5_000, { op, callback -> store.writeDocument(op, relative, payload, callback) }) { done() }
    }

    fun artifacts(values:Map<String,String>,done:()->Unit) {
        val remaining=values.entries.toList()
        fun next(index:Int) {
            if(index==remaining.size){done();return}
            val entry=remaining[index]
            perform("artifact",5_000,{op,callback->store.writeArtifact(op,entry.key,entry.value,callback)}){next(index+1)}
        }
        next(0)
    }

    fun cancelPending() {
        pending = null; cancelTimer?.invoke(); cancelTimer = null
        store.invalidate()
        if (run.has("id") && !unavailable) store.beginRun(run.getString("id"))
    }

    private fun perform(name: String, budget: Long,
        start: (DiagnosticStoreOperation, (DiagnosticStoreResult) -> Unit) -> Unit,
        done: (String?) -> Unit) {
        if (unavailable) return
        check(pending == null) { "diagnostic_store_operation_already_pending" }
        val token = UUID.randomUUID().toString()
        val runId = run.optString("id")
        val expectedRevision = revision
        val sessionIdentity=com.shilapi.xcertplay.DiPlaySessionService.liveRuntime?.snapshot()?.identity
        pending = token
        cancelTimer = schedule(budget) { if (pending == token) storageFailed("${name}_deadline") }
        start(DiagnosticStoreOperation(runId, expectedRevision, token)) callback@ { result ->
            if (pending != token || run.optString("id") != runId || revision != expectedRevision) return@callback
            pending = null; cancelTimer?.invoke(); cancelTimer = null
            RuntimeEvidence.storage.record(sessionIdentity?.id,sessionIdentity?.generation,"storage_operation",mapOf("operation" to name,"operationId" to token,"revision" to expectedRevision,
                "elapsedMs" to result.elapsedMs,"result" to result.javaClass.simpleName),runId.takeIf{it.isNotBlank()})
            when (result) {
                is DiagnosticStoreResult.Success -> done(result.payload)
                is DiagnosticStoreResult.Failure -> storageFailed("$name:${result.reason}")
                is DiagnosticStoreResult.Stale -> storageFailed("${name}_stale")
            }
        }
    }

    fun markStorageUnavailable(reason: String) { if (!unavailable) storageFailed(reason) }

    private fun storageFailed(reason: String) {
        pending = null; cancelTimer?.invoke(); cancelTimer = null
        unavailable = true; recovering = false; loaded = true
        store.invalidate()
        run.put("status", "storage_unavailable").put("execution", "interrupted")
            .put("publication", "failed").put("publicationError", "report_not_saved:$reason").put("timeoutStage", reason)
        publishMemory()
        recoveryWaiters.clear()
        onStorageFailure?.invoke(reason)
    }
}
