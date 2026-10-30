package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.VideoColorOverride
import org.junit.Assert.*
import org.junit.Test

class ColorMainProbeRegistryTest {
    @Test fun unregisteredIntentTokensCannotActivateTemporaryHostSettings() {
        assertNull(ColorMainProbeRegistry.lookup("external-token"))
        val case = ColorTestRun.cases.indexOfFirst { it.mainProfile == "direct" }
        val request = ColorMainProbeRegistry.begin(case, VideoColorOverride())
        try {
            assertEquals(false to true, request.profile.resolve(true, false))
            assertSame(request, ColorMainProbeRegistry.lookup(request.token))
        } finally { ColorMainProbeRegistry.finish(request.token);ColorMainProbeRegistry.teardownFinished(request.token) }
        assertNull(ColorMainProbeRegistry.lookup(request.token))
    }
    @Test fun aStaleCompletionCannotFinishANewerProbeAndCurrentProfileUsesSavedValues() {
        val index = ColorTestRun.cases.indexOfFirst { it.mainProfile == "current" }
        val first = ColorMainProbeRegistry.begin(index, VideoColorOverride())
        try {ColorMainProbeRegistry.begin(index,VideoColorOverride());fail("overlap must be rejected")}
        catch(expected:IllegalStateException){assertTrue(ColorMainProbeRegistry.lookup(first.token)!=null)}
        ColorMainProbeRegistry.finish(first.token);ColorMainProbeRegistry.teardownFinished(first.token)
        val second = ColorMainProbeRegistry.begin(index, VideoColorOverride())
        assertNull(ColorMainProbeRegistry.finish(first.token))
        assertSame(second, ColorMainProbeRegistry.lookup(second.token))
        assertEquals(true to false, second.profile.resolve(true, false))
        assertEquals(false to true, second.profile.resolve(false, true))
        ColorMainProbeRegistry.finish(second.token);ColorMainProbeRegistry.teardownFinished(second.token)
    }
}
