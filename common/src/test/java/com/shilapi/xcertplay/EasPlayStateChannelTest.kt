package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.shilapi.flymeeas.EasAdapter
import com.shilapi.flymeeas.EasPlayStateChannel
import com.shilapi.flymeeas.PlaybackSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fakes only the unavailable OEM service; exercises real client/Parcel code on API28. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class EasPlayStateChannelTest {
    private val component = ComponentName("com.ecarx.sdk.openapi", "Framework")
    private class BindingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val bindings = mutableListOf<ServiceConnection>()
        val intents = mutableListOf<Intent>()
        val unbound = mutableListOf<ServiceConnection>()
        var accept = true
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            intents += intent
            if (accept) bindings += conn
            return accept
        }
        override fun unbindService(conn: ServiceConnection) { unbound += conn }
    }
    private class Framework : Binder() {
        val states = mutableListOf<Int>()
        val packages = mutableListOf<String>()
        var inits = 0
        var resultCode = 200
        var supported = true
        var present = true
        var duringCall: (() -> Unit)? = null
        var applied: ((Int) -> Unit)? = null
        var descriptorReads = 0
        override fun getInterfaceDescriptor(): String { descriptorReads++; error("unprotected descriptor query") }
        init { attachInterface(null, "com.ecarx.eas.framework.sdk.IEASFrameworkService") }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface("com.ecarx.eas.framework.sdk.IEASFrameworkService")
            if (!supported) return false
            reply!!.writeNoException()
            when (code) {
                1 -> {
                    assertArrayEquals(arrayOf("mediacenter"), data.createStringArray())
                    assertEquals(0, data.dataAvail()); inits++
                }
                4 -> {
                    assertEquals(1, data.readInt())
                    assertEquals("mediacenter", data.readString())
                    assertEquals("MediaCenterAPI", data.readString())
                    assertEquals("updatePlayState", data.readString())
                    val json = JSONObject(String(data.createByteArray()!!, Charsets.UTF_8))
                    assertEquals(setOf("packageName", "playState", "displayId"), json.keys().asSequence().toSet())
                    assertEquals(0, json.getInt("displayId"))
                    packages += json.getString("packageName"); states += json.getInt("playState")
                    assertArrayEquals(byteArrayOf(), data.createByteArray()); assertEquals(0, data.dataAvail())
                    duringCall?.invoke()
                    applied?.invoke(json.getInt("playState"))
                    reply.writeInt(if (present) 1 else 0)
                    if (present) {
                        reply.writeInt(resultCode); reply.writeString("fixture")
                        reply.writeByteArray(byteArrayOf()); reply.writeInt(0)
                    }
                }
                else -> fail("unexpected transaction $code; state channel must not register/request focus")
            }
            return true
        }
    }
    private fun worker(channel: EasPlayStateChannel) = ReflectionHelpers.getField<Handler>(channel, "worker")
    private fun drain(channel: EasPlayStateChannel) {
        val looper = worker(channel).looper
        fun awaitStopped() {
            looper.thread.join(5000)
            assertFalse("closed channel must terminate its worker", looper.thread.isAlive)
        }
        if (ReflectionHelpers.getField<Boolean>(channel, "closed")) { awaitStopped(); return }
        try { shadowOf(looper).idle() } catch (e: IllegalStateException) {
            // close can finish while idle is being queued; verify actual termination instead.
            if (!ReflectionHelpers.getField<Boolean>(channel, "closed")) throw e
            awaitStopped()
        }
    }
    private fun advance(channel: EasPlayStateChannel, millis: Long) {
        shadowOf(worker(channel).looper).idleFor(Duration.ofMillis(millis))
    }
    private fun withChannel(block: (EasPlayStateChannel, BindingContext, MutableList<String>) -> Unit) {
        val context = BindingContext(); val logs = mutableListOf<String>()
        val channel = EasPlayStateChannel(context) { logs += it }
        try { block(channel, context, logs) } finally { channel.close(); drain(channel) }
    }
    private fun connect(channel: EasPlayStateChannel, context: BindingContext, remote: Framework = Framework()): Framework {
        channel.start(); drain(channel)
        context.bindings.last().onServiceConnected(component, remote); drain(channel)
        return remote
    }
    @Test fun delayedFirstConnectionSendsLatestThenPlayPauseResumeWithoutDuplicateProgress() = withChannel { c, ctx, _ ->
        c.update(true); c.update(false); c.update(true)
        val remote = connect(c, ctx)
        assertEquals(listOf(1), remote.states)
        assertEquals(listOf(ctx.packageName), remote.packages)
        assertEquals(1, remote.inits)
        assertEquals(0, remote.descriptorReads)
        assertEquals("com.ecarx.easframework.intent.action.EASFRAMEWORK", ctx.intents.single().action)
        assertEquals("com.ecarx.sdk.openapi", ctx.intents.single().`package`)
        c.update(true); drain(c); assertEquals(listOf(1), remote.states)
        c.update(false); drain(c); c.update(true); drain(c)
        assertEquals(listOf(1, 0, 1), remote.states)
    }
    @Test fun serviceReconnectReplaysLatestStateEvenIfItWasAlreadyAccepted() = withChannel { c, ctx, _ ->
        c.update(true); val first = connect(c, ctx)
        val old = ctx.bindings.single()
        old.onServiceDisconnected(component); drain(c)
        c.update(false); drain(c); advance(c, 1000)
        val second = Framework(); ctx.bindings.last().onServiceConnected(component, second); drain(c)
        assertEquals(listOf(1), first.states); assertEquals(listOf(0), second.states)
        old.onServiceConnected(component, first); drain(c)
        assertEquals(1, first.inits)
        assertTrue(ctx.unbound.contains(old))
    }
    @Test fun failedBindingIsRetriedAndLatestStateIsRetained() = withChannel { c, ctx, logs ->
        ctx.accept = false; c.update(true); c.start(); drain(c)
        assertTrue(logs.any { "bindService=false" in it })
        ctx.accept = true; advance(c, 1000)
        val remote = Framework(); ctx.bindings.single().onServiceConnected(component, remote); drain(c)
        assertEquals(listOf(1), remote.states)
    }
    @Test fun deniedNotificationIsNotDeduplicatedAsSuccessAndRetriesOnlyItsOwnChannel() = withChannel { c, ctx, logs ->
        c.update(true); val denied = Framework().apply { resultCode = 403 }; connect(c, ctx, denied)
        assertTrue(logs.any { "code=403" in it })
        assertFalse(logs.any { "accepted code=200" in it })
        advance(c, 1000)
        val accepted = Framework(); ctx.bindings.last().onServiceConnected(component, accepted); drain(c)
        assertEquals(listOf(1), accepted.states)
        assertTrue(logs.any { "accepted code=200 playing=true" in it })
    }
    @Test fun unsupportedInitAndNullReturnAreReportedWithoutFalseSuccess() = withChannel { c, ctx, logs ->
        connect(c, ctx, Framework().apply { supported = false })
        assertTrue(logs.any { "初始化失败" in it })
        advance(c, 1000)
        val missing = Framework().apply { present = false }
        ctx.bindings.last().onServiceConnected(component, missing); drain(c)
        assertTrue(logs.any { "code=-1" in it })
        assertFalse(logs.any { "accepted code=200" in it })
    }
    @Test fun bindingTimeoutRetriesAndLateCallbackCannotPublish() = withChannel { c, ctx, logs ->
        c.start(); drain(c); val old = ctx.bindings.single()
        advance(c, 5000); assertTrue(logs.any { "连接超时" in it })
        val late = Framework(); old.onServiceConnected(component, late); drain(c)
        assertEquals(0, late.inits)
        advance(c, 1000); assertEquals(2, ctx.bindings.size)
    }
    @Test fun closedChannelSendsFinalPausedAndIgnoresUpdatesOrLateCallbacks() = withChannel { c, ctx, _ ->
        c.update(true); val remote = connect(c, ctx); val old = ctx.bindings.single()
        c.close(); c.update(true); c.start(); drain(c)
        assertEquals(listOf(1, 0), remote.states)
        old.onServiceConnected(component, remote)
        old.onBindingDied(component); drain(c)
        assertEquals(1, remote.inits); assertEquals(listOf(1, 0), remote.states)
        assertEquals(1, ctx.intents.size); assertEquals(1, ctx.unbound.size)
    }
    @Test fun closingBeforeConnectionDoesNotBindJustToSendStop() = withChannel { c, ctx, logs ->
        c.update(true); c.close(); c.start(); drain(c)
        assertTrue(ctx.intents.isEmpty()); assertTrue(logs.any { "停止通知未送达" in it })
    }
    @Test fun timeoutInvalidatesLateSuccessAndRetainsLatestForRetry() = withChannel { c, ctx, logs ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        c.update(true); c.start(); drain(c)
        val slow = Framework().apply {
            duringCall = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        }
        ctx.bindings.single().onServiceConnected(component, slow)
        try {
            assertTrue("OEM state call must be in flight", entered.await(10, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertTrue(logs.any { "Binder超时" in it })
        } finally { release.countDown() }
        drain(c)
        assertFalse(logs.any { "accepted code=200" in it })
        c.update(false); drain(c); advance(c, 1000)
        val next = Framework(); ctx.bindings.last().onServiceConnected(component, next); drain(c)
        assertEquals(listOf(0), next.states)
    }
    @Test fun oldQueuedStopCannotOverwriteReplacementSession() = withChannel { old, oldCtx, _ ->
        old.update(true); val oldRemote = connect(old, oldCtx)
        var next: EasPlayStateChannel? = null
        var nextRemote: Framework? = null
        oldRemote.duringCall = {
            old.close()
            val ctx = BindingContext(); next = EasPlayStateChannel(ctx) {}
            next!!.update(true); nextRemote = connect(next!!, ctx)
        }
        try {
            old.update(false); drain(old); drain(next!!)
            assertEquals(listOf(1, 0), oldRemote.states)
            assertTrue(nextRemote!!.states.all { it == 1 })
        } finally { next?.close(); next?.let { drain(it) } }
    }
    @Test fun oldStopAlreadyInFlightCausesNewSessionToReassertLatestState() = withChannel { old, oldCtx, _ ->
        val applied = mutableListOf<Int>()
        old.update(true); val oldRemote = Framework().apply { this.applied = { applied += it } }
        connect(old, oldCtx, oldRemote)
        var next: EasPlayStateChannel? = null
        var nextRemote: Framework? = null
        oldRemote.duringCall = {
            val ctx = BindingContext()
            next = EasPlayStateChannel(ctx) {}
            next!!.update(true)
            nextRemote = Framework().apply { this.applied = { applied += it } }
            connect(next!!, ctx, nextRemote!!)
        }
        try {
            old.close(); drain(old); drain(next!!)
            assertEquals(listOf(1, 1, 0, 1), applied)
            assertEquals(listOf(1, 1), nextRemote!!.states)
        } finally { next?.close(); next?.let { drain(it) } }
    }
    @Test fun allSessionsStoppedAfterOldTrueArrivesLateMustEndPaused() = withChannel { old, oldCtx, _ ->
        val applied = mutableListOf<Int>()
        val remote = Framework().apply { this.applied = { applied += it } }
        connect(old, oldCtx, remote)
        var once = false
        remote.duringCall = {
            if (!once) {
                once = true; old.close()
                val ctx = BindingContext(); val next = EasPlayStateChannel(ctx) {}
                val nextRemote = Framework().apply { this.applied = { applied += it } }
                try {
                    next.update(true); connect(next, ctx, nextRemote)
                } finally { next.close(); drain(next) }
            }
        }
        old.update(true); drain(old)
        assertEquals(listOf(0, 1, 0, 1, 0), applied)
    }
    @Test fun focusOrSourceRefreshReassertsEvenAnAlreadyAcceptedState() = withChannel { c, ctx, _ ->
        c.update(true); val remote = connect(c, ctx)
        c.refresh(); drain(c)
        assertEquals(listOf(1, 1), remote.states)
    }
    @Test fun adapterFeedsActualSnapshotAndCloseStopsStateChannel() {
        val ctx = BindingContext()
        val adapter = EasAdapter(ctx, object : EasAdapter.Sink {
            override fun event(action: Int, raw: Int, source: String?) {}
            override fun status(stage: String?, result: String?) {}
            override fun diagnostic(message: String?) {}
            override fun registered() {}
            override fun focus(packageName: String?) {}
            override fun disconnected() {}
            override fun canDispatch(action: Int) = false
            override fun shouldRequestFocus() = false
            override fun requestFinished() {}
        })
        val channel = ReflectionHelpers.getField<EasPlayStateChannel>(adapter, "playStateChannel")
        try {
            adapter.updatePlayback(PlaybackSnapshot("曲目", "歌手", "", 1000, 0, true))
            val remote = connect(channel, ctx)
            assertEquals(listOf(1), remote.states)
            adapter.updatePlayback(PlaybackSnapshot("曲目", "歌手", "", 1000, 0, false)); drain(channel)
            assertEquals(listOf(1, 0), remote.states)
            adapter.close(); drain(channel)
            assertEquals(listOf(1, 0, 0), remote.states)
        } finally { adapter.close(); drain(channel) }
    }
}
