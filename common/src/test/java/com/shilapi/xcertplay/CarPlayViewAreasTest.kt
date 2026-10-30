package com.shilapi.xcertplay

import com.shilapi.xcertplay.CarPlayViewAreas.Kind
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_BOTTOM
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayViewAreasTest {
    @Test
    fun automaticDockWithoutExtraAreasNeedsNoAreas() {
        assertNull(CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC))
    }

    @Test
    fun fixedDockDeclaresTheScreenOncePerEdgeAndStartsOnTheChosenOne() {
        val driver = CarPlayViewAreas.build(2560, 1440, CarPlayDock.DRIVER_SIDE)!!
        assertEquals(listOf(DOCK_EDGE_DRIVER_SIDE, DOCK_EDGE_BOTTOM), driver.areas.map { it.dockEdge })
        assertEquals(0, driver.current)
        val bottom = CarPlayViewAreas.build(2560, 1440, CarPlayDock.BOTTOM)!!
        assertEquals(1, bottom.current)
        assertEquals(0, bottom.withDock(DOCK_EDGE_DRIVER_SIDE))
    }

    private fun turning(dock: CarPlayDock = CarPlayDock.AUTOMATIC, startPortrait: Boolean = false) =
        CarPlayViewAreas.build(2560, 2560, listOf(
            CarPlayViewAreas.Screen(2560, 1440, portrait = false),
            CarPlayViewAreas.Screen(1440, 2560, portrait = true),
        ), dock, startPortrait = startPortrait)!!

    @Test
    fun aSquareCanvasHoldsBothScreensAndStartsInTheWindowsOne() {
        val areas = turning()
        assertEquals(true, areas.turnsWithScreen)
        assertEquals(listOf(2560 to 1440, 1440 to 2560), areas.areas.map { it.width to it.height })
        assertEquals(0, areas.current)
        assertEquals(1, turning(startPortrait = true).current)
    }

    @Test
    fun turningPicksTheOtherScreen() {
        val areas = turning()
        assertEquals(0, areas.indexFor(2560, 1440, portrait = false))
        assertEquals(1, areas.indexFor(1440, 2560, portrait = true))
    }

    @Test
    fun aPlainCanvasHasNoAreaForTheOtherScreen() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.BOTTOM)!!
        assertEquals(false, areas.turnsWithScreen)
        assertNull(areas.indexFor(1440, 2560, portrait = true))
    }

    @Test
    fun aSquareCanvasNeedsAreasEvenWithAnAutomaticDock() {
        val areas = turning()
        assertEquals(listOf<Int?>(null, null), areas.areas.map { it.dockEdge })
    }

    @Test
    fun theSidePanelSitsOnTheRightOrAtTheBottomOfCarPlaysTwoThirds() {
        val areas = CarPlayViewAreas.build(2560, 2560, listOf(
            CarPlayViewAreas.Screen(2560, 1440, portrait = false),
            CarPlayViewAreas.Screen(1440, 2560, portrait = true),
        ), CarPlayDock.AUTOMATIC, startPortrait = false, sidePanel = true)!!
        val landscape = areas.sidePanel(portrait = false)!!
        assertEquals(Kind.SIDE_PANEL, areas.kindOf(landscape))
        assertEquals(1706 to 1440, areas.areas[landscape].let { it.width to it.height })
        assertEquals(0, areas.areas[landscape].originX)
        val portrait = areas.sidePanel(portrait = true)!!
        assertEquals(1440 to 1706, areas.areas[portrait].let { it.width to it.height })
        assertEquals(2560 to 1440, areas.layoutArea(landscape).let { it.width to it.height })
        assertEquals(1440 to 2560, areas.layoutArea(portrait).let { it.width to it.height })
        assertEquals(AirPlayViewArea(854, 1440, 1706, 0), areas.panelRect(landscape))
        assertEquals(AirPlayViewArea(1440, 854, 0, 1706), areas.panelRect(portrait))
        assertNull(areas.panelRect(areas.current))
    }

    @Test
    fun aRightHandDriveCarKeepsCarPlayOnTheRightWithThePanelOnTheLeft() {
        val areas = CarPlayViewAreas.build(2560, 2560, listOf(
            CarPlayViewAreas.Screen(2560, 1440, portrait = false),
            CarPlayViewAreas.Screen(1440, 2560, portrait = true),
        ), CarPlayDock.AUTOMATIC, startPortrait = false, sidePanel = true, rightHandDrive = true)!!
        val landscape = areas.sidePanel(portrait = false)!!
        assertEquals(854, areas.areas[landscape].originX)
        assertEquals(AirPlayViewArea(854, 1440, 0, 0), areas.panelRect(landscape))
        assertEquals(AirPlayViewArea(1440, 854, 0, 1706), areas.panelRect(areas.sidePanel(portrait = true)!!))
    }

    @Test
    fun theSidePanelLeavesCarPlayTwoThirdsHalfOrOneThird() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC, sidePanel = true)!!
        assertEquals(listOf(1706, 1280, 852), CarPlayViewAreas.SIDE_PANEL_SIXTHS.map { areas.areas[areas.sidePanel(sixths = it)!!].width })
        assertEquals(AirPlayViewArea(1708, 1440, 852, 0), areas.panelRect(areas.sidePanel(sixths = 2)!!))
        assertEquals(areas.sidePanel(sixths = 4), areas.sidePanel())
    }

    @Test
    fun releasingThePanelEdgeSnapsCarPlayToTheNearestShare() {
        assertEquals(4, CarPlayViewAreas.nearestSidePanelSixths(0.8))
        assertEquals(3, CarPlayViewAreas.nearestSidePanelSixths(0.55))
        assertEquals(2, CarPlayViewAreas.nearestSidePanelSixths(0.2))
    }

    @Test
    fun aWindowNeverPicksTheSidePanelByItself() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC, sidePanel = true)!!
        assertEquals(0, areas.indexFor(2560, 1440))
        assertEquals(0, areas.indexFor(1706, 1440))
        areas.use(areas.sidePanel()!!)
        assertEquals(0, areas.indexFor(2560, 1440))
    }

    @Test
    fun theDockMovesBesideTheSidePanelToo() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.DRIVER_SIDE, sidePanel = true)!!
        areas.use(areas.sidePanel()!!)
        val moved = areas.withDock(DOCK_EDGE_BOTTOM)!!
        assertEquals(Kind.SIDE_PANEL, areas.kindOf(moved))
        assertEquals(DOCK_EDGE_BOTTOM, areas.areas[moved].dockEdge)
    }
}
