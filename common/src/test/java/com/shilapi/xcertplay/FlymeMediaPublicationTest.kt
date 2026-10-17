package com.shilapi.xcertplay

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.shilapi.flymeeas.EasAdapter
import com.shilapi.flymeeas.PlaybackSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class FlymeMediaPublicationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private class Sink : EasAdapter.Sink {
        val logs = mutableListOf<String>()
        override fun event(action: Int, raw: Int, source: String?) {}
        override fun status(stage: String, result: String) { logs += "$stage=$result" }
        override fun diagnostic(message: String) { logs += message }
        override fun registered() {}
        override fun focus(packageName: String?) {}
        override fun disconnected() {}
        override fun canDispatch(action: Int) = true
        override fun shouldRequestFocus() = false
        override fun requestFinished() {}
    }
    private fun info(adapter: EasAdapter): IBinder {
        val type = Class.forName("com.shilapi.flymeeas.EasAdapter\$PlaybackInfo")
        return type.getDeclaredConstructor(EasAdapter::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(adapter, 36) as IBinder
    }
    private fun <T> read(info: IBinder, code: Int, decode: (Parcel) -> T): T {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("ecarx.xsf.mediacenter.IMusicPlaybackInfo")
            assertTrue(info.transact(code, data, reply, 0)); reply.readException()
            return decode(reply)
        } finally { data.recycle(); reply.recycle() }
    }
    private fun withAdapter(test: (EasAdapter, Sink) -> Unit) {
        val sink = Sink(); val adapter = EasAdapter(context, sink)
        try { test(adapter, sink) } finally { adapter.close() }
    }
    @Test fun trackIdentityChangesWithTrackAndLyricsAreProperStrings() = withAdapter { adapter, _ ->
        ReflectionHelpers.setField(adapter, "playback", PlaybackSnapshot("曲目一", "歌手", "专辑", 120000, 5000, true))
        val first = info(adapter)
        val id = read(first, 24) { it.readString() }
        assertFalse("media UUID must identify the song", id.isNullOrBlank())
        assertEquals("", read(first, 14) { it.readString() })
        assertEquals(-1, read(first, 8) { it.readInt() })
        assertEquals(0, read(first, 34) { it.readInt() })
        ReflectionHelpers.setField(adapter, "playback", PlaybackSnapshot("曲目二", "歌手", "专辑", 120000, 0, true))
        assertNotEquals(id, read(info(adapter), 24) { it.readString() })
    }
    @Test fun publishedBinderRemainsAnImmutableSnapshot() = withAdapter { adapter, _ ->
        ReflectionHelpers.setField(adapter, "playback", PlaybackSnapshot("原曲目", "歌手", "专辑", 120000, 5000, true))
        val published = info(adapter)
        ReflectionHelpers.setField(adapter, "playback", PlaybackSnapshot("新曲目", "歌手", "专辑", 200000, 0, false))
        assertEquals("原曲目", read(published, 2) { it.readString() })
        assertEquals(120000L, read(published, 7) { it.readLong() })
        assertEquals(1, read(published, 11) { it.readInt() })
        adapter.close()
        for (code in listOf(2, 3, 4, 24)) assertEquals("", read(published, code) { it.readString() })
        assertEquals(0L, read(published, 7) { it.readLong() })
        assertEquals(0, read(published, 11) { it.readInt() })
    }
    @Test fun confirmingOurFocusRepublishesAfterInitialMetadataWasTooEarly() = withAdapter { adapter, _ ->
        val updates = mutableListOf<Int>()
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("ecarx.xsf.mediacenter.IMediaCenterSvc")
                reply!!.writeNoException()
                when (code) {
                    42 -> reply.writeString(context.packageName)
                    6 -> { updates += code; reply.writeInt(1) }
                    10, 11 -> {}
                    4 -> reply.writeInt(1)
                    else -> fail("unexpected transaction $code")
                }
                return true
            }
        }
        ReflectionHelpers.setField(adapter, "media", remote)
        ReflectionHelpers.setField(adapter, "token", Binder())
        ReflectionHelpers.setField(adapter, "sourceType", 36)
        ReflectionHelpers.setField(adapter, "playbackInfo", info(adapter))
        ReflectionHelpers.setField(adapter, "playback", PlaybackSnapshot("歌曲", "歌手", "专辑", 120000, 5000, true))
        val query = EasAdapter::class.java.getDeclaredMethod("query", Int::class.javaPrimitiveType).apply { isAccessible = true }
        query.invoke(adapter, 0)
        assertTrue("confirmed focus must republish retained metadata", updates.isNotEmpty())
        val before = updates.size
        adapter.close()
        query.invoke(adapter, 0)
        ShadowLooper.idleMainLooper()
        assertEquals("closed adapters must not publish", before, updates.size)
    }
}
