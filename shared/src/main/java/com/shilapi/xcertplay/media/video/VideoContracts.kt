package com.shilapi.xcertplay.media.video

import android.view.Surface
import java.nio.ByteBuffer

/**
 * 核心管线实现分支。
 */
enum class VideoImplementation {
    LEGACY,
    INDEPENDENT
}

/**
 * 解码与呈现后端种类。
 */
enum class BackendKind {
    PLATFORM_SURFACE,
    PLATFORM_YUV,
    NATIVE_YUV
}

/**
 * 解码器偏好。
 */
enum class DecoderPreference {
    HARDWARE,
    SOFTWARE,
    AUTO
}

/**
 * 访问单元接纳结果。
 * 只有 ACCEPTED 才转移缓冲所有权；BACKPRESSURE / REJECTED 调用方负责归还。
 */
enum class OfferResult {
    ACCEPTED,
    BACKPRESSURE,
    REJECTED
}

/**
 * 资源释放状态。
 * 只有真正退役才成立 RELEASED；无法确认释放时保持 BLOCKED。
 */
enum class ReleaseState {
    PENDING,
    RELEASED,
    BLOCKED
}

/**
 * 视频流输入活动状态（静止与唤醒）。
 */
enum class StreamActivity {
    ACTIVE,
    QUIESCENT,
    RESUMING
}

/**
 * 视频编码格式。
 */
enum class CodecType {
    H264,
    HEVC,
    UNKNOWN
}

/**
 * 访问单元封装格式。
 */
enum class SourceFraming {
    ANNEX_B,
    AVCC,
    HVCC,
    UNKNOWN
}

/**
 * 随机访问点类型。
 */
enum class RandomAccessType {
    IDR,
    CRA,
    NONE
}

/**
 * 单调时钟接口，解耦系统 System.nanoTime() 便于单元测试注入。
 */
fun interface MonotonicClock {
    fun nowNs(): Long
}

/**
 * 字节缓冲租约，持有对原生或直接内存块的受控借用。
 */
interface BufferLease : AutoCloseable {
    val buffer: ByteBuffer
    val sizeBytes: Int
    override fun close()
}

/**
 * 异步释放票据。
 */
interface ReleaseTicket {
    fun state(): ReleaseState
    fun awaitUntil(deadlineNs: Long): ReleaseState
    fun onComplete(listener: (ReleaseState) -> Unit): AutoCloseable
}

/**
 * 解码帧租约接口，具有恰好一次底层释放责任。
 */
interface DecodedFrameLease : AutoCloseable {
    val key: FrameKey
    val releaseAttempts: Int
    fun releaseOnce()
    override fun close() = releaseOnce()
}

/**
 * 会话流唯一身份。
 */
data class SessionVideoId(
    val sessionId: String,
    val streamRole: Int = 110,
    val streamGeneration: Long = 1L
)

/**
 * 运行时代际与组件唯一标识。
 */
data class RuntimeIdentity(
    val stream: SessionVideoId,
    val configGeneration: Long,
    val decoderGeneration: Long,
    val surfaceGeneration: Long,
    val backendInstanceId: String
)

/**
 * 帧唯一身份。
 */
data class FrameKey(
    val identity: RuntimeIdentity,
    val accessUnitId: Long,
    val codecPtsUs: Long
)

/**
 * 视频流配置快照（不可变）。
 */
data class VideoStreamConfig(
    val streamId: SessionVideoId,
    val codec: CodecType,
    val width: Int,
    val height: Int,
    val framing: SourceFraming = SourceFraming.ANNEX_B,
    val colorDeclaration: SourceColorDeclaration = SourceColorDeclaration(),
    private val csdData: List<ByteArray> = emptyList(),
    val csdHash: Long = computeCsdHash(csdData)
) {
    /** 返回不可变防御性拷贝副本，防止外部数组篡改 */
    val csd: List<ByteArray> = csdData.map { it.clone() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoStreamConfig) return false
        return streamId == other.streamId &&
                codec == other.codec &&
                width == other.width &&
                height == other.height &&
                framing == other.framing &&
                colorDeclaration == other.colorDeclaration &&
                csdHash == other.csdHash
    }

    override fun hashCode(): Int {
        var result = streamId.hashCode()
        result = 31 * result + codec.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + framing.hashCode()
        result = 31 * result + colorDeclaration.hashCode()
        result = 31 * result + csdHash.hashCode()
        return result
    }

    companion object {
        fun computeCsdHash(csdList: List<ByteArray>): Long {
            var hash = 1125899906842597L
            for (arr in csdList) {
                for (b in arr) {
                    hash = 31 * hash + b.toLong()
                }
            }
            return hash
        }
    }
}

/**
 * 待解码访问单元。
 */
data class EncodedAccessUnit(
    val accessUnitId: Long,
    val streamId: SessionVideoId,
    val configGeneration: Long,
    val lease: BufferLease,
    val sourcePtsUs: Long? = null,
    val arrivalMonoNs: Long,
    val isKeyFrame: Boolean = false,
    val randomAccessType: RandomAccessType = if (isKeyFrame) RandomAccessType.IDR else RandomAccessType.NONE
)

/**
 * 呈现目标描述。
 */
data class OutputTarget(
    val surface: Surface?,
    val role: Int = 110,
    val surfaceGeneration: Long = 1L,
    val width: Int = 0,
    val height: Int = 0,
    val ownsSurface: Boolean = false
)
