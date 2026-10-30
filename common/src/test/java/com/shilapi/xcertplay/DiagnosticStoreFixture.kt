package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.diagnostics.DiagnosticRunCoordinator
import com.shilapi.xcertplay.diagnostics.DiagnosticRunStore
import org.junit.Assert.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/** Each Robolectric application has its own files and main Looper; never reuse a previous store. */
internal object DiagnosticStoreFixture {
    fun reset() {
        val previous=ReflectionHelpers.getField<DiagnosticRunCoordinator?>(DecoderDiagnosticsRun,"state")
        previous?.cancelPending()
        if(previous!=null)ReflectionHelpers.getField<DiagnosticRunStore>(previous,"store").close()
        ReflectionHelpers.setField(DecoderDiagnosticsRun,"state",null)
    }
    fun recover(context:Context) {
        val owner=DecoderDiagnosticsRun.coordinator(context)
        var finished=false
        owner.recover{finished=true}
        val deadline=System.nanoTime()+5_000_000_000L
        while(!finished && !owner.unavailable && System.nanoTime()<deadline){
            shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)
        }
        assertFalse("Recovery failed: ${owner.snapshot()}",owner.unavailable)
        assertTrue("Recovery did not finish: ${owner.snapshot()}",finished)
        assertFalse(owner.recovering)
    }
}
