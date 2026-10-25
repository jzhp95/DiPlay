package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class ExperimentalAudioConfigTest {
    private fun pcm(vararg samples: Int) = samples.flatMap { listOf(it.toByte(), (it shr 8).toByte()) }.toByteArray()

    @Test fun disabledControlsAreTransparentEvenWithSavedValues() {
        val value = ExperimentalAudioConfig(microphoneDb = 12, navigationDb = 12, callDb = 12)
        assertEquals(0, value.microphoneGainDb)
        assertEquals(-9, value.playbackGainDb(true, false, -9))
        assertEquals(0, value.playbackGainDb(false, true, 0))
        val data = pcm(32767, -32768, 1000)
        Pcm16Gain.scale(data, 0, data.size, 0)
        assertArrayEquals(pcm(32767, -32768, 1000), data)
    }

    @Test fun independentGainsClampAndMusicAndAssistantRemainUnchanged() {
        val value = ExperimentalAudioConfig(true, 999, true, 6, true, -999)
        assertEquals(12, value.microphoneGainDb)
        assertEquals(0, value.playbackGainDb(true, false, -6))
        assertEquals(-12, value.playbackGainDb(false, true, 0))
        assertEquals(0, value.playbackGainDb(false, false, 0))
    }

    @Test fun amplificationSaturatesInsteadOfWrappingAndLeavesOutsideBytesAlone() {
        val data = pcm(123, 1000, -1000, 20000, -20000, 321) + byteArrayOf(99)
        Pcm16Gain.scale(data, 2, 9, 12)
        assertArrayEquals(pcm(123, 3981, -3981, 32767, -32768, 321) + byteArrayOf(99), data)
        val attenuated = pcm(10000, -10000)
        Pcm16Gain.scale(attenuated, 0, attenuated.size, -32)
        assertArrayEquals(pcm(251, -251), attenuated)
    }

    @Test fun recoveryBudgetNeverResetsWithSuccessAndIgnoresArgumentsAndDisabledMode() {
        val recovery = AudioOutputRecovery()
        assertFalse(recovery.shouldRebuild(-6, false, true))
        assertFalse(recovery.shouldRebuild(-6, true, false))
        assertFalse(recovery.shouldRebuild(-2, true, true))
        assertTrue(recovery.shouldRebuild(-6, true, true))
        repeat(100) { recovery.successfulWrite() }
        assertTrue(recovery.shouldRebuild(-3, true, true))
        assertFalse(recovery.shouldRebuild(-6, true, true))
        assertEquals(2, recovery.attempts)
    }

    @Test fun onlyThreeConsecutiveZeroWritesRecover() {
        val recovery = AudioOutputRecovery()
        repeat(2) { assertFalse(recovery.shouldRebuild(0, true, true)) }
        recovery.successfulWrite()
        repeat(2) { assertFalse(recovery.shouldRebuild(0, true, true)) }
        assertTrue(recovery.shouldRebuild(0, true, true))
    }
}
