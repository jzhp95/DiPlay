package com.shilapi.xcertplay.media.video

/**
 * 触控动作类型。
 */
enum class TouchAction {
    DOWN,
    MOVE,
    UP,
    CANCEL
}

/**
 * 触控点坐标数据。
 */
data class TouchPoint(
    val id: Int,
    val x: Float,
    val y: Float
)

/**
 * 原始触控事件快照。
 */
data class TouchEventSnapshot(
    val action: TouchAction,
    val pointers: List<TouchPoint>,
    val timestampNs: Long
)

/**
 * 显示几何配置。
 */
data class DisplayGeometry(
    val viewWidth: Int,
    val viewHeight: Int,
    val carPlayWidth: Int,
    val carPlayHeight: Int,
    val rotationDegrees: Int = 0
)

/**
 * 触控映射结果。
 */
data class TouchMapResult(
    val action: TouchAction,
    val mappedPointers: List<TouchPoint>,
    val isInsideBounds: Boolean
)

/**
 * 独立触控坐标映射器 (VideoTouchMapper)。
 * 纯数学变换，按黑边 letterbox、裁剪与旋转将 Android View 物理坐标映射至 CarPlay 协议坐标 [0, carPlayWidth] x [0, carPlayHeight]。
 */
object VideoTouchMapper {

    fun map(event: TouchEventSnapshot, geometry: DisplayGeometry): TouchMapResult {
        if (geometry.viewWidth <= 0 || geometry.viewHeight <= 0 || geometry.carPlayWidth <= 0 || geometry.carPlayHeight <= 0) {
            return TouchMapResult(event.action, emptyList(), false)
        }

        // 计算等比缩放与黑边偏移
        val scaleX = geometry.viewWidth.toFloat() / geometry.carPlayWidth.toFloat()
        val scaleY = geometry.viewHeight.toFloat() / geometry.carPlayHeight.toFloat()
        val scale = Math.min(scaleX, scaleY)

        val activeWidth = geometry.carPlayWidth * scale
        val activeHeight = geometry.carPlayHeight * scale

        val offsetX = (geometry.viewWidth - activeWidth) / 2f
        val offsetY = (geometry.viewHeight - activeHeight) / 2f

        var allInside = true
        val mapped = event.pointers.map { pt ->
            // 减去黑边偏移并缩放回 CarPlay 原始分辨率
            val normX = (pt.x - offsetX) / scale
            val normY = (pt.y - offsetY) / scale

            val inside = normX in 0f..geometry.carPlayWidth.toFloat() && normY in 0f..geometry.carPlayHeight.toFloat()
            if (!inside) allInside = false

            // 限制在协议边界内
            val clampedX = Math.max(0f, Math.min(geometry.carPlayWidth.toFloat(), normX))
            val clampedY = Math.max(0f, Math.min(geometry.carPlayHeight.toFloat(), normY))

            TouchPoint(pt.id, clampedX, clampedY)
        }

        return TouchMapResult(event.action, mapped, allInside)
    }
}
