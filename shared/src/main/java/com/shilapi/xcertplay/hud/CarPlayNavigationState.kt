package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** CarPlay phone and navigation state, independent of vehicle services. */
object CarPlayNavigationState {
    @Volatile private var overlayListener: ((ClusterTurnGuidance?) -> Unit)? = null
    private val overlayLock = Any()
    private var publishedOverlay: ClusterTurnGuidance? = null
    private val overlayRoute = CarPlayRouteState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
        keepAcrossNoRoute = true,
    )
    internal fun onFrame(frame: Iap2Frame) {
        // Phone protocol state is shared with Siri/call-key handling and the generic map overlay.
        if (frame.messageId == CarPlayCallState.CALL_STATE_UPDATE) {
            CarPlayPhoneState.onFrame(frame)
            return
        }
        if (frame.messageId == CarPlayRouteState.ROUTE_GUIDANCE_UPDATE ||
            frame.messageId == CarPlayRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) updateOverlay(frame)
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
        if (change != CarPlayRouteChange.NONE) refreshTurnOverlay()
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
            ClusterTurnGuidance.from(CarPlayNavigationFrame.from(apple)).copy(
                arrivalEpochSeconds = apple.arrivalEpochSeconds,
                remainingSeconds = apple.remainingSeconds,
                remainingMeters = apple.remainingMeters,
            )
        }
    }

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
