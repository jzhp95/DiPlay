package com.shilapi.xcertplay.media.video

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 独立主屏视频运行时协调器。
 * 负责状态机流转、会话与代际管理、配置屏障建立、后端生命周期协调与资源退役。
 */
class VideoRuntimeCoordinator(
    private val dependencies: RuntimeDependencies,
    initialPolicy: VideoRuntimePolicy = VideoRuntimePolicy(),
    val queue: EncodedQueue = EncodedQueue(clock = dependencies.clock),
    val activity: VideoInputActivity = VideoInputActivity()
) : VideoRuntime {

    private val controlLock = ReentrantLock()
    private val policy = AtomicReference(initialPolicy)
    private val activeSession = AtomicReference(SessionVideoId("uninitialized"))

    private val configGen = AtomicLong(0L)
    private val decoderGen = AtomicLong(0L)
    private val surfaceGen = AtomicLong(0L)

    private val currentConfig = AtomicReference<VideoStreamConfig?>(null)
    private val currentOutputTarget = AtomicReference<OutputTarget?>(null)
    private val currentBackend = AtomicReference<VideoBackend?>(null)
    private val activeBackendKind = AtomicReference<BackendKind?>(null)

    private val releaseTicket = AtomicReference<SettableReleaseTicket?>(null)
    private val releaseState = AtomicReference(ReleaseState.PENDING)
    private val isStopped = AtomicBoolean(false)
    private var lastError: String? = null

    override fun attachSession(identity: SessionVideoId) {
        controlLock.withLock {
            activeSession.set(identity)
            configGen.set(0L)
            decoderGen.set(0L)
            surfaceGen.set(0L)
            currentConfig.set(null)
            currentOutputTarget.set(null)
            isStopped.set(false)
            releaseState.set(ReleaseState.PENDING)
            lastError = null

            val discarded = queue.resetForConfig(0L)
            discarded.forEach { it.lease.close() }
            activity.reset()

            // 退役旧后端
            currentBackend.getAndSet(null)?.let { old ->
                old.requestStop()
                old.awaitReleased(dependencies.clock.nowNs() + 500_000_000L)
            }
        }
    }

    override fun onConfig(config: VideoStreamConfig) {
        controlLock.withLock {
            if (isStopped.get()) return

            val existing = currentConfig.get()
            if (existing != null && existing.codec == config.codec && existing.csdHash == config.csdHash) {
                // 重复配置保持幂等
                return
            }

            val newConfigGen = configGen.incrementAndGet()
            val newDecoderGen = decoderGen.incrementAndGet()
            currentConfig.set(config)

            // 建立新配置屏障：清空队列中旧代际帧并安全释放其租约
            val discarded = queue.resetForConfig(newConfigGen)
            discarded.forEach { it.lease.close() }

            // 决定并创建新后端
            val preferredKind = policy.get().preferredBackend ?: when (policy.get().decoderPreference) {
                DecoderPreference.HARDWARE -> BackendKind.PLATFORM_SURFACE
                DecoderPreference.SOFTWARE -> BackendKind.PLATFORM_YUV
                DecoderPreference.AUTO -> BackendKind.PLATFORM_SURFACE
            }

            val identity = currentIdentity()
            val newBackend = dependencies.backendFactory.createBackend(preferredKind, identity)

            // 退役前任后端
            val oldBackend = currentBackend.getAndSet(newBackend)
            activeBackendKind.set(preferredKind)
            oldBackend?.let {
                it.requestStop()
                it.awaitReleased(dependencies.clock.nowNs() + 300_000_000L)
            }

            val target = currentOutputTarget.get() ?: OutputTarget(surface = null)
            val backendOptions = BackendOptions(
                allowHardware = policy.get().decoderPreference != DecoderPreference.SOFTWARE,
                lowLatencyMode = true,
                eglSwapIntervalZero = true
            )

            when (val result = newBackend.configure(config, target, backendOptions)) {
                is ConfigureResult.Success -> {
                    lastError = null
                }
                is ConfigureResult.Rejected -> {
                    lastError = "Backend rejected config: ${result.reason}"
                    dependencies.recoveryRequest?.invoke(lastError!!)
                }
                is ConfigureResult.Failed -> {
                    lastError = "Backend configure failed: ${result.cause.message}"
                    dependencies.recoveryRequest?.invoke(lastError!!)
                }
            }
        }
    }

    override fun tryOfferAccessUnit(unit: EncodedAccessUnit): OfferResult {
        if (isStopped.get()) {
            return OfferResult.REJECTED
        }

        // 观察流活动状态（静止与唤醒）
        val queueSnap = queue.snapshot()
        activity.observe(
            nowNs = unit.arrivalMonoNs,
            hasNewInput = true,
            hasPendingWork = queueSnap.depth > 0
        )

        // 检查过载保护
        if (activity.isOverloaded(
                nowNs = unit.arrivalMonoNs,
                oldestFrameArrivalNs = queueSnap.oldestArrivalNs,
                currentDepth = queueSnap.depth,
                maxFrames = queue.maxFrames
            )
        ) {
            dependencies.recoveryRequest?.invoke("input_overload_detected (depth=${queueSnap.depth})")
        }

        return queue.offer(unit)
    }

    override fun attachOutput(target: OutputTarget) {
        controlLock.withLock {
            if (isStopped.get()) return

            surfaceGen.set(target.surfaceGeneration)
            currentOutputTarget.set(target)

            val backend = currentBackend.get()
            val config = currentConfig.get()
            if (backend != null && config != null) {
                backend.configure(
                    config,
                    target,
                    BackendOptions(lowLatencyMode = true, eglSwapIntervalZero = true)
                )
            }
        }
    }

    override fun detachOutput(surfaceGeneration: Long): ReleaseTicket {
        controlLock.withLock {
            val ticket = SettableReleaseTicket()
            val current = currentOutputTarget.get()
            if (current != null && current.surfaceGeneration == surfaceGeneration) {
                currentOutputTarget.set(null)
            }
            ticket.complete(ReleaseState.RELEASED)
            return ticket
        }
    }

    override fun setRuntimePolicy(policy: VideoRuntimePolicy) {
        this.policy.set(policy)
    }

    override fun stop(reason: String): ReleaseTicket {
        controlLock.withLock {
            isStopped.set(true)
            val ticket = SettableReleaseTicket()
            releaseTicket.set(ticket)

            // 清理并排空队列，防止泄漏在途租约
            val discarded = queue.close()
            discarded.forEach { it.lease.close() }

            val backend = currentBackend.getAndSet(null)
            if (backend != null) {
                backend.requestStop()
                val deadline = dependencies.clock.nowNs() + 500_000_000L
                val state = backend.awaitReleased(deadline)
                releaseState.set(state)
                ticket.complete(state)
            } else {
                releaseState.set(ReleaseState.RELEASED)
                ticket.complete(ReleaseState.RELEASED)
            }

            return ticket
        }
    }

    override fun snapshot(): VideoRuntimeSnapshot {
        val qSnap = queue.snapshot()
        val sess = activeSession.get()
        val pol = policy.get()

        return VideoRuntimeSnapshot(
            sessionId = sess.sessionId,
            streamRole = sess.streamRole,
            requestedImplementation = pol.requestedImplementation,
            actualImplementation = VideoImplementation.INDEPENDENT,
            actualBackend = activeBackendKind.get(),
            activityState = activity.currentState(),
            releaseState = releaseState.get(),
            configGeneration = configGen.get(),
            decoderGeneration = decoderGen.get(),
            surfaceGeneration = surfaceGen.get(),
            fallbackReason = null,
            lastError = lastError,
            lastInputArrivalMonoNs = activity.lastInputMonoNs(),
            idleDurationMs = activity.idleDurationMs(),
            resumeEpisodeId = activity.currentResumeEpisodeId(),
            appOwnedBytes = qSnap.bytes,
            inputQueueDepth = qSnap.depth,
            activeLeaseCount = qSnap.depth
        )
    }

    fun currentIdentity(): RuntimeIdentity = RuntimeIdentity(
        stream = activeSession.get(),
        configGeneration = configGen.get(),
        decoderGeneration = decoderGen.get(),
        surfaceGeneration = surfaceGen.get(),
        backendInstanceId = "backend-${decoderGen.get()}"
    )
}
