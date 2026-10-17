package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Detailed event recording independent of an Activity or a live CarPlay connection. */
internal class DetailedDebugRecorder(private val directory: File, private val process: String) : Closeable {
    private val diskLock = Any()
    private val target = SessionLogFile(File(directory, "diplay.log"))
    private val writer = BoundedDiagnosticWriter<String>(maxPending = 1024) { line ->
        synchronized(diskLock) {
            directory.mkdirs()
            target.append(line)
        }
    }
    @Volatile var recording = false
        private set
    private var sequence = 0L

    @Synchronized fun setEnabled(enabled: Boolean) {
        if (recording == enabled) return
        if (enabled) recording = true
        record("recorder", "recording ${if (enabled) "enabled" else "disabled"}")
        recording = enabled
    }

    @Synchronized fun record(category: String, message: String, nowMillis: Long = System.currentTimeMillis()) {
        if (!recording) return
        val safe = DiagnosticRedactor.redact(message) ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))
        writer.enqueue("$time epochMs=$nowMillis process=$process seq=${++sequence} [$category] ${safe.take(480)}")
    }

    /** Call off the main thread; report a timed-out flush rather than blocking export forever. */
    fun report(): String {
        val flushed = writer.awaitIdle(2_000)
        return synchronized(diskLock) {
            buildString {
                appendLine("Detailed debug recording=$recording writerFlushed=$flushed queueDropped=${writer.droppedCount}")
                for (name in SessionLogFile.REPORT_NAMES) {
                    val file = File(directory, name)
                    if (!file.isFile) continue
                    appendLine("--- detailed/$name ---")
                    runCatching {
                        file.useLines { lines -> lines.forEach { DiagnosticRedactor.redact(it)?.let(::appendLine) } }
                    }.onFailure { appendLine("read failed: ${it.javaClass.simpleName}") }
                }
            }
        }
    }

    // SessionLogFile holds no open stream. Pending writes may finish after close().
    override fun close() { recording = false; writer.close() }
}
