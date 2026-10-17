package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DetailedDebugRecorderTest {
    @Test fun firstEnableCreatesThePrivateDirectoryBeforeWriting() {
        val parent = Files.createTempDirectory("detailed-debug-first-enable").toFile()
        val dir = parent.resolve("not-created-yet")
        try {
            DetailedDebugRecorder(dir, "test-process").use { log ->
                assertFalse(dir.exists())
                log.setEnabled(true)
                log.record("wheel", "first key")
                assertTrue(log.report().contains("first key"))
                assertTrue(dir.resolve("diplay.log").isFile)
            }
        } finally { parent.deleteRecursively() }
    }

    @Test fun disabledByDefaultAndDisablingKeepsOnlyAlreadyAcceptedEvents() = withRecorder { log ->
        log.record("connection", "not-recorded-before")
        log.setEnabled(true)
        log.record("wheel", "NEXT sent=true", 1_000L)
        log.setEnabled(false)
        log.record("wheel", "not-recorded-after")
        val report = log.report()
        assertTrue(report.contains("NEXT sent=true"))
        assertTrue(report.contains("recording enabled"))
        assertTrue(report.contains("recording disabled"))
        assertFalse(report.contains("not-recorded"))
        assertTrue(report.contains("recording=false"))
    }

    @Test fun exportedEventsHaveOrderingProcessAndWallClockTime() = withRecorder { log ->
        log.setEnabled(true)
        log.record("connection", "session active", 1_000L)
        log.record("focus", "granted=true", 2_000L)
        log.record("wheel", "NEXT sent=true", 3_000L)
        val report = log.report()
        assertTrue(report.contains("process=test-process"))
        assertTrue(report.contains("epochMs=1000"))
        assertTrue(report.indexOf("session active") < report.indexOf("granted=true"))
        assertTrue(report.indexOf("granted=true") < report.indexOf("NEXT sent=true"))
        assertTrue(report.contains("writerFlushed=true"))
    }

    @Test fun secretsAreRejectedAndAddressesRedactedBeforeStorage() = withRecorder { log ->
        log.setEnabled(true)
        log.record("connection", "peer=192.168.49.1 active=true")
        log.record("connection", "password=secret-value")
        log.record("protocol", "TRACE payload bytes")
        log.record("protocol", "bad\nprivate line")
        val report = log.report()
        assertTrue(report.contains("peer=[ip]"))
        assertFalse(report.contains("192.168.49.1"))
        assertFalse(report.contains("secret-value"))
        assertFalse(report.contains("payload bytes"))
        assertFalse(report.contains("private line"))
    }

    @Test fun reopeningRetainsEvidenceFromBeforeAProcessRestart() {
        val dir = Files.createTempDirectory("detailed-debug-restart").toFile()
        try {
            DetailedDebugRecorder(dir, "old-process").use { old ->
                old.setEnabled(true)
                old.record("connection", "previous failure")
                assertTrue(old.report().contains("previous failure"))
            }
            DetailedDebugRecorder(dir, "new-process").use { current ->
                current.setEnabled(true)
                current.record("connection", "next attempt")
                val report = current.report()
                assertTrue(report.contains("previous failure"))
                assertTrue(report.contains("next attempt"))
            }
        } finally { dir.deleteRecursively() }
    }

    private fun withRecorder(block: (DetailedDebugRecorder) -> Unit) {
        val dir = Files.createTempDirectory("detailed-debug").toFile()
        try { DetailedDebugRecorder(dir, "test-process").use(block) }
        finally { dir.deleteRecursively() }
    }
}
