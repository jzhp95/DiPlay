package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticLeaseOwnershipTest {
    @Test fun timedOutOwnerBlocksAllEntrypointsUntilActualRelease() {
        val session=Any();val first=checkNotNull(DiagnosticLease.acquire("a",session))
        var exited=false
        try {
            DiagnosticLease.release(first,false){exited}
            assertTrue(DiagnosticLease.busy());assertNull(DiagnosticLease.acquire("b",Any()))
            exited=true
            val second=checkNotNull(DiagnosticLease.acquire("b",session))
            try {assertEquals(first.sessionGeneration,second.sessionGeneration)}
            finally {DiagnosticLease.release(second,true)}
        } finally {exited=true;DiagnosticLease.release(first,true);DiagnosticLease.busy()}
    }
    @Test fun lateReleaseCannotClearNewOwnerAndOldSessionCannotRestoreNewOne() {
        val a=Any();val b=Any();val old=checkNotNull(DiagnosticLease.acquire("a",a))
        assertFalse(DiagnosticLease.owns(old,b))
        DiagnosticLease.release(old,true)
        val current=checkNotNull(DiagnosticLease.acquire("b",b))
        try {
            DiagnosticLease.release(old,true)
            assertTrue(DiagnosticLease.busy());assertTrue(DiagnosticLease.owns(current,b))
            assertTrue(current.sessionGeneration>old.sessionGeneration)
        } finally {DiagnosticLease.release(current,true)}
    }
}
