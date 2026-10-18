package com.shilapi.xcertplay.network

/** Small adapter boundary: firmware/Android objects stay outside the transaction and priority policy. */
internal interface CarHotspotApi {
    val name: String
    fun read(): CarHotspotProfile?
    fun enabled(): Boolean?
    fun supports(profile: CarHotspotProfile): Boolean
    fun configure(profile: CarHotspotProfile): Boolean
    fun start(): Boolean
    fun stop(): Boolean
}

data class CarHotspotControlResult(val success: Boolean, val running: Boolean?, val profile: CarHotspotProfile?, val diagnostic: String)

internal class CarHotspotControl(
    private val apis: List<CarHotspotApi>,
    private val log: (String) -> Unit = {},
    private val waitState: (CarHotspotApi, Boolean, () -> Boolean) -> Boolean = { api, target, cancelled ->
        val until = System.nanoTime() + 4_000_000_000L
        var matched = api.enabled() == target
        while (!matched && !cancelled() && !Thread.currentThread().isInterrupted && System.nanoTime() < until) {
            Thread.sleep(200)
            matched = api.enabled() == target
        }
        matched
    },
) {
    fun read(): CarHotspotProfile? = apis.firstNotNullOfOrNull { api ->
        attempt(api, "read") { api.read()?.takeIf { it.error() == null } }
    }

    fun apply(profile: CarHotspotProfile, cancelled: () -> Boolean = { false }): CarHotspotControlResult {
        if (profile.error() != null) return failure("validation:${profile.error()}")
        for (api in apis) {
            if (cancelled()) return failure("cancelled")
            if (attempt(api, "supports") { api.supports(profile) } != true) continue
            val wasRunning = attempt(api, "state") { api.enabled() } == true
            val previous = attempt(api, "read-before") { api.read() } ?: read()
            if (previous != null && attempt(api, "supports-rollback") { api.supports(previous) } != true) continue
            var changed = false
            var stopped = false
            var stage = "stop"
            try {
                // A persisted configuration is not proof that an already-running AP changed its radio/SSID.
                if (wasRunning) {
                    if (!api.stop()) { log("${api.name}:stop rejected"); continue }
                    stopped = true
                    if (!waitState(api, false, cancelled)) throw IllegalStateException("stop-unconfirmed")
                }
                if (cancelled()) throw InterruptedException()
                stage = "configure"
                if (!api.configure(profile)) throw IllegalStateException("configure-rejected")
                changed = true
                if (cancelled()) throw InterruptedException()
                stage = "start"
                if (!api.start() || !waitState(api, true, cancelled)) throw IllegalStateException("start-unconfirmed")
                if (cancelled()) throw InterruptedException()
                stage = "verify"
                val observed = api.read() ?: read()
                if (observed == null || !profile.matches(observed)) {
                    log("${api.name}:readback available=${observed != null} credentialsMatch=${observed?.let { it.ssid == profile.ssid && it.passphrase == profile.passphrase }} requested=${profile.security}/${profile.band}/${profile.channel} actual=${observed?.security}/${observed?.band}/${observed?.channel}")
                    throw IllegalStateException("readback-mismatch-or-unavailable")
                }
                log("${api.name}:configuration verified; band=${observed.band} channel=${observed.channel}")
                return CarHotspotControlResult(true, true, observed, "${api.name}:verified")
            } catch (error: Exception) {
                // Firmware exception messages can include passwords. Record only controlled stage/class names.
                log("${api.name}:apply stage=$stage failed ${errorClass(error)}")
                if (error is InterruptedException) Thread.currentThread().interrupt()
                if ((changed || stopped) && !rollback(api, previous, wasRunning, changed)) return failure("rollback-unconfirmed")
                if (cancelled() || Thread.currentThread().isInterrupted) return failure("cancelled")
            }
        }
        return failure("no-interface-verified-configuration")
    }

    fun enable(cancelled: () -> Boolean = { false }): CarHotspotControlResult {
        for (api in apis) {
            if (cancelled()) return failure("cancelled")
            if (attempt(api, "state") { api.enabled() } == true ||
                (attempt(api, "start") { api.start() } == true && attempt(api, "wait") { waitState(api, true, cancelled) } == true)) {
                return CarHotspotControlResult(true, true, read(), "${api.name}:running")
            }
        }
        return failure("hotspot-start-unconfirmed")
    }

    private fun rollback(api: CarHotspotApi, previous: CarHotspotProfile?, wasRunning: Boolean, changed: Boolean): Boolean {
        // Cancellation must not prevent bounded cleanup from restoring the previous AP state.
        val interrupted = Thread.interrupted()
        try {
            val off = attempt(api, "rollback-stop") {
                api.enabled() == false || (api.stop() && waitState(api, false) { false })
            } == true
            if (!off) { log("${api.name}:rollback stop unconfirmed"); return false }
            val restoredConfiguration = !changed || (previous != null && attempt(api, "rollback-config") {
                val current = api.read() ?: read()
                (current != null && previous.matches(current)) ||
                    (api.configure(previous) && (api.read() ?: read())?.let(previous::matches) == true)
            } == true)
            val restoredState = if (wasRunning) attempt(api, "rollback-start") {
                api.start() && waitState(api, true) { false }
            } == true else api.enabled() == false
            log("${api.name}:rollback configuration=$restoredConfiguration state=$restoredState")
            return restoredConfiguration && restoredState
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun errorClass(error: Throwable): String =
        ((error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error).javaClass.simpleName

    private fun failure(reason: String) = CarHotspotControlResult(false, null, null, reason).also { log("hotspot:$reason") }
    private fun <T> attempt(api: CarHotspotApi, stage: String, action: () -> T): T? = try { action() } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        log("${api.name}:$stage ${errorClass(error)}"); null
    }
}
