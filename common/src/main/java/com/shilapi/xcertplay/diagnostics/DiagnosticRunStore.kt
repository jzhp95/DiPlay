package com.shilapi.xcertplay.diagnostics

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One writer for this store's lifetime. Closing never waits for a blocked filesystem. */
internal class DiagnosticRunStore(
    private val root: File,
    private val dispatch: (() -> Unit) -> Unit,
    private val files: FileAccess = DiskFileAccess,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    interface FileAccess {
        fun read(file: File): ByteArray
        fun list(directory: File): List<File>
        fun exists(file: File): Boolean
        fun mkdirs(directory: File)
        fun write(file: File, bytes: ByteArray)
        fun sync(file: File)
        fun rename(from: File, to: File)
    }
    object DiskFileAccess : FileAccess {
        override fun read(file: File) = file.readBytes()
        override fun list(directory: File) = directory.listFiles()?.toList().orEmpty()
        override fun exists(file: File) = file.isFile
        override fun mkdirs(directory: File) { check(directory.isDirectory || directory.mkdirs()) }
        override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
        override fun sync(file: File) { java.io.RandomAccessFile(file, "rw").use { it.fd.sync() } }
        override fun rename(from: File, to: File) { check(from.renameTo(to)) { "rename_failed" } }
    }
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(32), { task -> Thread(task, "diagnostic-store").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val lock = Any()
    private var epoch = 0L
    private var closed = false
    private var activeRun: String? = null
    private val highestRevisions = mutableMapOf<String, Long>() // writer thread only

    fun beginRun(runId: String) {
        require(validRun(runId))
        synchronized(lock) { check(!closed); activeRun = runId; epoch++ }
    }

    /** Invalidates queued and already-dispatched results without interrupting fd.sync. */
    fun invalidate() { synchronized(lock) { epoch++; activeRun = null } }

    fun checkpoint(operation: DiagnosticStoreOperation, bytes: ByteArray, done: (DiagnosticStoreResult) -> Unit) {
        val payload = bytes.toString(Charsets.UTF_8)
        submit(operation, done) { current ->
            val value = JSONObject(payload)
            require(value.getString("id") == operation.runId) { "payload_run_mismatch" }
            require(!value.has("storageRevision") || value.getLong("storageRevision") == operation.revision) { "payload_revision_mismatch" }
            val directory = directory(operation.runId)
            val target = File(directory, "revisions/${operation.revision}.json")
            check(!files.exists(target)) { "revision_already_exists" }
            val envelope = JSONObject().put("runId", operation.runId).put("revision", operation.revision)
                .put("sha256", sha(payload)).put("payload", payload)
            atomic(target, envelope.toString().toByteArray())
            // A stale operation may leave its own revision, but cannot publish a current pointer.
            if (current() && operation.revision > (highestRevisions[operation.runId] ?: -1L)) {
                atomic(File(root, "store-latest.json"), JSONObject().put("runId", operation.runId)
                    .put("revision", operation.revision).toString().toByteArray(), current)
                highestRevisions[operation.runId] = operation.revision
            }
            payload
        }
    }

    fun loadLatest(operationId: String, done: (DiagnosticStoreResult) -> Unit) {
        submit(DiagnosticStoreOperation("", 0, operationId), done, requireRun = false) {
            val pointer = File(root, "store-latest.json")
            if (!files.exists(pointer)) {
                val legacy = File(root, "latest.json")
                if (!files.exists(legacy)) null else {
                    val id = JSONObject(files.read(legacy).toString(Charsets.UTF_8)).getString("id")
                    val payload = files.read(File(directory(id), "run.json")).toString(Charsets.UTF_8)
                    require(JSONObject(payload).getString("id") == id) { "legacy_identity_mismatch" }
                    recovered(payload, id)
                }
            } else {
                val index = JSONObject(files.read(pointer).toString(Charsets.UTF_8))
                val id=index.getString("runId")
                recovered(readRevision(id,index.getLong("revision")),id)
            }
        }
    }

    private fun recovered(payload: String, id: String): String {
        // A crash can leave an immutable revision ahead of the committed pointer. Never reuse it.
        val floor=files.list(File(directory(id),"revisions")).mapNotNull { file ->
            file.name.takeIf { it.matches(Regex("[0-9]+\\.json")) }?.removeSuffix(".json")?.toLongOrNull()
        }.maxOrNull() ?: 0L
        return JSONObject(payload).put("storageRevisionFloor",floor).toString()
    }

    fun readCase(operation: DiagnosticStoreOperation, caseId: String, done: (DiagnosticStoreResult) -> Unit) {
        require(caseId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        submit(operation, done) {
            val path = File(directory(operation.runId), "cases/$caseId.json")
            if (files.exists(path)) files.read(path).toString(Charsets.UTF_8).also {
                require(JSONObject(it).getString("caseId") == caseId) { "case_identity_mismatch" }
            } else null
        }
    }

    /** Seal one validated revision; the coordinator publishes it only after acknowledging success. */
    fun sealSnapshot(operation: DiagnosticStoreOperation, done: (DiagnosticStoreResult) -> Unit) {
        submit(operation, done) {
            val payload = readRevision(operation.runId, operation.revision)
            // A late seal may leave its own immutable revision, never overwrite another seal.
            val target = File(directory(operation.runId), "sealed/${operation.revision}.json")
            if (files.exists(target)) check(files.read(target).toString(Charsets.UTF_8) == payload) { "seal_already_differs" }
            else atomic(target, payload.toByteArray())
            payload
        }
    }

    fun readDocument(operation: DiagnosticStoreOperation, relative: String, done: (DiagnosticStoreResult) -> Unit) {
        val path = document(operation.runId, relative)
        submit(operation, done) {
            if (files.exists(path)) files.read(path).toString(Charsets.UTF_8).also { JSONObject(it) } else null
        }
    }

    fun writeDocument(operation: DiagnosticStoreOperation, relative: String, payload: String,
        done: (DiagnosticStoreResult) -> Unit) {
        val path = document(operation.runId, relative)
        submit(operation, done) { current ->
            JSONObject(payload)
            atomic(path, payload.toByteArray(), current)
            payload
        }
    }

    fun writeArtifact(operation:DiagnosticStoreOperation,relative:String,payload:String,done:(DiagnosticStoreResult)->Unit) {
        require(payload.toByteArray().size<=256*1024){"artifact_budget_exceeded"}
        val path=document(operation.runId,relative)
        submit(operation,done){current->
            if(relative.endsWith(".jsonl"))payload.lineSequence().filter{it.isNotBlank()}.forEach{JSONObject(it)}else JSONObject(payload)
            atomic(path,payload.toByteArray(),current)
            null
        }
    }

    /** Keep the old manual diagnostics view in sync in the owning process, off its UI thread. */
    fun mirrorDevice(operation: DiagnosticStoreOperation, context: android.content.Context, payload: String,
        done: (DiagnosticStoreResult) -> Unit) {
        val app = context.applicationContext
        submit(operation, done) { current ->
            val device = JSONObject(payload).put("diagnosticRunId", operation.runId)
            if (current()) com.shilapi.xcertplay.VideoAdaptationStore.update(app) { it.put("device", device) }
            null
        }
    }

    private fun document(runId: String, relative: String): File {
        require(relative.matches(Regex("[A-Za-z0-9_/-]+\\.jsonl?")) &&
            relative.split('/').none { it.isEmpty() || it == "." || it == ".." })
        return File(directory(runId), relative)
    }

    private fun readRevision(runId: String, revision: Long): String {
        require(validRun(runId) && revision >= 0)
        val envelope = JSONObject(files.read(File(directory(runId), "revisions/$revision.json")).toString(Charsets.UTF_8))
        require(envelope.getString("runId") == runId && envelope.getLong("revision") == revision) { "revision_identity_mismatch" }
        val payload = envelope.getString("payload")
        require(sha(payload) == envelope.getString("sha256")) { "revision_digest_mismatch" }
        val value=JSONObject(payload)
        require(value.getString("id") == runId) { "payload_run_mismatch" }
        require(!value.has("storageRevision") || value.getLong("storageRevision") == revision) { "payload_revision_mismatch" }
        return payload
    }

    private fun submit(operation: DiagnosticStoreOperation, done: (DiagnosticStoreResult) -> Unit,
        requireRun: Boolean = true, work: (() -> Boolean) -> String?) {
        require(operation.operationId.isNotBlank())
        if (requireRun) require(validRun(operation.runId) && operation.revision >= 0)
        val started = nanoTime()
        val generation = synchronized(lock) { epoch }
        fun current() = synchronized(lock) {
            !closed && generation == epoch && (!requireRun || activeRun == operation.runId)
        }
        fun elapsed() = TimeUnit.NANOSECONDS.toMillis(nanoTime() - started).coerceAtLeast(0)
        fun deliver(result: DiagnosticStoreResult) { dispatch {
            done(if (current()) result else DiagnosticStoreResult.Stale(operation, elapsed()))
        } }
        if (!current()) { deliver(DiagnosticStoreResult.Stale(operation, elapsed())); return }
        try {
            executor.execute {
                if (!current()) { deliver(DiagnosticStoreResult.Stale(operation, elapsed())); return@execute }
                val result = try {
                    val payload = work(::current)
                    DiagnosticStoreResult.Success(operation, elapsed(), payload)
                }
                catch (error: Exception) { DiagnosticStoreResult.Failure(operation, elapsed(), error.javaClass.simpleName + ":" + error.message.orEmpty().take(160)) }
                deliver(result)
            }
        } catch (_: RejectedExecutionException) {
            deliver(DiagnosticStoreResult.Failure(operation, elapsed(), "storage_queue_full_or_closed"))
        }
    }
    private fun directory(runId: String): File { require(validRun(runId)); return File(root, runId) }
    private fun atomic(file: File, bytes: ByteArray, current: () -> Boolean = { true }) {
        files.mkdirs(checkNotNull(file.parentFile))
        val temporary = File(file.parentFile, file.name + ".writing")
        files.write(temporary, bytes); files.sync(temporary)
        if (current()) files.rename(temporary, file)
    }
    override fun close() {
        synchronized(lock) { closed = true; epoch++ }
        executor.shutdown() // queued operations report stale; an active write is allowed to return naturally
    }
    companion object {
        private fun validRun(id: String) = id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
        private fun sha(payload: String) = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
