package com.shilapi.xcertplay.media.video

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 帧渲染回执。
 * 记录单个帧在各个渲染阶段的时长、成功状态与故障阶段。
 */
data class RenderReceipt(
    val frameKey: FrameKey,
    val uploadNs: Long,
    val renderNs: Long,
    val swapNs: Long,
    val success: Boolean,
    val errorStage: String? = null,
    val error: Throwable? = null
)

/**
 * 独立 EGL 帧渲染器 (EglFrameRenderer)。
 * 单线程拥有 EGL 上下文与 GLES2 Program，使用 VideoColorTransform 生成 Shader 参数。
 * 纹理上传完成后立即归还源帧租约，释放 Codec Image / 缓冲区占用。
 */
class EglFrameRenderer(
    private val driverFactory: () -> EglDriver = { AndroidEglDriver() }
) {
    private val lock = ReentrantLock()
    private var driver: EglDriver? = null
    private var currentTarget: OutputTarget? = null
    private var program: Int = 0
    private var textures: IntArray? = null
    private val isRunning = AtomicBoolean(false)

    // Shader Uniform Locations
    private var locTexY = -1
    private var locTexU = -1
    private var locTexV = -1
    private var locYOffset = -1
    private var locYCoeff = -1
    private var locCOffset = -1
    private var locCCoeff = -1
    private var locColorMatrix = -1

    /**
     * 关联输出目标 Surface。
     */
    fun attach(target: OutputTarget) {
        lock.withLock {
            currentTarget = target
            val surface = target.surface ?: return

            val d = driver ?: driverFactory().also {
                it.initEgl()
                driver = it
            }

            d.destroySurface()
            if (d.createWindowSurface(surface)) {
                d.makeCurrent()
                // 请求 eglSwapInterval(0)，记录请求但不假定零等待
                d.setSwapInterval(0)
                initGles(d)
                isRunning.set(true)
            }
        }
    }

    private fun initGles(d: EglDriver) {
        if (program == 0) {
            program = d.createProgram(VideoShaders.VERTEX_SHADER, VideoShaders.FRAGMENT_SHADER_3_PLANES)
            if (program != 0) {
                locTexY = d.getUniformLocation(program, "uTextureY")
                locTexU = d.getUniformLocation(program, "uTextureU")
                locTexV = d.getUniformLocation(program, "uTextureV")
                locYOffset = d.getUniformLocation(program, "uYOffset")
                locYCoeff = d.getUniformLocation(program, "uYCoeff")
                locCOffset = d.getUniformLocation(program, "uCOffset")
                locCCoeff = d.getUniformLocation(program, "uCCoeff")
                locColorMatrix = d.getUniformLocation(program, "uColorMatrix")
            }
        }
        if (textures == null) {
            textures = d.genTextures(3)
        }
    }

    /**
     * 渲染单帧并应用色彩决策。
     */
    fun render(frame: DecodedFrameLease, decision: ColorDecision): RenderReceipt {
        val transform = when (decision) {
            is ColorDecision.Accepted -> decision.transform
            is ColorDecision.Unverified -> decision.fallbackTransform
            is ColorDecision.Unsupported -> return RenderReceipt(
                frameKey = frame.key,
                uploadNs = 0L,
                renderNs = 0L,
                swapNs = 0L,
                success = false,
                errorStage = "UNSUPPORTED_COLOR",
                error = IllegalArgumentException("Unsupported color: ${decision.reason}")
            )
        }

        lock.withLock {
            val d = driver
            if (d == null || !isRunning.get() || currentTarget?.surface == null) {
                return RenderReceipt(
                    frameKey = frame.key,
                    uploadNs = 0L,
                    renderNs = 0L,
                    swapNs = 0L,
                    success = false,
                    errorStage = "NO_SURFACE",
                    error = IllegalStateException("Renderer not attached or no active Surface")
                )
            }

            // 1. 纹理上传准备
            val startUpload = System.nanoTime()
            val uploadPlan = try {
                YuvPlaneReader.prepareUpload(frame)
            } catch (t: Throwable) {
                return RenderReceipt(frame.key, 0L, 0L, 0L, false, "UPLOAD_PREP", t)
            }

            val tex = textures ?: return RenderReceipt(
                frame.key, 0L, 0L, 0L, false, "TEXTURE_INIT", IllegalStateException("Textures not initialized")
            )

            // 上传 Y 平面
            d.bindTexture(0x0DE1 /* GL_TEXTURE_2D */, tex[0])
            d.texImage2D(0x0DE1, 0, 0x1909 /* GL_LUMINANCE */, uploadPlan.width, uploadPlan.height, 0, 0x1909, 0x1401 /* GL_UNSIGNED_BYTE */, uploadPlan.yBuffer)

            // 上传 U 平面
            d.bindTexture(0x0DE1, tex[1])
            d.texImage2D(0x0DE1, 0, 0x1909, uploadPlan.width / 2, uploadPlan.height / 2, 0, 0x1909, 0x1401, uploadPlan.uBuffer)

            // 上传 V 平面
            d.bindTexture(0x0DE1, tex[2])
            d.texImage2D(0x0DE1, 0, 0x1909, uploadPlan.width / 2, uploadPlan.height / 2, 0, 0x1909, 0x1401, uploadPlan.vBuffer)

            val uploadNs = System.nanoTime() - startUpload

            // 2. 纹理上传完成，及早归还源帧租约
            try {
                frame.releaseOnce()
            } catch (_: Throwable) {}

            // 3. 执行着色与绘制
            val startRender = System.nanoTime()
            d.useProgram(program)
            d.viewport(0, 0, uploadPlan.width, uploadPlan.height)

            d.uniform1f(locYOffset, transform.yOffset)
            d.uniform1f(locYCoeff, transform.yCoeff)
            d.uniform1f(locCOffset, transform.cOffset)
            d.uniform1f(locCCoeff, transform.cCoeff)
            d.uniformMatrix3fv(locColorMatrix, 1, false, transform.colorMatrixColumnMajor, 0)

            d.uniform1i(locTexY, 0)
            d.uniform1i(locTexU, 1)
            d.uniform1i(locTexV, 2)

            d.drawArrays(5 /* GL_TRIANGLE_STRIP */, 0, 4)
            val renderNs = System.nanoTime() - startRender

            // 4. 提交呈现 Swap
            val startSwap = System.nanoTime()
            val swapOk = d.swapBuffers()
            val swapNs = System.nanoTime() - startSwap

            return RenderReceipt(
                frameKey = frame.key,
                uploadNs = uploadNs,
                renderNs = renderNs,
                swapNs = swapNs,
                success = swapOk,
                errorStage = if (swapOk) null else "SWAP_FAILED",
                error = if (swapOk) null else IllegalStateException("eglSwapBuffers returned false")
            )
        }
    }

    /**
     * 停止并释放 EGL 资源。
     */
    fun stop(): ReleaseTicket {
        val ticket = SettableReleaseTicket(ReleaseState.PENDING)
        lock.withLock {
            isRunning.set(false)
            driver?.let { d ->
                textures?.let {
                    try { d.deleteTextures(it) } catch (_: Throwable) {}
                }
                textures = null
                if (program != 0) {
                    try { d.deleteProgram(program) } catch (_: Throwable) {}
                    program = 0
                }
                try { d.destroySurface() } catch (_: Throwable) {}
                try { d.terminateEgl() } catch (_: Throwable) {}
            }
            driver = null
            currentTarget = null
        }
        ticket.complete(ReleaseState.RELEASED)
        return ticket
    }
}
