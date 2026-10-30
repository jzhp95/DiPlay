package com.shilapi.xcertplay.session

/** UI decisions derive from the service snapshot; the page owns no recovery budget. */
internal object ProjectionUiStateRenderer {
    data class State(val showRetry: Boolean, val requestPrerequisites: Boolean)
    fun render(session: SessionState) = State(
        showRetry = session.phase == RuntimePhase.FAILED,
        requestPrerequisites = session.phase == RuntimePhase.WAITING_PREREQUISITES &&
            session.failure !in setOf("wifi_reset_required", "diagnostic_active", "display_configuration_pending"),
    )
}
