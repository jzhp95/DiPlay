package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.flymeeas.FlymeWheelBridge
import com.shilapi.flymeeas.PlaybackSnapshot
import com.shilapi.xcertplay.orchestration.CarPlayController

/** The only DiPlay-specific adapter. Binder ABI and focus lifecycle live in :flyme-eas. */
internal object FlymeWheelIntegration {
    private var owner: CarPlayController? = null
    private var bridge: FlymeWheelBridge? = null

    @Synchronized fun attach(context: Context, controller: CarPlayController) {
        if (owner === controller) return
        bridge?.close()
        owner = controller
        bridge = if (AirPlayPersistence.loadCarBluetoothAudio(context)) null else
            FlymeWheelBridge(context, object : FlymeWheelBridge.Host {
                override fun diagnostic(message: String) { DetailedDebugLog.record("flyme-eas", message) }
                override fun ready() = !controller.isClosed() && CarPlayBackgroundSession.active &&
                    DiPlaySessionService.liveRuntime?.currentConnection?.controller === controller
                override fun canHandleVoiceKey() = ready() && !inCall(context)
                override fun voiceKey(keyCode: Int) {
                    if (!canHandleVoiceKey()) return
                    val paired = VoiceKeyPair.shared.allowCarPlayKey(android.os.SystemClock.uptimeMillis())
                    val sent = paired && CarPlayMediaKeys.onExternalSiriKey()
                    DetailedDebugLog.record("flyme-siri", "vendor key=$keyCode deduplicated=${!paired} sent=$sent (queued, not iPhone acknowledgement)")
                }
                override fun mediaButton(index: Int) = CarPlayMediaKeys.onExternalMediaKey(index, "flyme-eas")
                override fun playback(): PlaybackSnapshot {
                    val p = controller.nowPlayingSnapshot()
                    return PlaybackSnapshot(p.title, p.artist, p.album, p.durationMillis ?: 0L,
                        p.elapsedMillis ?: 0L, p.playing, controller.matchingArtworkSnapshot(p.artworkTransferId)?.bytes)
                }
            })
    }

    @Synchronized fun detach(expected: CarPlayController) {
        if (owner !== expected) return
        bridge?.close()
        bridge = null
        owner = null
    }

    @Synchronized fun update(expected: CarPlayController) {
        if (owner === expected) bridge?.update()
    }

    @Synchronized fun allowHardware(index: Int) = bridge?.allowHardware(index) != false
    fun report() = FlymeWheelBridge.report()
}
