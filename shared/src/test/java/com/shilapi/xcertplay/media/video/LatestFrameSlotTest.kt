package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestFrameSlotTest {

    @Test
    fun replacingPendingReturnsOnlyReplacedLease() {
        val slot = LatestFrameSlot()
        val a = testFrame()
        val b = testFrame()
        val c = testFrame()

        // 1. 发布 A 并由渲染线程取走 (进入 in-flight)
        assertTrue(slot.publish(a))
        assertSame(a, slot.take())
        assertEquals(0, a.releaseAttempts)

        // 2. 当 A 仍在进行中时，连续发布 B 和 C
        assertTrue(slot.publish(b))
        assertEquals(0, b.releaseAttempts)

        assertTrue(slot.publish(c))
        // B 被 C 替换，B 必须已经被槽主动释放一次
        assertEquals(1, b.releaseAttempts)
        assertEquals(0, a.releaseAttempts)
        assertEquals(0, c.releaseAttempts)

        // 3. 在 A 尚未 finish 之前，take() 无法获取 C (不能并发双帧进行中)
        assertNull(slot.take())

        // 4. A 结束呈现生命周期
        slot.finish(a)
        assertEquals(1, a.releaseAttempts)

        // 5. 现在可以取走最新的 C
        assertSame(c, slot.take())
        slot.finish(c)
        assertEquals(1, c.releaseAttempts)

        slot.close()
    }

    @Test
    fun earlyReleaseInRenderAllowsIdempotentFinish() {
        val slot = LatestFrameSlot()
        val frame = testFrame()

        slot.publish(frame)
        assertSame(frame, slot.take())

        // 模拟渲染线程在纹理上传后提前释放底层 Codec Image
        frame.releaseOnce()
        assertEquals(1, frame.releaseAttempts)

        // 在 finally 块中调用 finish 不会重复执行底层归还
        slot.finish(frame)
        assertEquals(2, frame.releaseAttempts) // releaseOnce 幂等调用了两次，但底层释放仅一次
        slot.close()
    }

    @Test
    fun rejectedPublishAfterCloseKeepsCallerOwnership() {
        val slot = LatestFrameSlot()
        val frame = testFrame()

        slot.close()

        val published = slot.publish(frame)
        assertFalse("Publish must be rejected after close", published)
        assertEquals("Slot must not touch caller lease on rejection", 0, frame.releaseAttempts)

        // 调用方自行清理
        frame.releaseOnce()
        assertEquals(1, frame.releaseAttempts)
    }

    @Test
    fun closingWithPendingFrameReleasesIt() {
        val slot = LatestFrameSlot()
        val pending = testFrame()

        slot.publish(pending)
        assertEquals(0, pending.releaseAttempts)

        // 关闭槽时，未被取走的 pending 帧必须被清理
        slot.close()
        assertEquals(1, pending.releaseAttempts)
    }
}
