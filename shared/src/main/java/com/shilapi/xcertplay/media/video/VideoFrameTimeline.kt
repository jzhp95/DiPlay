package com.shilapi.xcertplay.media.video

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 单帧生命周期各阶段时钟采样。
 */
data class FrameTimingRecord(
    val key: FrameKey,
    val receivedNs: Long,
    val submittedNs: Long = 0L,
    val decodedNs: Long = 0L,
    val renderedNs: Long = 0L,
    val isDropped: Boolean = false,
    val dropReason: String? = null
)

/**
 * 有界单帧时延与生命周期时间线 (VideoFrameTimeline)。
 * 使用有界环形双端队列 (默认容量 120 帧)，自动覆盖最旧数据，绝不无限堆积内存。
 */
class VideoFrameTimeline(private val maxEntries: Int = 120) {
    private val lock = ReentrantLock()
    private val entries = ArrayDeque<FrameTimingRecord>()

    fun record(record: FrameTimingRecord) {
        lock.withLock {
            if (entries.size >= maxEntries) {
                entries.pollFirst()
            }
            entries.addLast(record)
        }
    }

    fun snapshotEntries(): List<FrameTimingRecord> {
        lock.withLock {
            return entries.toList()
        }
    }

    fun clear() {
        lock.withLock {
            entries.clear()
        }
    }
}
