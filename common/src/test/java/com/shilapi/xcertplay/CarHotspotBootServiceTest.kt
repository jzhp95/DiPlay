package com.shilapi.xcertplay

import android.app.job.JobScheduler
import com.shilapi.xcertplay.network.CarHotspotSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotBootServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val scheduler get() = app.getSystemService(JobScheduler::class.java)
    @Before fun setup() {
        app.getSharedPreferences("diplay_car_hotspot", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        scheduler.cancelAll()
    }
    @Test fun defaultOffSchedulesNothing() {
        assertFalse(CarHotspotSettings.enabled(app))
        CarHotspotBootService.schedule(app)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
    @Test fun hotspotBootDoesNotRequireAppAutoStart() {
        CarHotspotSettings.setEnabled(app, true)
        CarHotspotBootService.schedule(app)
        assertEquals(1, scheduler.allPendingJobs.size)
        assertEquals(CarHotspotBootService::class.java.name, scheduler.allPendingJobs.single().service.className)
    }
    @Test fun otherWirelessModesDoNotScheduleAHotspot() {
        CarHotspotSettings.setEnabled(app, true)
        app.getSharedPreferences("xcertplay_airplay", 0).edit().putString("wireless_hotspot_mode", "WIFI_P2P").commit()
        CarHotspotBootService.schedule(app)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
    @Test fun disablingCancelsTheQueuedBootJob() {
        CarHotspotSettings.setEnabled(app, true)
        CarHotspotBootService.schedule(app)
        CarHotspotSettings.setEnabled(app, false)
        CarHotspotBootService.cancel(app)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
}
