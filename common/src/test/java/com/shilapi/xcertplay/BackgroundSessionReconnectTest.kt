package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [BackgroundSessionReconnectTest.Bootstrap::class, BackgroundSessionReconnectTest.Vpn::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BackgroundSessionReconnectTest {
    @Test fun adoptedWiredSessionRechecksConsentAndRestarts() = exercise(false, false)
    @Test fun adoptedWirelessSessionCanRestartAfterMicrophoneWasDenied() = exercise(true, false)
    @Test fun revokedWiredConsentWaitsForUserOnRestart() = exercise(false, true)

    @Test fun adoptionRestoresTheLiveSessionAndSynchronizesThemeWithoutMaskMode() =
        exercise(true, false, restoreSession = true)

    @Test fun microphoneGrantedInSystemSettingsIsUsedByTheReplacementStack() =
        exercise(true, false, grantMic = true)

    @Test fun adoptionObservesASessionChangeWhileInstallingTheNewListener() =
        exercise(true, false, restoreSession = true, switchDuringAttach = true)

    private fun exercise(wireless: Boolean, revokeConsent: Boolean, restoreSession: Boolean = false, grantMic: Boolean = false,
        switchDuringAttach: Boolean = false) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        AirPlayPersistence.saveWirelessEnabled(app, wireless)
        AirPlayPersistence.saveSmoothVideo(app, false)
        AirPlayPersistence.saveLocationReportingEnabled(app, false)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        if (wireless) shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        else shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        Vpn.calls = 0
        Vpn.consent = null
        BackgroundSessionFixture.clear()
        val old = mock(CarPlayController::class.java)
        `when`(old.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(old.awaitClosed(anyLong())).thenReturn(true)
        val activeSession = mock(com.shilapi.xcertplay.airplay.AirPlaySession::class.java)
        if (restoreSession) `when`(old.currentAirPlaySession()).thenReturn(activeSession)
        val replacementSession = mock(com.shilapi.xcertplay.airplay.AirPlaySession::class.java)
        val expectedSession = if (switchDuringAttach) replacementSession else activeSession
        val oldSink = mock(AndroidMediaSink::class.java)
        BackgroundSessionFixture.store(old, oldSink, 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, 0, true, true, 800, 480)) { it() }
        val constructedConfigs = mutableListOf<com.shilapi.xcertplay.airplay.AirPlayConfig>()
        val construction = mockConstruction(CarPlayController::class.java) { controller, context ->
            constructedConfigs += context.arguments().filterIsInstance<com.shilapi.xcertplay.airplay.AirPlayConfig>().single()
            `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        }
        val owner = Robolectric.buildActivity(CarPlayHostActivity::class.java)
        val activity = owner.get()
        val commands = if (restoreSession) PausedExecutorService().also {
            field<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
            HostSessionFixture.setField(activity, "airPlayCommandExecutor", it)
        } else null
        owner.create()
        if(switchDuringAttach){
            `when`(old.currentAirPlaySession()).thenReturn(replacementSession)
            BackgroundSessionFixture.deliverActive(replacementSession)
        }
        try {
            assertSame(old, field<CarPlayController>(activity, "controller"))
            assertEquals("Adoption must not disturb the live connection with a VPN prompt", 0, Vpn.calls)
            if (restoreSession) {
                assertEquals(0, field<Int>(activity, "blackCornerRadiusDp"))
                assertSame(expectedSession, field<Any?>(activity, "activeAirPlaySession"))
                commands!!.runAll()
                verify(expectedSession).setNightMode(anyBoolean())
            }
            if (grantMic) {
                owner.start().resume().pause().stop()
                shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
                owner.restart().start().resume()
                assertTrue(field(activity, "microphoneAvailable"))
                verify(old, never()).close()
            }
            if (revokeConsent) Vpn.consent = Intent("test.vpn.CONSENT")
            field<ExecutorService>(activity, "teardownExecutor").shutdownNow()
            val executor = PausedExecutorService()
            HostSessionFixture.setField(activity, "teardownExecutor", executor)
            activity.javaClass.getDeclaredMethod("restartCarPlay", String::class.java)
                .apply { isAccessible = true }.invoke(activity, "Test adopted connection loss")
            executor.runAll()
            shadowOf(Looper.getMainLooper()).idle()
            verify(old).close()
            if (revokeConsent) {
                assertTrue("The replacement must recheck VPN consent",Vpn.calls >= 1)
                assertTrue(field(activity, "awaitingVpnConsent"))
                assertFalse(field(activity, "vpnReady"))
                assertTrue(construction.constructed().isEmpty())
            } else {
                assertEquals("Replacement state=${DiPlaySessionService.liveRuntime?.snapshot()}", 1, construction.constructed().size)
                verify(construction.constructed().single()).start()
                if (!wireless) assertTrue("The replacement must recheck VPN consent",Vpn.calls >= 1)
                assertEquals(!wireless || grantMic,field<Boolean>(activity,"microphoneAvailable"))
                if (wireless) {
                    assertEquals(grantMic, field<Boolean>(activity, "microphoneAvailable"))
                    assertEquals(grantMic, constructedConfigs.single().microphone)
                }
            }
        } finally {
            field<AtomicBoolean>(activity, "shuttingDown").set(true)
            field<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
            (field<Any?>(activity, "sink") as? AndroidMediaSink)?.close()
            field<ExecutorService>(activity, "teardownExecutor").shutdownNow()
            field<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
            BackgroundSessionFixture.clear()
            owner.destroy()
            construction.close()
        }
    }
    private fun <T> field(target: Any, name: String): T = HostSessionFixture.getField(target, name)

    @Implements(DiPlayBootstrap::class, isInAndroidSdk = false)
    class Bootstrap {
        @Implementation fun ensure(context: Context, target: com.shilapi.xcertplay.orchestration.MfiTarget) = Unit
        @Implementation fun deviceId(identity: AirPlayIdentity): String = "02:00:00:00:00:01"
    }
    @Implements(VpnService::class)
    class Vpn {
        companion object {
            var calls = 0
            var consent: Intent? = null
            @JvmStatic @Implementation fun prepare(context: Context): Intent? { calls++; return consent }
        }
    }
}
