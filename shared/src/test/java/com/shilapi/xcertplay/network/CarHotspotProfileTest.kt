package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Test

class CarHotspotProfileTest {
    private val profile = CarHotspotProfile("Car 5G", "12345678", ManualHotspotSecurity.WPA2, ManualHotspotBand.GHZ_5, 149)

    @Test fun validatesNamesSecurityAndRadioParameters() {
        assertNull(profile.error())
        assertNotNull(profile.copy(ssid = "").error())
        assertNotNull(profile.copy(security = ManualHotspotSecurity.OPEN).error())
        assertNotNull(profile.copy(passphrase = "short").error())
        assertNotNull(profile.copy(band = ManualHotspotBand.GHZ_2_4).error())
        assertNotNull(profile.copy(channel = 200).error())
        assertNull(profile.copy(security = ManualHotspotSecurity.OPEN, passphrase = "", channel = 0).error())
    }

    @Test fun onlyAutomaticRadioFieldsAllowDifferentReadback() {
        assertTrue(profile.copy(band = ManualHotspotBand.AUTO, channel = 0).matches(profile))
        assertFalse(profile.matches(profile.copy(channel = 36)))
        assertFalse(profile.matches(profile.copy(security = ManualHotspotSecurity.WPA3)))
        assertFalse(profile.matches(profile.copy(passphrase = "87654321")))
    }

    @Test fun systemProfileNeverLoadsConfirmedOrManualCredentials() {
        val result = CarHotspotProfileSelection.resolve({ profile }, { error("confirmed must stay unread") }, { error("manual must stay unread") })
        assertEquals(CarHotspotProfileSource.SYSTEM, result?.source)
    }

    @Test fun confirmedProfilePrecedesManualFallback() {
        val result = CarHotspotProfileSelection.resolve({ null }, { profile }, { error("manual must stay unread") })
        assertEquals(CarHotspotProfileSource.CONFIRMED, result?.source)
    }

    @Test fun invalidManagedProfilesFallBackToManualAndNeverBecomeSystemSuccess() {
        val result = CarHotspotProfileSelection.resolve({ profile.copy(ssid = "") }, { null }, { profile })
        assertEquals(CarHotspotProfileSource.MANUAL, result?.source)
        assertNull(CarHotspotProfileSelection.resolve({ null }, { null }, { profile.copy(passphrase = "x") }))
    }

    @Test fun blockedSystemReadsStillAllowManualFallback() {
        val result = CarHotspotProfileSelection.resolve({ throw SecurityException() }, { null }, { profile })
        assertEquals(CarHotspotProfileSource.MANUAL, result?.source)
    }

    @Test fun diagnosticsDoNotExposePasswords() {
        assertFalse(profile.toString().contains(profile.passphrase))
    }
}
