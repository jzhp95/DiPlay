package com.shilapi.xcertplay.media.video

import android.view.Surface

/**
 * 包装旧版视频管线或用于非主屏镜像兼容的端点适配器 (LegacyVideoEndpoint)。
 */
class LegacyVideoEndpoint(
    override val streamRole: Int,
    private val onConfigCall: (ByteArray) -> Unit = {},
    private val onCodecCall: (Int) -> Unit = {},
    private val onFrameCall: (ByteArray, Long, Boolean) -> Unit = { _, _, _ -> },
    private val onAttachSurface: (Surface) -> Unit = {},
    private val onDetachSurface: () -> ReleaseTicket = {
        val t = SettableReleaseTicket(ReleaseState.RELEASED)
        t.complete(ReleaseState.RELEASED)
        t
    },
    private val onClose: () -> Unit = {}
) : SessionVideoEndpoint {

    override val implementation: VideoImplementation = VideoImplementation.LEGACY

    override fun onConfig(configBytes: ByteArray) = onConfigCall(configBytes)
    override fun onCodec(codec: Int) = onCodecCall(codec)
    override fun onFrame(frameBytes: ByteArray, ptsUs: Long, isKey: Boolean) = onFrameCall(frameBytes, ptsUs, isKey)
    override fun attachSurface(surface: Surface) = onAttachSurface(surface)
    override fun detachSurface(): ReleaseTicket = onDetachSurface()
    override fun snapshot(): RuntimeSnapshot = RuntimeSnapshot(
        sessionId = "legacy-$streamRole",
        streamRole = streamRole,
        requestedImplementation = VideoImplementation.LEGACY,
        actualImplementation = VideoImplementation.LEGACY
    )
    override fun close() = onClose()
}
