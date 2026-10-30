package com.shilapi.xcertplay
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AutomaticVideoCleanupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var release: (() -> Unit)? = null
    @Before fun setup() { context.getSharedPreferences("automatic_video_suite_v1", 0).edit().clear().commit() }
    @After fun cleanup() {
        release?.invoke(); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AutomaticVideoCleanup.blocked())
        AutomaticVideoSuite.stop(context, "cleanup")
        context.getSharedPreferences("automatic_video_suite_v1", 0).edit().clear().commit()
    }
    @Test fun acknowledgementClearsTheCrossPageBarrierBeforeAdvancing() {
        val id = java.util.UUID.randomUUID().toString(); var done = 0
        AutomaticVideoCleanup.await(context, id, { release = it }, { done++ })
        assertTrue(AutomaticVideoCleanup.blocked())
        assertEquals(0, done)
        release!!.invoke(); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AutomaticVideoCleanup.blocked())
        assertFalse(AutomaticVideoCleanup.blocked())
        assertEquals(1, done)
    }
    @Test fun stalledReleaseInterruptsAndBlocksNewConnectionsUntilActualAcknowledgement() {
        val id = java.util.UUID.randomUUID().toString()
        AutomaticVideoCleanup.await(context, id, { release = it }, {})
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertTrue(AutomaticVideoCleanup.blocked())
        assertThrows(IllegalStateException::class.java) { AutomaticVideoCleanup.await(context,"new",{}, {}) }
        release!!.invoke(); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AutomaticVideoCleanup.blocked())
    }
}
