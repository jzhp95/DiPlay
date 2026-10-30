package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import kotlin.math.abs

/**
 * The view areas the main screen declares for one session, and the one CarPlay uses now. A fixed dock
 * declares every area once per dock edge; a square canvas for a turning screen holds a landscape and a
 * portrait screen; beside DiPlay's side panel CarPlay draws in part of the screen. The car moves CarPlay
 * between them with updateViewArea, without reconnecting.
 */
class CarPlayViewAreas private constructor(
    val areas: List<AirPlayViewArea>,
    private val slots: List<Slot>,
    initial: Int,
) {
    /** What an area is for; SIDE_PANEL is CarPlay beside DiPlay's panel and is only chosen on request. */
    enum class Kind { FULL_SCREEN, SIDE_PANEL }

    /** A full screen the canvas holds: its size and whether the screen is portrait then. */
    data class Screen(val width: Int, val height: Int, val portrait: Boolean)

    /** One area's purpose; [sixths] is CarPlay's share of its screen beside the side panel, in sixths. */
    private data class Slot(val kind: Kind, val portrait: Boolean, val sixths: Int = 6)

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

    /**
     * The area beside DiPlay's side panel on the current (or given) screen with CarPlay's share in [sixths]
     * (another share when this session has no such area), or null without a side panel.
     */
    fun sidePanel(portrait: Boolean = slots[current].portrait, sixths: Int = DEFAULT_SIDE_PANEL_SIXTHS): Int? {
        val edge = areas[current].dockEdge
        return areas.indices.firstOrNull {
            slots[it].kind == Kind.SIDE_PANEL && slots[it].portrait == portrait && areas[it].dockEdge == edge && slots[it].sixths == sixths
        } ?: index(Kind.SIDE_PANEL, portrait, edge)
    }

    /**
     * The area to lay out the canvas by: beside the side panel CarPlay draws in part of its screen, so the
     * whole screen is laid out and the panel covers the rest ([panelRect]).
     */
    fun layoutArea(index: Int): AirPlayViewArea =
        if (slots[index].kind == Kind.SIDE_PANEL) {
            index(Kind.FULL_SCREEN, slots[index].portrait, areas[index].dockEdge)?.let { areas[it] } ?: areas[index]
        } else areas[index]

    /**
     * The part of the stream DiPlay's panel covers beside side-panel area [index]: the strip of its whole
     * screen that CarPlay leaves (stream pixels), or null for other areas.
     */
    fun panelRect(index: Int): AirPlayViewArea? {
        if (slots[index].kind != Kind.SIDE_PANEL) return null
        val side = areas[index]
        val screen = layoutArea(index)
        return when {
            side.height < screen.height -> AirPlayViewArea(screen.width, screen.height - side.height,
                screen.originX, side.originY + side.height)
            side.originX > screen.originX -> AirPlayViewArea(side.originX - screen.originX, screen.height,
                screen.originX, screen.originY)
            else -> AirPlayViewArea(screen.originX + screen.width - side.originX - side.width, screen.height,
                side.originX + side.width, screen.originY)
        }
    }

    /** The area in use after moving the dock to [dockEdge], keeping the kind of area and the screen. */
    fun withDock(dockEdge: Int): Int? = index(kindOf(current), dockEdge)

    fun use(index: Int) {
        current = index.coerceIn(0, areas.lastIndex)
    }

    companion object {
        /** CarPlay's share of the screen beside the side panel, in sixths: two thirds, half, one third. */
        val SIDE_PANEL_SIXTHS = listOf(4, 3, 2)
        const val DEFAULT_SIDE_PANEL_SIXTHS = 4

        /** The declared share nearest to CarPlay's [fraction] of the screen (0..1). */
        fun nearestSidePanelSixths(fraction: Double): Int = SIDE_PANEL_SIXTHS.minByOrNull { abs(it / 6.0 - fraction) }!!

        /** One screen the size of the stream (no turning); see the general [build]. */
        fun build(
            width: Int,
            height: Int,
            dock: CarPlayDock,
            splitWindow: Pair<Float, Float>? = null,
            sidePanel: Boolean = false,
            rightHandDrive: Boolean = false,
        ): CarPlayViewAreas? =
            build(width, height, listOf(Screen(width, height, portrait = height > width)), dock,
                splitWindow = { splitWindow }, startPortrait = height > width, sidePanel = sidePanel,
                rightHandDrive = rightHandDrive)

        /**
         * The areas for a [canvasWidth] x [canvasHeight] stream holding [screens] (each at its top-left
         * corner), or null when the whole stream as one area will do (one screen, automatic dock).
         * With [sidePanel], CarPlay also gets two thirds of each screen beside a DiPlay panel, nearer
         * the driver ([rightHandDrive]).
         */
        fun build(
            canvasWidth: Int,
            canvasHeight: Int,
            screens: List<Screen>,
            dock: CarPlayDock,
            splitWindow: ((portrait: Boolean) -> Pair<Float, Float>?)? = null,
            startPortrait: Boolean = false,
            sidePanel: Boolean = false,
            rightHandDrive: Boolean = false,
        ): CarPlayViewAreas? {
            if (screens.size == 1 && dock.edge == null && !sidePanel &&
                screens[0].width == canvasWidth && screens[0].height == canvasHeight) return null
            val edges = dock.edge?.let { listOf(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, AirPlayInfoPlist.DOCK_EDGE_BOTTOM) }
                ?: listOf(null)
            val areas = mutableListOf<AirPlayViewArea>()
            val slots = mutableListOf<Slot>()
            for (screen in screens) for (edge in edges) {
                areas += AirPlayViewArea(screen.width, screen.height, dockEdge = edge)
                slots += Slot(Kind.FULL_SCREEN, screen.portrait)
            }
            if (sidePanel) for (screen in screens) for (sixths in SIDE_PANEL_SIXTHS) {
                // CarPlay keeps two thirds, half or one third: the driver's side of a landscape screen (the panel
                // on the passenger's side) or the top of a portrait one (the panel at the bottom).
                val width = if (screen.portrait) screen.width else (screen.width * sixths / 6) and 1.inv()
                val height = if (screen.portrait) (screen.height * sixths / 6) and 1.inv() else screen.height
                val originX = if (rightHandDrive) screen.width - width else 0
                for (edge in edges) {
                    areas += AirPlayViewArea(width, height, originX = originX, dockEdge = edge)
                    slots += Slot(Kind.SIDE_PANEL, screen.portrait, sixths)
                }
            }
            val initial = areas.indices.firstOrNull {
                slots[it].kind == Kind.FULL_SCREEN && slots[it].portrait == startPortrait && areas[it].dockEdge == dock.edge
            } ?: areas.indices.first { slots[it].kind == Kind.FULL_SCREEN && areas[it].dockEdge == dock.edge }
            return CarPlayViewAreas(areas, slots, initial)
        }
    }
}
