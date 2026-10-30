package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Home uses managed hotspot startup; retired vendor/ADB startup expectations do not apply. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class HomeHotspotAccessTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    @Before fun setup(){reset()}
    @After fun cleanup(){reset()}
    private fun reset(){
        for(name in listOf("diplay_car_hotspot","xcertplay_airplay","diplay"))
            context.getSharedPreferences(name,0).edit().clear().commit()
    }
    private fun eligible(session:Boolean=false)=CarHotspotSetup.shouldStartOnLaunch(context,session)
    @Test fun startupRequiresExplicitOptInWirelessAndManagedMode() {
        AirPlayPersistence.saveWirelessEnabled(context,true)
        AirPlayPersistence.saveWirelessHotspotMode(context,WirelessHotspotMode.MANUAL)
        assertFalse(eligible())
        CarHotspotSettings.setEnabled(context,true);assertTrue(eligible())
        AirPlayPersistence.saveWirelessEnabled(context,false);assertFalse(eligible())
        AirPlayPersistence.saveWirelessEnabled(context,true)
        for(mode in WirelessHotspotMode.entries.filter{it!=WirelessHotspotMode.MANUAL}){
            AirPlayPersistence.saveWirelessHotspotMode(context,mode)
            assertEquals(mode.name,AirPlayPersistence.loadWirelessHotspotMode(context)==WirelessHotspotMode.MANUAL,eligible())
        }
    }
    @Test fun anExistingSessionPreventsAnAutomaticHotspotRestart() {
        AirPlayPersistence.saveWirelessEnabled(context,true)
        AirPlayPersistence.saveWirelessHotspotMode(context,WirelessHotspotMode.MANUAL)
        CarHotspotSettings.setEnabled(context,true)
        assertFalse(eligible(session=true));assertTrue(eligible(session=false))
    }
    @Test fun startupEligibilityDoesNotWriteTheSavedProfileOrAuthorizeAnything() {
        val before=context.getSharedPreferences("diplay_managed_hotspot",0).all.toMap()
        AirPlayPersistence.saveWirelessEnabled(context,true)
        AirPlayPersistence.saveWirelessHotspotMode(context,WirelessHotspotMode.MANUAL)
        CarHotspotSettings.setEnabled(context,true)
        repeat(3){assertTrue(eligible())}
        assertEquals(before,context.getSharedPreferences("diplay_managed_hotspot",0).all)
    }
}
