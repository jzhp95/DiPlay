package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class AutomaticMfiSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", 0)
    @Before fun setup() { prefs.edit().clear().commit() }

    @Test fun aFreshInstallSelectsAutomaticAuthentication() {
        assertEquals("AUTO", AirPlayPersistence.loadMfiTarget(context).name)
    }

    @Test fun legacyLocalAndUsbSelectionsUseAutomaticWithoutDeletingTheOldChoice() {
        for (legacy in listOf("LOCAL", "USB_CH341")) {
            prefs.edit().clear().putString("mfi_target", legacy).commit()
            assertEquals("AUTO", AirPlayPersistence.loadMfiTarget(context).name)
            assertEquals(legacy, prefs.getString("mfi_target", null))
        }
    }

    @Test fun legacyExpertBackendsKeepTheirExistingSelection() {
        for (legacy in listOf("I2C", "REMOTE")) {
            prefs.edit().clear().putString("mfi_target", legacy).commit()
            assertEquals(legacy, AirPlayPersistence.loadMfiTarget(context).name)
        }
    }

    @Test fun anExplicitChoiceOverridesAutomaticMigrationAndPreservesRollbackData() {
        prefs.edit().putString("mfi_target", "LOCAL").commit()
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(context))
        assertEquals("LOCAL", prefs.getString("mfi_target", null))
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(context))
    }
}
