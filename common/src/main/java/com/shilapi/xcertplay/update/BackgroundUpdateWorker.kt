package com.shilapi.xcertplay.update

import android.content.Context
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Keep the persisted class name so old installations can finish queued jobs without networking. */
internal class BackgroundUpdateWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
}

/** Migration only: new versions never schedule or retry an update check. */
internal object BackgroundUpdateScheduler {
    fun cancel(context: Context) {
        context.getSharedPreferences("diplay_update_availability", Context.MODE_PRIVATE)
            .edit().clear().apply()
        cancel(WorkManager.getInstance(context.applicationContext))
    }

    internal fun cancel(manager: WorkManager) {
        manager.cancelUniqueWork("diplay-periodic-update-check")
        manager.cancelUniqueWork("diplay-open-update-check")
    }
}
