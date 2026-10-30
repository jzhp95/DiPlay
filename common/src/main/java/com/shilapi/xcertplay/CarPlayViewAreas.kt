package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea

/**
 * The view areas the main screen declares for one session, and the one CarPlay uses now. A fixed dock
 * declares every area once per dock edge; a square canvas for a turning screen holds a landscape and a
 * portrait screen. The car moves CarPlay between them with updateViewArea, without reconnecting.
 */
class CarPlayViewAreas private constructor(
    val areas: List<AirPlayViewArea>,
    private val slots: List<Slot>,
    initial: Int,
) {
    /** What an area is for. */
    enum class Kind { FULL_SCREEN }

    /** A full screen the canvas holds: its size and whether the screen is portrait then. */
    data class Screen(val width: Int, val height: Int, val portrait: Boolean)

    /** One area's purpose. */
    private data class Slot(val kind: Kind, val portrait: Boolean)

    /** The area CarPlay uses now; shared by the session's host and its settings. */
    @Volatile var current: Int = initial
        private set

    /** True when the canvas holds both a landscape and a portrait screen. */
    val turnsWithScreen: Boolean = slots.any { it.portrait } && slots.any { !it.portrait }

    fun kindOf(index: Int): Kind = slots[index].kind

    /** The area of [kind] with [dockEdge] (or the edge-less one) on the current screen, or null. */
    fun index(kind: Kind, dockEdge: Int?): Int? = index(kind, slots[current].portrait, dockEdge)

    fun index(kind: Kind, portrait: Boolean, dockEdge: Int?): Int? =
        areas.indices.firstOrNull { slots[it].kind == kind && slots[it].portrait == portrait && areas[it].dockEdge == dockEdge }

    /**
     * The area for DiPlay's window on a [portrait] or landscape screen, keeping the dock edge.
     * Null when this session has no area for that screen (the caller reconnects).
     */
    fun indexFor(width: Int, height: Int, splitScreen: Boolean = false, portrait: Boolean = slots[current].portrait): Int? {
        val edge = areas[current].dockEdge
        return index(Kind.FULL_SCREEN, portrait, edge)
    }

    /** The area in use after moving the dock to [dockEdge], keeping the kind of area and the screen. */
    fun withDock(dockEdge: Int): Int? = index(kindOf(current), dockEdge)

    fun use(index: Int) {
        current = index.coerceIn(0, areas.lastIndex)
    }

    companion object {
        /** One screen the size of the stream (no turning); see the general [build]. */
        fun build(
            width: Int,
            height: Int,
            dock: CarPlayDock,
        ): CarPlayViewAreas? =
            build(width, height, listOf(Screen(width, height, portrait = height > width)), dock,
                startPortrait = height > width)

        /**
         * The areas for a [canvasWidth] x [canvasHeight] stream holding [screens] (each at its top-left
         * corner), or null when the whole stream as one area will do (one screen, automatic dock).
         */
        fun build(
            canvasWidth: Int,
            canvasHeight: Int,
            screens: List<Screen>,
            dock: CarPlayDock,
            splitWindow: ((portrait: Boolean) -> Pair<Float, Float>?)? = null,
            startPortrait: Boolean = false,
        ): CarPlayViewAreas? {
            if (screens.size == 1 && dock.edge == null &&
                screens[0].width == canvasWidth && screens[0].height == canvasHeight) return null
            val edges = dock.edge?.let { listOf(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, AirPlayInfoPlist.DOCK_EDGE_BOTTOM) }
                ?: listOf(null)
            val areas = mutableListOf<AirPlayViewArea>()
            val slots = mutableListOf<Slot>()
            for (screen in screens) for (edge in edges) {
                areas += AirPlayViewArea(screen.width, screen.height, dockEdge = edge)
                slots += Slot(Kind.FULL_SCREEN, screen.portrait)
            }
            val initial = areas.indices.firstOrNull {
                slots[it].kind == Kind.FULL_SCREEN && slots[it].portrait == startPortrait && areas[it].dockEdge == dock.edge
            } ?: areas.indices.first { slots[it].kind == Kind.FULL_SCREEN && areas[it].dockEdge == dock.edge }
            return CarPlayViewAreas(areas, slots, initial)
        }
    }
}
