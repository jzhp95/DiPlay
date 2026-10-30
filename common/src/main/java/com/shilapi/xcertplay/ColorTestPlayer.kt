package com.shilapi.xcertplay

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.VideoColorOverride
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

/** Uses the production decoder for fixtures and a separate mirror for live CarPlay. No saved settings. */
internal class ColorTestPlayer(context: Context) {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "color-test").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val stateLock = Any()
    private data class Request(val token: Int, val surface: Surface)
    private var pending: Request? = null
    private var attachedRequest: Request? = null
    private var retainedRequest: Request? = null
    @Volatile private var closed = false
    @Volatile private var attachedSink: AndroidMediaSink? = null
    @Volatile private var attachedSurface: Surface? = null

    @Volatile private var fixtureStartedNs = 0L
    fun performance(): org.json.JSONObject {
        val counters = attachedSink?.liveVideoCounters() ?: return org.json.JSONObject().put("unavailable", true)
        val seconds = (System.nanoTime() - fixtureStartedNs) / 1e9
        val p = counters.pipeline
        fun avg(n: Long, count: Long): Any = if (count > 0) n / count / 1e6 else org.json.JSONObject.NULL
        return org.json.JSONObject().put("durationSeconds", seconds).put("receivedFrames", counters.received).put("submittedFrames", counters.rendered)
            .put("submittedFps", if (seconds > 0) counters.rendered / seconds else org.json.JSONObject.NULL)
            .put("appWaitMillis", avg(p.applicationWaitNanos, p.inputSamples)).put("inputAcquireMillis", avg(p.inputWaitNanos, p.inputSamples))
            .put("codecQueueToDequeueMillis", avg(p.codecOutputNanos, p.outputSamples))
            .put("yuvCopyMillis", avg(p.yuvCopyNanos, p.yuvSamples)).put("yuvDrawMillis", avg(p.yuvDrawNanos, p.yuvSamples))
            .put("backgroundCarPlaySession", CarPlayBackgroundSession.hasSession())
            .put("scope", "fixture_app_counters_not_physical_panel_fps; concurrent_CarPlay_may_add_load")
    }
    @Volatile private var retainedEvidence: com.shilapi.xcertplay.media.VideoSourceColorEvidence? = null
    fun evidence(): com.shilapi.xcertplay.media.VideoSourceColorEvidence? = synchronized(stateLock) {
        val request = pending ?: return@synchronized null
        if (attachedRequest === request) attachedSink?.videoColorEvidence(110)
            ?: retainedEvidence.takeIf { retainedRequest === request }
        else retainedEvidence.takeIf { retainedRequest === request }
    }

    fun stop(released: () -> Unit = {}) {
        synchronized(stateLock) { pending = null; retainedRequest = null; retainedEvidence = null; generation.incrementAndGet() }
        afterCleanup(released)
    }
    fun afterCleanup(released: () -> Unit) {
        if (!closed) worker.execute { main.post(released) }
        else Thread({ worker.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS); main.post(released) }, "color-release").apply { isDaemon = true; start() }
    }
    fun detachSurface(surface: Surface) {
        val sink = synchronized(stateLock) {
            if (pending?.surface === surface) { pending = null; generation.incrementAndGet() }
            attachedSink?.takeIf { attachedSurface === surface }
        }
        sink?.beginSurfaceDetach(surface, parkMain = false)?.await()
    }
    fun close() {
        if (closed) return
        synchronized(stateLock) { pending = null; generation.incrementAndGet(); closed = true }
        worker.shutdown()
    }
    fun start(case: ColorTestRun.Case, color: VideoColorOverride, surface: Surface, event: (String) -> Unit) {
        if (closed) return
        val request = synchronized(stateLock) {
            Request(generation.incrementAndGet(), surface).also { pending = it; retainedEvidence = null; retainedRequest = it }
        }
        val token = request.token
        fun alive() = token == generation.get() && !closed
        fun emit(message: String) { main.post { if (alive()) event(message) } }
        worker.execute {
            if (!alive()) return@execute
            val diagnosticLease=com.shilapi.xcertplay.diagnostics.DiagnosticLease.acquire(
                java.util.UUID.randomUUID().toString(),DiPlaySessionService.liveRuntime?.currentConnection?.controller)
            if(diagnosticLease==null){emit("错误：另一项诊断正在执行或等待资源释放");return@execute}
            var sink: AndroidMediaSink? = null
            var extractor: MediaExtractor? = null
            var live = false
            try {
                if (case.asset == null) {
                    val session = com.shilapi.xcertplay.diagnostics.DiagnosticLease.production(diagnosticLease)
                        ?: throw IllegalStateException("未连接 CarPlay。先连接 iPhone，再返回此页重试；无需退出测试页修改参数。")
                    synchronized(stateLock) {
                        if (pending !== request || !surface.isValid) return@execute
                        sink = session.sink
                        attachedSink = sink; attachedSurface = surface; attachedRequest = request
                        live = true
                        emit("同源 CarPlay 镜像 ${session.width}x${session.height}；镜像单独解码，不改变主屏")
                        sink!!.setMirrorSurface(110, MIRROR, surface, color, ::emit)
                    }
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    var sawFrame = false
                    // Mirror diagnostics provide its own first-frame signal, not the main screen's counters.
                    while (alive()) {
                        if (com.shilapi.xcertplay.diagnostics.DiagnosticLease.production(diagnosticLease)?.sink !== sink) throw IllegalStateException("CarPlay 连接已变化，请重试当前项目")
                        if (!sawFrame && SystemClock.elapsedRealtime() > deadline) {
                            emit("提示：若画面仍黑屏，请点亮 iPhone 或切换 CarPlay 页面触发新画面；也可记录无法显示")
                            sawFrame = true
                        }
                        Thread.sleep(100)
                    }
                } else {
                    val fixtureCodec = if (case.asset.startsWith("h264")) VideoCodec.H264 else VideoCodec.H265
                    val fixtureCsd = app.assets.open("color-test/${case.asset}.csd").use { it.readBytes() }
                    synchronized(stateLock) { if (pending === request) retainedEvidence =
                        com.shilapi.xcertplay.media.VideoSourceColorEvidence(fixtureCodec.name, fixtureCsd,
                            com.shilapi.xcertplay.media.VideoColorMetadata.parse(fixtureCodec, fixtureCsd), null,
                            com.shilapi.xcertplay.media.videoConfigurationHash(fixtureCsd)) }
                    val reader = MediaExtractor().also { extractor = it }
                    app.assets.openFd("color-test/${case.asset}.mp4").use {
                        reader.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                    }
                    val format = reader.getTrackFormat(0)
                    val fixtureFps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(10).coerceIn(1, 120)
                    var nextFrameNs = System.nanoTime()
                    fixtureStartedNs = nextFrameNs
                    reader.selectTrack(0)
                    val hevc = format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_HEVC
                    synchronized(stateLock) {
                    if (pending !== request || !surface.isValid) return@execute
                    val plan=com.shilapi.xcertplay.media.VideoPlanResolver.resolve(com.shilapi.xcertplay.media.VideoPipelineRequest(
                        codec=fixtureCodec,width=format.getInteger(MediaFormat.KEY_WIDTH),height=format.getInteger(MediaFormat.KEY_HEIGHT),
                        forceSoftware=case.forceSoftware,hardwareYuv=case.hardwareYuv,direct=case.surface,color=color,
                        purpose=com.shilapi.xcertplay.media.VideoPurpose.DIAGNOSTIC))
                    sink = AndroidMediaSink(videoPipelinePlan=plan,surface = surface,
                        videoWidth = format.getInteger(MediaFormat.KEY_WIDTH),
                        videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT),
                        videoColorOverride = color, forceSoftwareVideoDecoder = case.forceSoftware,
                        mainScreenHardwareYuv = case.hardwareYuv)
                    attachedSink = sink; attachedSurface = surface; attachedRequest = request
                    sink!!.setVideoDiagnosticHandler(110, ::emit)
                    emit("测试片输入 $format；请求 range=${color.range ?: "auto"} standard=${color.standard ?: "auto"} fromSps=${color.fromSps}")
                    sink!!.onVideoCodec(110, if (hevc) VideoCodec.H265 else VideoCodec.H264)
                    sink!!.onVideoConfig(110, app.assets.open("color-test/${case.asset}.csd").use { it.readBytes() })
                    }
                    val buffer = ByteBuffer.allocate(1024 * 1024)
                    var firstFrame = false
                    val started = SystemClock.elapsedRealtime()
                    while (alive()) {
                        buffer.clear()
                        val size = reader.readSampleData(buffer, 0)
                        if (size < 0) { reader.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC); continue }
                        val bytes = ByteArray(size); buffer.position(0); buffer.get(bytes)
                        sink!!.onVideoFrame(110, bytes)
                        if (!firstFrame && (sink!!.liveVideoCounters()?.rendered ?: 0) > 0) {
                            firstFrame = true
                            emit("first frame rendered")
                        }
                        if (!firstFrame && SystemClock.elapsedRealtime() - started > 10_000)
                            throw IllegalStateException("10 秒内没有解码画面；该解码器可能不支持此测试片")
                        reader.advance()
                        nextFrameNs += 1_000_000_000L / fixtureFps
                        val sleepMs = ((nextFrameNs - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
                        if (sleepMs > 0) Thread.sleep(sleepMs)
                        if (System.nanoTime() - nextFrameNs > 250_000_000L) nextFrameNs = System.nanoTime()
                    }
                }
            } catch (error: Exception) {
                emit("错误：${error.javaClass.simpleName}: ${error.message}")
            } finally {
                runCatching { extractor?.release() }
                synchronized(stateLock) {
                    if (pending === request) sink?.videoColorEvidence()?.let { retainedEvidence = it; retainedRequest = request }
                }
                sink?.let { output ->
                    if (live) {
                        if (!output.detachMirrorAndAwait(110, MIRROR, 5_000)) {
                            emit("错误：车机尚未释放测试镜像，等待实际释放后才能继续")
                            output.detachMirrorAndAwait(110, MIRROR, Long.MAX_VALUE)
                        }
                    } else {
                        output.close()
                        if (!output.awaitVideoReleased(5_000)) {
                            emit("错误：车机尚未释放测试解码器，正在等待实际释放")
                            val released = CountDownLatch(1)
                            output.whenVideoReleased { released.countDown() }
                            released.await()
                        }
                    }
                }
                synchronized(stateLock) { attachedSink = null; attachedSurface = null; attachedRequest = null }
                com.shilapi.xcertplay.diagnostics.DiagnosticLease.release(diagnosticLease,true)
            }
        }
    }
    companion object { private const val MIRROR = "color-diagnostic" }
}
