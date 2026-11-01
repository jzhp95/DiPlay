package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.video.VideoImplementation

/**
 * 视频实现保存操作结果。
 */
sealed interface SaveResult {
    data class Success(val newRevision: Long) : SaveResult
    data class Conflict(val currentRevision: Long) : SaveResult
    data class Error(val reason: String) : SaveResult
}

/**
 * 视频管线实现持久化与版本控制偏好管理器 (VideoImplementationPreferences)。
 * 严禁使用设置值直接充当实际运行证据；管理设置 revision 防止并发写入冲突。
 */
class VideoImplementationPreferences(private val context: Context) {

    /**
     * 读取配置的视频实现分支。若键缺失、损坏或为未知值，则安全回退至 LEGACY。
     */
    fun load(): VideoImplementation {
        return AirPlayPersistence.getVideoImplementation(context)
    }

    /**
     * 读取当前设置的版本代际 (Revision)。
     */
    fun loadRevision(): Long {
        return AirPlayPersistence.getVideoImplementationRevision(context)
    }

    /**
     * 针对期望版本原子保存新的实现选择。
     */
    fun save(selection: VideoImplementation, expectedRevision: Long): SaveResult {
        val currentRev = loadRevision()
        if (expectedRevision != -1L && currentRev != expectedRevision) {
            return SaveResult.Conflict(currentRev)
        }
        val newRev = currentRev + 1
        val ok = AirPlayPersistence.setVideoImplementation(context, selection, newRev)
        return if (ok) {
            SaveResult.Success(newRev)
        } else {
            SaveResult.Error("Failed to persist video implementation setting")
        }
    }
}
