package com.shilapi.xcertplay

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.shilapi.xcertplay.network.CarHotspotController
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Boot opt-in runs independently of the separate "open app after boot" preference. */
class CarHotspotBootService : JobService() {
    @Volatile private var generation = 0
    override fun onStartJob(params: JobParameters): Boolean {
        if (!eligible(this)) return false
        val epoch = ++generation
        Thread({
            val result = runCatching {
                CarHotspotController(this) { DetailedDebugLog.record("hotspot-boot", it) }
                    .enable { epoch != generation || !eligible(this) }
            }
            DetailedDebugLog.record("hotspot-boot", "completed success=${result.getOrNull()?.success == true} exception=${result.exceptionOrNull()?.javaClass?.simpleName ?: "none"}")
            if (epoch == generation) jobFinished(params, false)
        }, "carplay-hotspot-boot").start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { generation++; return false }

    companion object {
        private const val JOB = 0x435048
        private fun eligible(context: Context) = CarHotspotSettings.enabled(context) &&
            AirPlayPersistence.loadWirelessHotspotMode(context) == WirelessHotspotMode.MANUAL
        fun schedule(context: Context) {
            if (!eligible(context)) return
            runCatching {
                context.getSystemService(JobScheduler::class.java)?.schedule(JobInfo.Builder(JOB,
                    ComponentName(context, CarHotspotBootService::class.java))
                    .setMinimumLatency(5_000).setOverrideDeadline(20_000).build())
            }.onFailure { DetailedDebugLog.record("hotspot-boot", "schedule failed ${it.javaClass.simpleName}") }
        }
        fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java)?.cancel(JOB) }
    }
}
