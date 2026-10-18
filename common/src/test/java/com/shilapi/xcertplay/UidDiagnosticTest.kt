package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class UidDiagnosticTest {
    @Test fun readingOrdinaryShellOrRootUidDoesNotClaimSystemIdentity() {
        for (uid in listOf(10000, 2000, 0, 110000)) {
            assertFalse("UID $uid is not system", UidDiagnostic(uid, uid, "com.example").isSystem)
        }
        assertTrue(UidDiagnostic(1000, 1000, "com.example").isSystem)
        assertTrue("secondary Android user retains system appId", UidDiagnostic(101000, 101000, "com.example").isSystem)
    }
    @Test fun missingAndMismatchedIdentityAreNotReportedAsVerified() {
        assertFalse(UidDiagnostic(null, null, "com.example").matchesInstallation)
        assertFalse(UidDiagnostic(10000, 10001, "com.example").matchesInstallation)
        assertFalse(UidDiagnostic(null, 1000, "com.example").isSystem)
        assertTrue(UidDiagnostic(10000, 10000, "com.example").matchesInstallation)
    }
}
