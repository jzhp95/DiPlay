package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec

/** Opt-in, bounded, memory-only receive tap. Nothing writes to disk on a protocol/decoder thread. */
class VideoDiagnosticCapture(val codec: VideoCodec, val csd: ByteArray,
    private val budget: Int = 8 * 1024 * 1024, private val maxDurationNs: Long = 10_000_000_000L) {
    data class AccessUnit(val bytes: ByteArray, val receivedNs: Long, val senderNs: Long, val arrivalNs: Long, val key: Boolean)
    private val frames = ArrayList<AccessUnit>()
    private var size = 0
    private var closed = false
    private var reason = "waiting_for_keyframe"
    private var changed = false
    @Synchronized fun configuration(next: VideoCodec, bytes: ByteArray) {
        if (next != codec || !bytes.contentEquals(csd)) { changed = true; closed = true; reason = "configuration_changed" }
    }
    @Synchronized fun receive(bytes: ByteArray, senderNs: Long = 0, arrivalNs: Long = 0, now: Long = System.nanoTime()) {
        if (closed) return
        val annex = MediaCodecSupport.toAnnexB(bytes)
        if (annex.isEmpty()) { closed = true; reason = "invalid_access_unit"; return }
        val key = MediaCodecSupport.isRandomAccess(annex, codec)
        if (frames.isEmpty() && !key) return
        // End immediately BEFORE a subsequent random access unit: all retained GOPs remain whole.
        if (frames.isNotEmpty() && key && (now - frames.first().receivedNs >= maxDurationNs || size >= budget / 2)) {
            closed = true; reason = "complete_gop_boundary"; return
        }
        if (size + bytes.size > budget || frames.size >= 1800) { closed = true; reason = "budget_before_gop_boundary"; return }
        frames.add(AccessUnit(bytes.copyOf(), now, senderNs, arrivalNs, key)); size += bytes.size
        reason = "open_gop_at_capture_end"
    }
    data class Snapshot(val codec: VideoCodec, val csd: ByteArray, val frames: List<AccessUnit>, val bytes: Int,
        val strictReplay: Boolean, val reason: String, val configurationChanged: Boolean)
    @Synchronized fun finish(): Snapshot {
        closed = true
        return Snapshot(codec, csd.copyOf(), frames.toList(), size,
            !changed && frames.isNotEmpty() && reason == "complete_gop_boundary", reason, changed)
    }
}

/** Bounded per-process trace, enabled only by explicit diagnostics. Nanoseconds share the local monotonic clock. */
object VideoDiagnosticTrace {
    data class Event(val instance: String, val stage: String, val frame: Long, val nanos: Long,
        val a: Long = 0, val b: Long = 0, val c: Long = 0)
    private var enabled = false
    private var dropped = 0
    private val events = ArrayList<Event>()
    @Synchronized fun begin() { events.clear(); dropped = 0; enabled = true }
    @Synchronized fun event(instance: String, stage: String, frame: Long, a: Long = 0, b: Long = 0, c: Long = 0) {
        if (!enabled) return
        if (events.size >= 50_000) { dropped++; return }
        events.add(Event(instance, stage, frame, System.nanoTime(), a, b, c))
    }
    data class Snapshot(val events: List<Event>, val dropped: Int)
    @Synchronized fun end(): Snapshot { enabled = false; return Snapshot(events.toList(), dropped) }
}
