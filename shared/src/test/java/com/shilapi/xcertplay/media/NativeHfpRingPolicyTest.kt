package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test

class NativeHfpRingPolicyTest {
    private val alert = AudioStreamId(100, "alert")
    @Test fun onlyTheOemRingingAlertIsSuppressedAndItsDecisionLastsUntilStop() {
        val policy = NativeHfpRingPolicy(true)
        assertFalse(policy.suppress(alert, false))
        assertTrue(policy.suppress(alert, true))
        assertTrue(policy.suppress(alert, false))
        policy.stop(alert)
        assertFalse(policy.suppress(alert, false))
    }
    @Test fun disabledPolicyAndMediaNavigationCallsAndSiriAlwaysPassThrough() {
        assertFalse(NativeHfpRingPolicy(false).suppress(alert, true))
        val policy = NativeHfpRingPolicy(true)
        for (id in listOf(AudioStreamId(101, "alert"), AudioStreamId(100, "default"),
            AudioStreamId(102, "media"), AudioStreamId(100, "telephony"), AudioStreamId(100, "speechrecognition")))
            assertFalse(id.toString(), policy.suppress(id, true))
        policy.suppress(alert, true)
        policy.clear()
        assertFalse(policy.suppress(alert, false))
    }
}
