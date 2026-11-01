package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTouchMapperTest {

    @Test
    fun mapsCoordinatesWithAspectLetterboxing() {
        // CarPlay is 1280x720 (16:9), View is ultra-wide 1920x720
        // Scale = 1.0, active area is 1280x720 centered, offsetX = (1920 - 1280) / 2 = 320
        val geometry = DisplayGeometry(
            viewWidth = 1920,
            viewHeight = 720,
            carPlayWidth = 1280,
            carPlayHeight = 720
        )

        // Point at center of view (960, 360) -> should map to center of CarPlay (640, 360)
        val centerEvent = TouchEventSnapshot(
            action = TouchAction.MOVE,
            pointers = listOf(TouchPoint(0, 960f, 360f)),
            timestampNs = 1000L
        )

        val result = VideoTouchMapper.map(centerEvent, geometry)
        assertTrue(result.isInsideBounds)
        assertEquals(640f, result.mappedPointers[0].x, 0.01f)
        assertEquals(360f, result.mappedPointers[0].y, 0.01f)

        // Point in left black bar (100, 360) -> outside bounds, clamped to 0
        val leftBarEvent = TouchEventSnapshot(
            action = TouchAction.MOVE,
            pointers = listOf(TouchPoint(0, 100f, 360f)),
            timestampNs = 1000L
        )

        val leftResult = VideoTouchMapper.map(leftBarEvent, geometry)
        assertFalse(leftResult.isInsideBounds)
        assertEquals(0f, leftResult.mappedPointers[0].x, 0.01f)
    }

    @Test
    fun mapsMultiTouchPointersCorrectly() {
        val geometry = DisplayGeometry(1280, 720, 1280, 720)
        val multiEvent = TouchEventSnapshot(
            action = TouchAction.MOVE,
            pointers = listOf(TouchPoint(0, 100f, 200f), TouchPoint(1, 300f, 400f)),
            timestampNs = 1000L
        )

        val result = VideoTouchMapper.map(multiEvent, geometry)
        assertEquals(2, result.mappedPointers.size)
        assertEquals(100f, result.mappedPointers[0].x, 0.01f)
        assertEquals(300f, result.mappedPointers[1].x, 0.01f)
    }
}
