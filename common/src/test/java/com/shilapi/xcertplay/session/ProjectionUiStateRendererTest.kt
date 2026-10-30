package com.shilapi.xcertplay.session

import org.junit.Assert.*
import org.junit.Test

class ProjectionUiStateRendererTest {
    @Test fun onlyFailedStateShowsTheManualRetry() {
        RuntimePhase.entries.forEach { phase ->
            assertEquals(phase==RuntimePhase.FAILED,ProjectionUiStateRenderer.render(SessionState(phase=phase)).showRetry)
        }
    }
    @Test fun permissionPromptsDoNotRunForDiagnosticOrWifiResetWaits() {
        for(reason in listOf("diagnostic_active","wifi_reset_required","display_configuration_pending"))
            assertFalse(ProjectionUiStateRenderer.render(SessionState(phase=RuntimePhase.WAITING_PREREQUISITES,failure=reason)).requestPrerequisites)
        assertTrue(ProjectionUiStateRenderer.render(SessionState(phase=RuntimePhase.WAITING_PREREQUISITES,failure="vpn_consent_required")).requestPrerequisites)
    }
}
