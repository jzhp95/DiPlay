package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.flymeeas.FlymeWheelBridge
import com.shilapi.flymeeas.PlaybackSnapshot
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.host.R
import java.io.Closeable

/** Foreground-only standalone probe, using the same EAS bridge as the real CarPlay integration. */
internal class WheelTestController(context: Context, private val changed: (State) -> Unit) : Closeable {
    data class Event(val media: Int?, val raw: Int?, val source: String, val simulated: Boolean)
    data class State(val running: Boolean, val requestResult: Int?, val focusChange: Int?,
        val easOwned: Boolean, val received: Int, val simulated: Int, val event: Event?, val lines: List<String>)

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    @Volatile private var running = false
    @Volatile private var disposed = false
    private var request: AudioFocusRequestCompat? = null
    private var session: MediaSession? = null
    private var bridge: FlymeWheelBridge? = null
    private var requestResult: Int? = null
    private var focusChange: Int? = null
    private var received = 0
    private var simulated = 0
    private var event: Event? = null
    private val lines = ArrayDeque<String>()

    fun snapshot() = State(running, requestResult, focusChange, bridge?.ownsFocus() == true,
        received, simulated, event, lines.toList())

    fun start() {
        if (disposed || running) return
        val current = ++generation
        running = true
        requestResult = null
        focusChange = null
        note("start generation=$current")
        try {
            val audio = context.getSystemService(AudioManager::class.java)
            request = AudioFocusRequestCompat(AudioManager.AUDIOFOCUS_GAIN,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                AudioManager.OnAudioFocusChangeListener { change ->
                    if (running && generation == current) {
                        focusChange = change
                        note("Android focus change=$change")
                    }
                }, main)
            requestResult = audio?.let { request?.request(it) }
            note("Android focus request result=$requestResult")
            session = MediaSession(context, "CarPlay wheel test").apply {
                @Suppress("DEPRECATION")
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSession.Callback() {
                    private fun key(index: Int) { if (running && generation == current) emit(index, null, "Android MediaSession", false) }
                    override fun onPlay() = key(CarPlayMediaButton.PLAY)
                    override fun onPause() = key(CarPlayMediaButton.PAUSE)
                    override fun onSkipToNext() = key(CarPlayMediaButton.NEXT)
                    override fun onSkipToPrevious() = key(CarPlayMediaButton.PREVIOUS)
                    override fun onMediaButtonEvent(intent: Intent): Boolean {
                        if (!running || generation != current) return false
                        @Suppress("DEPRECATION")
                        val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                        return this@WheelTestController.key(key, "Android MediaSession")
                    }
                }, main)
                setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, context.getString(R.string.wheel_test_title)).build())
                setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
                isActive = true
            }
            bridge = FlymeWheelBridge(context, object : FlymeWheelBridge.Host {
                override fun ready() = running && generation == current && !disposed
                override fun mediaButton(index: Int) { if (ready()) emit(index, null, "Flyme EAS", false) }
                override fun playback() = PlaybackSnapshot(context.getString(R.string.wheel_test_title), "", "", 0, 0, true)
                override fun diagnostic(message: String) {
                    DetailedDebugLog.record("wheel-test/eas", "generation=$current $message")
                    if (!disposed) main.post {
                        if (!disposed) note("EAS generation=$current $message")
                    }
                }
            })
            note("listening; PLAYING is a test declaration, no audio is played")
        } catch (error: Exception) {
            note("start failed type=${error.javaClass.simpleName}")
            stop()
        }
    }

    fun stop() {
        val hadResources = running || request != null || session != null || bridge != null
        running = false
        generation++ // Invalidate focus and Binder callbacks before abandoning anything.
        bridge?.close()
        bridge = null
        session?.let { runCatching { it.isActive = false; it.release() } }
        session = null
        request?.let { focus ->
            runCatching { context.getSystemService(AudioManager::class.java)?.let(focus::abandon) }
                .onSuccess { note("Android focus abandon result=$it") }
                .onFailure { note("Android focus abandon failed type=${it.javaClass.simpleName}") }
        }
        request = null
        requestResult = null
        focusChange = null
        if (hadResources) note("stopped; EAS unregister queued; no automatic reacquire")
    }

    /** Real key input is observed only while this probe is enabled; volume keeps its normal behavior. */
    fun key(key: KeyEvent, source: String): Boolean {
        if (!running || WheelKeyLog.writesText(key.keyCode)) return false
        val media = CarPlayMediaButton.forKeyCode(key.keyCode, experimentalDiLink3Keys = true)
            ?: if (CarPlayMediaButton.opensSiri(key.keyCode) || CarPlayMediaButton.opensSiriWhileCarPlay(key.keyCode)) 6 else null
        if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
            // EAS explicit play/pause also pairs with hardware toggle. Siri has no EAS counterpart.
            if (media != null && media in 1..5 && bridge?.allowHardware(media) == false) {
                note("duplicate hardware suppressed code=${key.keyCode}")
            } else emit(media, key.keyCode, source, false)
        }
        return media != null && key.action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)
    }

    /** Never inject simulated keys into Android, another player, or the iPhone. */
    fun simulate(media: Int) = emit(media, null, context.getString(R.string.wheel_test_simulated), true)

    private fun emit(media: Int?, raw: Int?, source: String, isSimulated: Boolean) {
        if (disposed) return
        if (isSimulated) simulated++ else received++
        event = Event(media, raw, source, isSimulated)
        note("event media=$media raw=$raw source=$source simulated=$isSimulated")
    }

    private fun note(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        lines.addLast("${System.currentTimeMillis()} $safe")
        while (lines.size > 60) lines.removeFirst()
        DetailedDebugLog.record("wheel-test", safe)
        if (!disposed) changed(snapshot())
    }

    override fun close() { stop(); disposed = true; main.removeCallbacksAndMessages(null) }
}
