package com.shilapi.xcertplay.diagnostics

internal data class DiagnosticStoreOperation(val runId: String, val revision: Long, val operationId: String)

/** Payload is a string so the writer and state threads never share mutable bytes or JSON. */
internal sealed class DiagnosticStoreResult {
    abstract val operation: DiagnosticStoreOperation
    abstract val elapsedMs: Long
    data class Success(override val operation: DiagnosticStoreOperation, override val elapsedMs: Long,
        val payload: String?) : DiagnosticStoreResult()
    data class Failure(override val operation: DiagnosticStoreOperation, override val elapsedMs: Long,
        val reason: String) : DiagnosticStoreResult()
    data class Stale(override val operation: DiagnosticStoreOperation, override val elapsedMs: Long) : DiagnosticStoreResult()
}
