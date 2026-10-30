package com.shilapi.xcertplay.transport

internal data class UsbMuxFrame(
    val protocol: Int,
    val length: Int,
    val word8: Int,
    val sequence: Int,
    val payload: ByteArray,
)

/** Incremental framing; callers serialize access with their USBMUX state lock. */
internal class UsbMuxFrameBuffer(private val diagnostic: (String) -> Unit = {}) {
    private var bytes = ByteArray(0)
    private var head = 0
    private var tail = 0
    private var optionalReplyPadding: UsbMuxFrame? = null
    private var paddingReports = 0
    private var lastUsbReadBytes = 0
    val bufferedBytes: Int get() = tail - head

    fun append(transfer: ByteArray) {
        lastUsbReadBytes = transfer.size
        if (transfer.isEmpty()) return
        val required = bufferedBytes.toLong() + transfer.size
        if (required > Int.MAX_VALUE) throw IphoneUsbException.Protocol("USBMUX receive buffer is too large")
        if (transfer.size > bytes.size - tail) {
            if (required <= bytes.size) {
                bytes.copyInto(bytes, 0, head, tail)
            } else {
                val capacity = maxOf(required, INITIAL_BUFFER_BYTES.toLong(), bytes.size.toLong() * 2)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val grown = ByteArray(capacity)
                bytes.copyInto(grown, 0, head, tail)
                bytes = grown
            }
            tail = bufferedBytes
            head = 0
        }
        transfer.copyInto(bytes, tail)
        tail += transfer.size
    }

    private fun discard(count: Int) {
        head += count
        if (head == tail) { head = 0; tail = 0 }
    }

    private fun readBufferedU32(offset: Int) = readU32(bytes, head + offset)
    private fun readBufferedU16(offset: Int) = readU16(bytes, head + offset)

    fun takeFrame(): UsbMuxFrame? {
        if (bufferedBytes < HEADER_BYTES) return null
        var length = readBufferedU32(4)
        if (length !in HEADER_BYTES..MAX_FRAME_BYTES) {
            val previous = optionalReplyPadding
            // The captured iOS 27 VERSION and valid RX TCP replies, including payload-bearing
            // replies, have four extra bytes. Do not scan or discard a USB completion:
            // only this single four-byte boundary is eligible, before validated TCP or the
            // captured protocol-1 diagnostic shape.
            // A normal or split next header at offset zero is always preserved.
            // Wait for the candidate MUX/TCP headers or complete diagnostic payload. A timeout
            // leaves these bytes in place; a legitimate split frame must never be lost.
            if (previous != null && readBufferedU32(0) != PROTOCOL_TCP &&
                bufferedBytes < PADDING_BYTES + HEADER_BYTES) return null
            val followingProtocol = readBufferedU32(PADDING_BYTES)
            val followingLength = readBufferedU32(PADDING_BYTES + 4)
            val candidateHeader = previous != null && readBufferedU32(0) != PROTOCOL_TCP &&
                readBufferedU32(PADDING_BYTES + 8) == CAPTURED_REPLY_MAGIC
            val validTarget = when {
                candidateHeader && followingProtocol == PROTOCOL_TCP &&
                    followingLength in (HEADER_BYTES + TCP_HEADER_BYTES)..MAX_FRAME_BYTES -> {
                    if (bufferedBytes < PADDING_BYTES + HEADER_BYTES + TCP_HEADER_BYTES) return null
                    val tcpHeaderBytes = ((bytes[head + PADDING_BYTES + HEADER_BYTES + 12].toInt() ushr 4) and 0x0f) * 4
                    tcpHeaderBytes in TCP_HEADER_BYTES..(followingLength - HEADER_BYTES) &&
                        readBufferedU16(PADDING_BYTES + HEADER_BYTES) != 0 &&
                        readBufferedU16(PADDING_BYTES + HEADER_BYTES + 2) != 0
                }
                candidateHeader && followingProtocol == PROTOCOL_DIAGNOSTIC &&
                    followingLength in (HEADER_BYTES + 2)..(HEADER_BYTES + MAX_DIAGNOSTIC_PAYLOAD_BYTES) -> {
                    // Issue #100 also captures four extra bytes before/after protocol 1.
                    // Validate its entire bounded subtype/text payload before recovering.
                    // A timeout leaves the trailer and incomplete frame untouched.
                    if (bufferedBytes < PADDING_BYTES + followingLength) return null
                    isCapturedDiagnosticPayload(bytes, head + PADDING_BYTES + HEADER_BYTES, followingLength - HEADER_BYTES)
                }
                // Issue #100 (0.2.19): a 24-byte transfer carried a VERSION reply and its own trailer
                // after the previous reply's trailer. Its whole header is fixed, so validate it exactly.
                previous != null && readBufferedU32(0) != PROTOCOL_TCP && followingProtocol == PROTOCOL_VERSION &&
                    followingLength == VERSION_BYTES && readBufferedU32(PADDING_BYTES + 8) == VERSION_REPLY_WORD -> true
                else -> false
            }
            if (previous != null && validTarget) {
                discard(PADDING_BYTES)
                optionalReplyPadding = null
                if (paddingReports++ < MAX_PADDING_REPORTS) report(
                    "USBMUX optional reply padding skipped bytes=$PADDING_BYTES " +
                        "previousProtocol=${previous.protocol} previousLength=${previous.length} " +
                        "nextProtocol=$followingProtocol nextLength=$followingLength " +
                        "lastUsbReadBytes=$lastUsbReadBytes bufferedBytes=${bufferedBytes}")
                if (bufferedBytes < HEADER_BYTES) return null
                length = readBufferedU32(4)
            } else {
                report("USBMUX framing rejected declaredLength=$length bufferedBytes=${bufferedBytes} " +
                    "lastUsbReadBytes=$lastUsbReadBytes optionalReplyPadding=${previous != null}")
                throw IphoneUsbException.Protocol("Invalid USBMUX frame length $length")
            }
        }
        if (bufferedBytes < length) return null
        val frame = UsbMuxFrame(
            protocol = readBufferedU32(0), length = length, word8 = readBufferedU32(8),
            sequence = ((bytes[head + 12].toInt() and 0xff) shl 8) or (bytes[head + 13].toInt() and 0xff),
            payload = bytes.copyOfRange(head + HEADER_BYTES, head + length),
        )
        discard(length)
        optionalReplyPadding = frame.takeIf(::canHaveOptionalReplyPadding)
        return frame
    }

