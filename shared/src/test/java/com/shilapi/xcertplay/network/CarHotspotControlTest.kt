package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Test

class CarHotspotControlTest {
    private val profile = CarHotspotProfile("Car", "12345678", ManualHotspotSecurity.WPA2)
    private class Api(var configuration: CarHotspotProfile?) : CarHotspotApi {
        override val name = "test"
        var on = false; var accept = true; var canRead = true; var writes = 0; var starts = 0
        override fun read() = if (canRead) configuration else null
        override fun enabled() = on
        override fun supports(profile: CarHotspotProfile) = true
        override fun configure(profile: CarHotspotProfile): Boolean { writes++; if (accept) configuration = profile; return accept }
        override fun start(): Boolean { starts++; on = true; return true }
        override fun stop(): Boolean { on = false; return true }
    }
    private fun controller(api: Api) = CarHotspotControl(listOf(api), waitState = { a, state, _ -> a.enabled() == state })

    @Test fun successNeedsReadbackAndRunningState() {
        val api = Api(null)
        val result = controller(api).apply(profile)
        assertTrue(result.success); assertEquals(profile, result.profile); assertEquals(true, result.running)
    }
    @Test fun refusedWritesDoNotReportSuccess() {
        val api = Api(profile).apply { accept = false }
        assertFalse(controller(api).apply(profile.copy(ssid = "Other")).success)
    }
    @Test fun unobservableWritesNeverBecomeVerifiedProfiles() {
        val api = Api(null).apply { canRead = false }
        assertFalse(controller(api).apply(profile).success)
        assertFalse(api.on)
    }
    @Test fun failureRestoresPreviousConfigurationAndRunningState() {
        val api = Api(profile).apply { on = true; accept = false }
        assertFalse(controller(api).apply(profile.copy(ssid = "Other")).success)
        assertTrue(api.on); assertEquals(profile, api.configuration)
    }
    @Test fun verificationFailureRestoresAnInitiallyOffApAndItsProfile() {
        val api = Api(profile)
        val calls = mutableListOf<String>()
        val wrapped = object : CarHotspotApi by api {
            var first = true
            override fun configure(profile: CarHotspotProfile): Boolean {
                calls += "configure"
                return api.configure(if (first) profile.copy(ssid = "Firmware changed name").also { first = false } else profile)
            }
            override fun stop(): Boolean { calls += "stop"; return api.stop() }
        }
        val control = CarHotspotControl(listOf(wrapped), waitState = { a, state, _ -> a.enabled() == state })
        assertFalse(control.apply(profile.copy(ssid = "Requested")).success)
        assertFalse(api.on)
        assertEquals(profile, api.configuration)
        assertTrue(calls.indexOf("stop") < calls.lastIndexOf("configure"))
    }
    @Test fun cancellationAfterStartRestoresTheOriginalRunningProfile() {
        val api = Api(profile).apply { on = true }
        var cancelled = false
        var starts = 0
        val wrapped = object : CarHotspotApi by api {
            override fun start(): Boolean { starts++; if (starts == 1) cancelled = true; return api.start() }
        }
        val control = CarHotspotControl(listOf(wrapped), waitState = { a, state, _ -> a.enabled() == state })
        assertFalse(control.apply(profile.copy(ssid = "New")) { cancelled }.success)
        assertTrue(api.on)
        assertEquals(profile, api.configuration)
        Thread.interrupted()
    }
    @Test fun writeOnlyAdapterRestoresProfileUsingAnotherAdaptersReadback() {
        val api = Api(profile).apply { on = true }
        var cancelled = false
        var starts = 0
        val writer = object : CarHotspotApi by api {
            override fun read(): CarHotspotProfile? = null
            override fun start(): Boolean { if (++starts == 1) cancelled = true; return api.start() }
        }
        val reader = object : CarHotspotApi by api {
            override fun supports(profile: CarHotspotProfile) = false
        }
        val control = CarHotspotControl(listOf(writer, reader), waitState = { a, state, _ -> a.enabled() == state })
        val result = control.apply(profile.copy(ssid = "New")) { cancelled }
        assertFalse(result.success)
        assertEquals("cancelled", result.diagnostic)
        assertTrue(api.on)
        assertEquals(profile, api.configuration)
        Thread.interrupted()
    }
    @Test fun invalidProfilesAndCancellationNeverWrite() {
        val api = Api(profile)
        assertFalse(controller(api).apply(profile.copy(ssid = "")).success)
        assertFalse(controller(api).apply(profile, cancelled = { true }).success)
        assertEquals(0, api.writes)
    }
    @Test fun enablingAnExistingHotspotDoesNotRewriteIt() {
        val api = Api(profile)
        assertTrue(controller(api).enable().success)
        assertEquals(0, api.writes); assertEquals(profile, api.configuration)
    }
}
