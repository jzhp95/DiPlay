package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ConnectionDiagnosticResourcesTest {
    @Test fun internalStageTokensRemainEnglishWhileUiStringsAreChinese() {
        val resources = RuntimeEnvironment.getApplication().resources
        val diagnostic = ConnectionDiagnosticResources(resources)
        assertEquals("Waiting for iPhone over USB", diagnostic.getString(R.string.waiting_for_iphone_over_usb))
        assertEquals("Failed: permission denied", diagnostic.getString(R.string.status_failed, "permission denied"))
        assertTrue(resources.getString(R.string.waiting_for_iphone_over_usb).any { it in '\u4e00'..'\u9fff' })
        assertEquals(resources.getString(R.string.connect_phone), diagnostic.getString(R.string.connect_phone))
    }
}