    private fun canHaveOptionalReplyPadding(frame: UsbMuxFrame): Boolean {
        if (frame.protocol == PROTOCOL_VERSION && frame.length == VERSION_BYTES && frame.word8 == VERSION_REPLY_WORD) return true
        if (frame.protocol == PROTOCOL_DIAGNOSTIC && frame.word8 == CAPTURED_REPLY_MAGIC) {
            return isCapturedDiagnosticPayload(frame.payload, 0, frame.payload.size)
        }
        if (frame.protocol != PROTOCOL_TCP || frame.word8 != CAPTURED_REPLY_MAGIC ||
            frame.payload.size < TCP_HEADER_BYTES) return false
        val tcpHeaderBytes = ((frame.payload[12].toInt() ushr 4) and 0x0f) * 4
        return tcpHeaderBytes in TCP_HEADER_BYTES..frame.payload.size &&
            readU16(frame.payload, 0) != 0 && readU16(frame.payload, 2) != 0
    }

    /** Only the captured diagnostic subtype followed by bounded printable ASCII qualifies. */
    private fun isCapturedDiagnosticPayload(source: ByteArray, offset: Int, length: Int): Boolean =
        length in 2..MAX_DIAGNOSTIC_PAYLOAD_BYTES && source[offset].toInt() == DIAGNOSTIC_TEXT_SUBTYPE &&
            (offset + 1 until offset + length).all { source[it].toInt() in 0x20..0x7e }

    private fun report(line: String) { runCatching { diagnostic(line) } }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val INITIAL_BUFFER_BYTES = 4_096
        const val HEADER_BYTES = 16
        const val TCP_HEADER_BYTES = 20
        const val MAX_FRAME_BYTES = 65_536
        const val VERSION_BYTES = 20
        const val VERSION_REPLY_WORD = 2
        const val PADDING_BYTES = 4
        const val MAX_PADDING_REPORTS = 4
        const val PROTOCOL_VERSION = 0
        const val PROTOCOL_DIAGNOSTIC = 1
        const val PROTOCOL_TCP = 6
        const val DIAGNOSTIC_TEXT_SUBTYPE = 4
        const val MAX_DIAGNOSTIC_PAYLOAD_BYTES = 1_024
        val CAPTURED_REPLY_MAGIC = 0xfaceface.toInt()
    }
}
