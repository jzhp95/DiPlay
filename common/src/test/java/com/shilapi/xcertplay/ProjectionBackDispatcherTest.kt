package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class ProjectionBackDispatcherTest {
    @Test fun firstBackForwardsAndSecondWithin1500msGoesDesktopWithoutAnotherHid() {
        val b=ProjectionBackDispatcher();val s=Any();var sent=0
        assertEquals(ProjectionBackDispatcher.Result.FORWARDED,b.press(s,1000){sent++;true})
        assertEquals(ProjectionBackDispatcher.Result.DESKTOP,b.press(s,2499){sent++;true})
        assertEquals(1,sent)
    }
    @Test fun timeoutOrReplacementStartsNewGesture() {
        val b=ProjectionBackDispatcher();val s=Any()
        b.press(s,0){true}
        assertEquals(ProjectionBackDispatcher.Result.FORWARDED,b.press(s,1500){true})
        assertEquals(ProjectionBackDispatcher.Result.FORWARDED,b.press(Any(),1600){true})
    }
    @Test fun failedOrAbsentSessionFallsBackWithoutArmingDoubleBack() {
        val b=ProjectionBackDispatcher();val s=Any()
        assertEquals(ProjectionBackDispatcher.Result.FALLBACK,b.press(null,0){error("no session")})
        assertEquals(ProjectionBackDispatcher.Result.FALLBACK,b.press(s,100){false})
        assertEquals(ProjectionBackDispatcher.Result.FORWARDED,b.press(s,200){true})
    }
    @Test fun localPanelAndBackgroundResetPreventUnrelatedBackFromGoingDesktop() {
        val b=ProjectionBackDispatcher();val s=Any();b.press(s,0){true};b.reset()
        assertEquals(ProjectionBackDispatcher.Result.FORWARDED,b.press(s,100){true})
    }
}
