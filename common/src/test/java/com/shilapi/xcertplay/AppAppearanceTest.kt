package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAppearanceTest {
    private val schedule = CarPlayNightSchedule(startMinute = 18 * 60, endMinute = 6 * 60)

    @After fun resetRuntime() = AppAppearanceRuntime.resetForTest()

    @Test fun stableKeysRoundTripAndUnknownValuesDefaultToDark() {
        assertEquals(AppAppearance.DARK, AppAppearance.fromKey(null))
        assertEquals(AppAppearance.DARK, AppAppearance.fromKey("future-value"))
        AppAppearance.entries.forEach { appearance ->
            assertEquals(appearance, AppAppearance.fromKey(appearance.key))
        }
    }

    @Test fun systemAppearanceIgnoresEveryLegacyPolicyAndHostOverride() {
        for (appearance in AppAppearance.entries) for (mode in CarPlayNightMode.entries) {
            for (systemNight in listOf(false, true)) for (hostNight in listOf(null, false, true)) {
                for (minute in listOf(0, 12 * 60, 23 * 60)) {
                    assertEquals(systemNight, resolveAppNight(appearance, mode, schedule,
                        systemNight, minute, hostNight))
                }
            }
        }
    }

    @Test fun runtimePublishesOnlyChangesAndProtectsTheCurrentOwner() {
        val firstOwner = Any()
        val secondOwner = Any()
        val observed = mutableListOf<Boolean?>()
        val stopObserving = AppAppearanceRuntime.observeHost(observed::add)

        assertEquals(listOf<Boolean?>(null), observed)
        AppAppearanceRuntime.publishHost(firstOwner, true)
        AppAppearanceRuntime.publishHost(firstOwner, true)
        AppAppearanceRuntime.publishHost(secondOwner, false)
        AppAppearanceRuntime.clearHost(firstOwner)
        assertEquals(false, AppAppearanceRuntime.hostNight())
        AppAppearanceRuntime.clearHost(secondOwner)
        assertNull(AppAppearanceRuntime.hostNight())
        assertEquals(listOf(null, true, false, null), observed)

        stopObserving()
        stopObserving()
        AppAppearanceRuntime.publishHost(firstOwner, true)
        assertEquals(listOf(null, true, false, null), observed)
    }

    @Test fun runtimeDoesNotHideObserverFailures() {
        val owner = Any()
        AppAppearanceRuntime.observeHost { night ->
            if (night != null) error("observer failed")
        }

        val failure = runCatching { AppAppearanceRuntime.publishHost(owner, true) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("observer failed", failure?.message)
    }

    @Test fun appearanceRepaintWaitsForEitherWheelKeyLearningPath() {
        assertFalse(shouldDeferAppearanceRender(windowKeyLearning = false, serviceKeyLearning = false))
        assertTrue(shouldDeferAppearanceRender(windowKeyLearning = true, serviceKeyLearning = false))
        assertTrue(shouldDeferAppearanceRender(windowKeyLearning = false, serviceKeyLearning = true))
        assertTrue(shouldDeferAppearanceRender(windowKeyLearning = true, serviceKeyLearning = true))
    }
}
