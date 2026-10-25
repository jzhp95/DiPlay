package com.shilapi.xcertplay

import org.json.JSONObject

/** Called off the UI thread, after BOTH resources were asked to close. Never overlaps the next test. */
internal object MainProbeTeardown {
    @Volatile private var unreleased: (() -> Boolean)? = null
    fun retain(checkReleased: () -> Boolean) { unreleased = checkReleased }
    fun blocked(): Boolean {
        val check = unreleased ?: return false
        if (!check()) return true
        if (unreleased === check) unreleased = null
        return false
    }
    fun await(controller: (Long) -> Boolean, video: (Long) -> Boolean,
        timeoutMillis: Long = 30_000, now: () -> Long = { System.nanoTime() / 1_000_000 }): JSONObject {
        val start = now()
        val deadline = start + timeoutMillis
        val controllerReleased = controller((deadline - now()).coerceAtLeast(0))
        val controllerEnd = now()
        val videoReleased = video((deadline - now()).coerceAtLeast(0))
        // A resource might finish while we were waiting for the other one.
        val controllerFinal = controllerReleased || controller(0)
        val videoFinal = videoReleased || video(0)
        return JSONObject().put("controllerReleased", controllerFinal).put("videoReleased", videoFinal)
            .put("controllerWaitMillis", controllerEnd - start).put("totalWaitMillis", now() - start)
            .put("timeoutMillis", timeoutMillis).put("confirmed", controllerFinal && videoFinal)
    }
}
