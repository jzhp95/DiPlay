package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.video.SessionVideoRouter
import com.shilapi.xcertplay.media.video.VideoImplementation
import com.shilapi.xcertplay.session.SessionMediaOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import com.shilapi.xcertplay.session.SessionFactory
import com.shilapi.xcertplay.session.SessionStartRequest
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[28],manifest=org.robolectric.annotation.Config.NONE)
class VideoSessionFactoryTest {

    @Test fun productionFactoryUsesRequestedImplementationForItsActualSink() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val factory = SessionFactory(app)
        for (implementation in VideoImplementation.values()) {
            val request = SessionStartRequest(
                CarPlayRuntimeConfig(mfiTarget=MfiTarget.LOCAL,identification=Iap2IdentificationConfig(
                    name="test",modelIdentifier="test",manufacturer="test",serialNumber="test",firmwareVersion="1",hardwareVersion="1",carPlayUsbInterfaceNumber=3)),
                AirPlayConfig(deviceName="test",deviceId="02:00:00:00:00:02",btMac="02:00:00:00:00:01",sourceVersion="1",
                    main=AirPlayDisplayConfig(widthPixels=800,heightPixels=480)),
                AirPlayIdentity.generate(),SessionMediaOptions(800,480,implementation=implementation))
            val connection = factory.create(request,object:AirPlaySessionListener{}, {}, {_,_->})
            try {
                assertEquals(implementation,connection.sink.videoImplementation)
                assertEquals(implementation==VideoImplementation.INDEPENDENT,connection.sink.videoRuntimeSnapshot()!=null)
                assertEquals(0,connection.sink.resourceOwnershipCounts()["primaryVideoDecoders"])
            } finally { connection.close(); assertTrue(connection.sink.awaitVideoReleased(3000)) }
        }
    }

    @Test
    fun sessionMediaOptionsRetainsIndependentImplementationChoice() {
        val optionsLegacy = SessionMediaOptions(1280, 720, implementation = VideoImplementation.LEGACY)
        assertEquals(VideoImplementation.LEGACY, optionsLegacy.implementation)

        val optionsIndependent = SessionMediaOptions(1280, 720, implementation = VideoImplementation.INDEPENDENT)
        assertEquals(VideoImplementation.INDEPENDENT, optionsIndependent.implementation)
    }
}
