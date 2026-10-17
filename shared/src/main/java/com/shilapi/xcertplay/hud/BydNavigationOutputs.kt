package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Phone-only call/navigation state; the former vendor output APIs remain inert for compatibility. */
@Suppress("UNUSED_PARAMETER")
object BydNavigationOutputs {
    // Kept as a compatibility boundary for shared controller callers. No vendor services are started.
    fun onAppOpened(context: Context) = Unit
    fun setDiagnosticHold(hold: Boolean) = Unit
    @Volatile private var overlayListener: ((ClusterTurnGuidance?) -> Unit)? = null
    private val overlayLock = Any()
    private var publishedOverlay: ClusterTurnGuidance? = null
    private val overlayRoute = BydHudRouteState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
        keepAcrossNoRoute = true,
    )
    fun setClusterMapShown(shown: Boolean) = Unit
    fun setClusterStreamControl(control: (Boolean) -> Unit) = Unit
    fun clusterNaviMode(): BydClusterNaviMode? = null
    fun setClusterNaviModeListener(listener: ((BydClusterNaviMode?) -> Unit)?) { listener?.invoke(null) }
    fun clearClusterStreamControl(control: (Boolean) -> Unit) = Unit
    fun batteryStatus(context: Context): com.shilapi.xcertplay.transport.VehicleStatusProvider =
        com.shilapi.xcertplay.transport.VehicleStatusProvider { null }
    fun wheelSpeed(context: Context): com.shilapi.xcertplay.transport.VehicleSpeedSource =
        object : com.shilapi.xcertplay.transport.VehicleSpeedSource {
            override fun start() = Unit
            override fun stop() = Unit
            override fun drain(): com.shilapi.xcertplay.transport.VehicleSpeedReading? = null
        }
    fun parked(context: Context): Boolean? = null
    fun start(context: Context) = Unit

    internal fun onFrame(frame: Iap2Frame) {
        // Phone protocol state is shared with Siri/call-key handling and the generic map overlay.
        if (frame.messageId == CarPlayCallState.CALL_STATE_UPDATE) {
            CarPlayPhoneState.onFrame(frame)
            return
        }
        if (frame.messageId == BydHudRouteState.ROUTE_GUIDANCE_UPDATE ||
            frame.messageId == BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) updateOverlay(frame)
    }

    /** Live next-turn state for the dashboard overlay. Called from the iAP2 thread. */
    fun setTurnOverlayListener(listener: ((ClusterTurnGuidance?) -> Unit)?) {
        overlayListener = listener
        val next = currentOverlay()
        synchronized(overlayLock) { publishedOverlay = next }
        listener?.invoke(next)
    }

    private fun updateOverlay(frame: Iap2Frame) {
        val change = synchronized(overlayLock) { overlayRoute.accept(frame.messageId, frame.payload) }
        if (change != BydHudRouteChange.NONE) refreshTurnOverlay()
    }

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshTurnOverlay() {
        val next = synchronized(overlayLock) {
            val current = currentOverlay()
            if (current == publishedOverlay) return
            publishedOverlay = current
            current
        }
        overlayListener?.invoke(next)
    }

    private fun currentOverlay(): ClusterTurnGuidance? = synchronized(overlayLock) {
        overlayRoute.currentApple()?.let { apple ->
            ClusterTurnGuidance.from(BydClusterFrame.from(apple)).copy(
                arrivalEpochSeconds = apple.arrivalEpochSeconds,
                remainingSeconds = apple.remainingSeconds,
                remainingMeters = apple.remainingMeters,
            )
        }
    }

    fun clusterSongChanged(enabled: Boolean) = Unit
    fun carPlayCallsChanged(enabled: Boolean) = Unit
    fun carPlaySessionStarted() = Unit
    fun carPlayCall(): CarPlayCallCard? = CarPlayPhoneState.current()
    fun clusterSongOnChangeChanged() = Unit
    fun dashboardNote(text: String, source: Int? = null) = Unit

    fun endNow(preserveTurnOverlay: Boolean = false) {
        CarPlayPhoneState.end()
        // Only a wireless session replacement retains the card. Explicit controller close
        // and wired disconnect still clear it immediately.
        if (!preserveTurnOverlay) {
            synchronized(overlayLock) { overlayRoute.clear() }
            refreshTurnOverlay()
        } else {
            synchronized(overlayLock) { overlayRoute.holdAcrossDrop() }
        }
    }
}
