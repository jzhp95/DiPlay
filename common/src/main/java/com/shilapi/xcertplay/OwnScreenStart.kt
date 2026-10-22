package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.util.Log

internal object OwnScreenStart {
    private const val TAG = "Apple CarPlay"

    fun start(activity: Activity, intent: Intent, log: (String) -> Unit = { Log.w(TAG, it) }): Boolean {
        val screen = intent.component?.className?.substringAfterLast('.') ?: "screen"
        try {
            activity.startActivity(intent)
            return true
        } catch (refused: IllegalArgumentException) {
            log("Opening $screen refused: ${refused.message?.lineSequence()?.firstOrNull()?.take(MAX_MESSAGE)}; " +
                "retrying in a new task")
        }
        return try {
            activity.applicationContext.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (again: RuntimeException) {
            log("Opening $screen in a new task failed: ${again.javaClass.simpleName}")
            false
        }
    }

    private const val MAX_MESSAGE = 120
}
