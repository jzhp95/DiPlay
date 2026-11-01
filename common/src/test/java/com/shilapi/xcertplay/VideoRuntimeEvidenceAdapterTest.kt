package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.video.BackendKind
import com.shilapi.xcertplay.media.video.FrameKey
import com.shilapi.xcertplay.media.video.ReleaseState
import com.shilapi.xcertplay.media.video.RuntimeIdentity
import com.shilapi.xcertplay.media.video.RuntimeSnapshot
import com.shilapi.xcertplay.media.video.SampleResult
import com.shilapi.xcertplay.media.video.SessionVideoId
import com.shilapi.xcertplay.media.video.VideoImplementation
import com.shilapi.xcertplay.media.video.YuvSample
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoRuntimeEvidenceAdapterTest {

    @Test
    fun formatsSnapshotCorrectlyIntoJsonObject() {
        val snapshot = RuntimeSnapshot(
            sessionId = "s123",
            streamRole = 110,
            requestedImplementation = VideoImplementation.INDEPENDENT,
            actualImplementation = VideoImplementation.INDEPENDENT,
            actualBackend = BackendKind.PLATFORM_SURFACE,
            releaseState = ReleaseState.RELEASED,
            appOwnedBytes = 1024L
        )

        val json = VideoRuntimeEvidenceAdapter.snapshotToJson(snapshot)
        assertEquals("s123", json.getString("sessionId"))
        assertEquals(110, json.getInt("streamRole"))
        assertEquals("INDEPENDENT", json.getString("requestedImplementation"))
        assertEquals("PLATFORM_SURFACE", json.getString("actualBackend"))
        assertEquals("RELEASED", json.getString("releaseState"))
        assertEquals(1024L, json.getLong("appOwnedBytes"))
    }

    @Test
    fun formatsSampleResultCorrectly() {
        val identity = RuntimeIdentity(SessionVideoId("s1"), 1L, 1L, 1L, "inst")
        val key = FrameKey(identity, 42L, 50_000L)
        val sampleResult = SampleResult(
            samples = listOf(YuvSample(10, 20, 100, 128, 128)),
            frameKey = key
        )

        val json = VideoRuntimeEvidenceAdapter.sampleResultToJson(sampleResult)
        assertEquals(42L, json.getLong("frameId"))
        assertEquals(50_000L, json.getLong("codecPtsUs"))
        val samples = json.getJSONArray("samples")
        assertEquals(1, samples.length())
        assertEquals(100, samples.getJSONObject(0).getInt("yVal"))
    }
}
