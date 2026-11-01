package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.video.SessionVideoRouter
import com.shilapi.xcertplay.media.video.VideoImplementation
import com.shilapi.xcertplay.session.SessionMediaOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSessionFactoryTest {

    @Test
    fun sessionMediaOptionsRetainsIndependentImplementationChoice() {
        val optionsLegacy = SessionMediaOptions(1280, 720, implementation = VideoImplementation.LEGACY)
        assertEquals(VideoImplementation.LEGACY, optionsLegacy.implementation)

        val optionsIndependent = SessionMediaOptions(1280, 720, implementation = VideoImplementation.INDEPENDENT)
        assertEquals(VideoImplementation.INDEPENDENT, optionsIndependent.implementation)
    }
}
