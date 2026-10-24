package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.VideoColorOverride
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ColorTestPlayerTest {
    @Test fun aPendingReplacementCannotReadThePreviousWorkersConfiguration() {
        val app = RuntimeEnvironment.getApplication()
        val player = ColorTestPlayer(app)
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val worker = ReflectionHelpers.getField<ExecutorService>(player, "worker")
        worker.execute { entered.countDown(); gate.await() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val previous = AndroidMediaSink()
        try {
            val csd = app.assets.open("color-test/h264_full.csd").use { it.readBytes() }
            previous.onVideoConfig(110, csd)
            player.start(ColorTestRun.cases.first { it.asset == "h264_full" }, VideoColorOverride(), surface) {}
            val old = ReflectionHelpers.getField<Any>(player, "pending")
            ReflectionHelpers.setField(player, "attachedRequest", old)
            ReflectionHelpers.setField(player, "attachedSink", previous)
            assertEquals("H264", player.evidence()?.codec)
            player.start(ColorTestRun.cases.first { it.asset == "hevc_full" }, VideoColorOverride(), surface) {}
            assertNull("a new case must not borrow the previous codec/CSD", player.evidence())
            player.stop()
            assertNull(player.evidence())
        } finally {
            player.stop(); gate.countDown(); player.close(); worker.awaitTermination(5, TimeUnit.SECONDS)
            previous.close(); previous.awaitVideoReleased(5_000)
            surface.release(); texture.release()
        }
    }
}
