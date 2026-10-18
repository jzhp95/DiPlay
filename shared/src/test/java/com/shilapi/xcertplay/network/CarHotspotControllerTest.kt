package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [CarHotspotControllerTest.Wifi::class])
class CarHotspotControllerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        app.getSharedPreferences("diplay_managed_hotspot", 0).edit().clear().commit()
        Wifi.on = false; Wifi.accept = true; Wifi.normalizeAuto = false; Wifi.beforeStart = null
        Wifi.value = WifiConfiguration().apply { SSID = "Old"; preSharedKey = "12345678"; allowedKeyManagement.set(4) }
    }
    @Test fun androidLegacyConfigurationIsWrittenReadBackAndThenConfirmed() {
        val requested = CarHotspotProfile("New", "abcdefgh", ManualHotspotSecurity.WPA2, ManualHotspotBand.GHZ_5, 149)
        val result = CarHotspotController(app).apply(requested)
        assertTrue(result.diagnostic, result.success)
        assertTrue(Wifi.on)
        assertEquals(requested, CarHotspotProfiles.confirmed(app))
    }
    @Test fun frameworkRefusalDoesNotCacheRequestedCredentials() {
        Wifi.accept = false
        val result = CarHotspotController(app).apply(CarHotspotProfile("New", "abcdefgh", ManualHotspotSecurity.WPA2))
        assertFalse(result.success); assertNull(CarHotspotProfiles.confirmed(app)); assertEquals("Old", Wifi.value.SSID)
    }
    @Test fun wpa3IsNeverSilentlyWrittenAsLegacyWpa2() {
        val result = CarHotspotController(app).apply(CarHotspotProfile("New", "abcdefgh", ManualHotspotSecurity.WPA3))
        assertFalse(result.success); assertEquals("Old", Wifi.value.SSID)
    }

    @Test fun enablingUsesSavedCredentialsInsteadOfOldSystemConfiguration() {
        seedSavedProfile()

        val result = CarHotspotController(app).enable()

        assertTrue(result.diagnostic, result.success)
        assertTrue(Wifi.on)
        assertEquals(CarHotspotProfile("Saved car", "saved-password", ManualHotspotSecurity.WPA2,
            ManualHotspotBand.GHZ_5, 149), result.profile)
        assertEquals("Saved car", Wifi.value.SSID)
        assertEquals("saved-password", Wifi.value.preSharedKey)
    }

    @Test fun failedSavedConfigurationDoesNotStartOldSystemHotspot() {
        seedSavedProfile()
        Wifi.accept = false

        val result = CarHotspotController(app).enable()

        assertFalse(result.diagnostic, result.success)
        assertFalse(Wifi.on)
        assertEquals("Old", Wifi.value.SSID)
        assertNull(CarHotspotProfiles.confirmed(app))
    }

    @Test fun savedCredentialsAreNotOverwrittenByReadableSystemConfiguration() {
        seedSavedProfile()

        val selected = CarHotspotProfiles.resolve(app, manual = { error("manual must remain unread") })

        assertEquals(CarHotspotProfile("Saved car", "saved-password", ManualHotspotSecurity.WPA2,
            ManualHotspotBand.GHZ_5, 149), selected?.profile)
        assertEquals("Old", Wifi.value.SSID)
        assertFalse(Wifi.on)
    }

    @Test fun successfulApplyKeepsAutoIntentSeparateFromActualRadioReadback() {
        Wifi.normalizeAuto = true
        val requested = CarHotspotProfile("Saved car", "saved-password", ManualHotspotSecurity.WPA2)

        val result = CarHotspotController(app).apply(requested)
        val selected = CarHotspotProfiles.resolve(app, manual = { null })

        assertTrue(result.diagnostic, result.success)
        assertEquals(CarHotspotProfile("Saved car", "saved-password", ManualHotspotSecurity.WPA2,
            ManualHotspotBand.GHZ_5, 149), CarHotspotProfiles.confirmed(app))
        assertEquals(requested, selected?.profile)
    }

    @Test fun cancellationBeforeSavedEnableLeavesSystemAndSavedCredentialsUntouched() {
        seedSavedProfile()

        val result = CarHotspotController(app).enable { true }

        assertFalse(result.success)
        assertFalse(Wifi.on)
        assertEquals("Old", Wifi.value.SSID)
        assertEquals("Saved car", app.getSharedPreferences("diplay_managed_hotspot", 0).getString("saved_ssid", null))
        assertNull(CarHotspotProfiles.confirmed(app))
    }

    @Test fun completingAnOlderEnableDoesNotOverwriteANewerExplicitSave() {
        seedSavedProfile()
        val starting = CountDownLatch(1)
        val release = CountDownLatch(1)
        Wifi.beforeStart = {
            starting.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }
        val operation = FutureTask { CarHotspotController(app).enable() }
        val worker = Thread(operation, "test-hotspot-enable").apply { start() }
        try {
            assertTrue("Enable did not reach the system start", starting.await(3, TimeUnit.SECONDS))
            val newer = CarHotspotProfile("Newer edit", "newer-password", ManualHotspotSecurity.WPA3)
            CarHotspotProfiles.saveSaved(app, newer)
            release.countDown()

            assertTrue(operation.get(3, TimeUnit.SECONDS).success)
            assertEquals(newer, CarHotspotProfiles.saved(app))
            assertEquals("Saved car", CarHotspotProfiles.confirmed(app)?.ssid)
        } finally {
            release.countDown()
            worker.join(3_000)
        }
    }

    @Test fun queuedApplyDoesNotOverwriteAProfileSavedWhileItWaitedForControl() {
        seedSavedProfile()
        val starting = CountDownLatch(1)
        val release = CountDownLatch(1)
        Wifi.beforeStart = {
            starting.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }
        val holding = FutureTask {
            CarHotspotController(app) { if (it.contains("configuration verified")) Wifi.on = false }.apply(CarHotspotProfile("First operation", "first-password", ManualHotspotSecurity.WPA2))
        }
        val holdingWorker = Thread(holding, "test-hotspot-holding").apply { start() }
        val queued = FutureTask {
            CarHotspotController(app).apply(CarHotspotProfile("Queued operation", "queued-password", ManualHotspotSecurity.WPA2))
        }
        val queuedWorker = Thread(queued, "test-hotspot-queued")
        try {
            assertTrue("First operation did not reach system start", starting.await(3, TimeUnit.SECONDS))
            queuedWorker.start()
            val controlLock = CarHotspotController::class.java.getDeclaredField("lock").apply { isAccessible = true }
                .get(null) as ReentrantLock
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!controlLock.hasQueuedThread(queuedWorker) && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("Second operation did not queue behind the first", controlLock.hasQueuedThread(queuedWorker))
            val newer = CarHotspotProfile("Newer saved edit", "newer-password", ManualHotspotSecurity.WPA3)
            CarHotspotProfiles.saveSaved(app, newer)
            release.countDown()

            holding.get(3, TimeUnit.SECONDS).let { assertTrue(it.diagnostic, it.success) }
            queued.get(3, TimeUnit.SECONDS).let { assertTrue(it.diagnostic, it.success) }
            assertEquals(newer, CarHotspotProfiles.saved(app))
            assertEquals("Queued operation", CarHotspotProfiles.confirmed(app)?.ssid)
        } finally {
            release.countDown()
            holdingWorker.join(3_000)
            queuedWorker.join(3_000)
        }
    }

    private fun seedSavedProfile() {
        app.getSharedPreferences("diplay_managed_hotspot", 0).edit()
            .putString("saved_ssid", "Saved car")
            .putString("saved_password", "saved-password")
            .putString("saved_security", "WPA2")
            .putString("saved_band", "GHZ_5")
            .putInt("saved_channel", 149).commit()
    }

    @Implements(WifiManager::class)
    class Wifi : ShadowWifiManager() {
        companion object { var on=false; var accept=true; var normalizeAuto=false; var beforeStart:(()->Unit)?=null; lateinit var value:WifiConfiguration }
        @Implementation(methodName = "getWifiApConfiguration") fun readAp(): WifiConfiguration = value
        @Implementation(methodName = "setWifiApConfiguration") fun writeAp(config:WifiConfiguration):Boolean {
            if (accept) {
                value = config
                if (normalizeAuto && config.javaClass.getField("apChannel").getInt(config) == 0) {
                    config.javaClass.getField("apBand").setInt(config, 1)
                    config.javaClass.getField("apChannel").setInt(config, 149)
                }
            }
            return accept
        }
        @Implementation(methodName = "isWifiApEnabled") fun state():Boolean = on
        @Implementation(methodName = "startSoftAp") fun startAp(config:WifiConfiguration?):Boolean {beforeStart?.invoke();on=true;return true}
        @Implementation(methodName = "stopSoftAp") fun stopAp():Boolean {on=false;return true}
        @Implementation(methodName = "setWifiApEnabled") fun enabledAp(config:WifiConfiguration?,enabled:Boolean):Boolean {on=enabled;return true}
    }
}
