package com.shilapi.xcertplay.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagnosticCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id="11111111-1111-1111-1111-111111111111"
    private val other="22222222-2222-2222-2222-222222222222"
    private fun value(runId: String = id) = JSONObject().put("id",runId).put("status","preflight").put("position",0)
    private fun take(queue: LinkedBlockingQueue<() -> Unit>) = checkNotNull(queue.poll(5,TimeUnit.SECONDS))
    private class Clock {
        data class Timer(val due: Long,val action: () -> Unit,var cancelled: Boolean=false)
        var now=0L
        private val timers=mutableListOf<Timer>()
        fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            val timer=Timer(now+delay,action);timers.add(timer)
            return {timer.cancelled=true}
        }
        fun advance(milliseconds: Long) {
            now+=milliseconds
            val due=timers.filter{!it.cancelled && it.due<=now}
            due.forEach{it.cancelled=true;it.action()}
        }
    }

    @Test fun recoveryCoalescesDuplicateRequestsAndLoadsLegacyWithoutResumingExecution() {
        val root=temporary.newFolder();val directory=File(root,id).apply{mkdirs()}
        File(root,"latest.json").writeText("{\"id\":\"$id\"}")
        File(directory,"run.json").writeText(value().toString())
        val callbacks=LinkedBlockingQueue<() -> Unit>();val clock=Clock()
        DiagnosticRunStore(root,{callbacks.add(it)}).use { store ->
            val owner=DiagnosticRunCoordinator(store,clock::schedule)
            var notified=0
            owner.recover{notified++};owner.recover{notified++}
            assertTrue(owner.recovering);assertEquals(0,notified)
            take(callbacks).invoke()
            assertFalse(owner.recovering);assertEquals(2,notified)
            assertEquals(id,owner.snapshot().getString("id"))
            assertEquals(0,owner.snapshot().getInt("position"))
            assertFalse(File(root,"store-latest.json").exists())
            owner.recover{notified++};assertEquals(3,notified)
            assertTrue(callbacks.isEmpty())
        }
    }

    @Test fun recoveryAllocatesPastOrphanRevisionWithoutAcceptingItsUncommittedState() {
        val root=temporary.newFolder();val callbacks=LinkedBlockingQueue<() -> Unit>();val clock=Clock()
        DiagnosticRunStore(root,{callbacks.add(it)}).use { store ->
            val owner=DiagnosticRunCoordinator(store,clock::schedule);owner.adopt(value())
            owner.checkpoint{};take(callbacks).invoke()
            File(root,"$id/revisions/2.json").writeText("uncommitted_or_corrupt")
            val restored=DiagnosticRunCoordinator(store,clock::schedule)
            restored.recover{};take(callbacks).invoke()
            assertEquals(0,restored.snapshot().getInt("position"))
            restored.checkpoint{};take(callbacks).invoke()
            assertFalse(restored.unavailable)
            assertEquals(3L,restored.snapshot().getLong("storageRevision"))
            assertEquals("uncommitted_or_corrupt",File(root,"$id/revisions/2.json").readText())
        }
    }

    @Test fun onlyAcknowledgedCheckpointContinuesAndSnapshotsCannotMutateOwner() {
        val root=temporary.newFolder();val callbacks=LinkedBlockingQueue<() -> Unit>();val clock=Clock()
        DiagnosticRunStore(root,{callbacks.add(it)}).use { store ->
            val owner=DiagnosticRunCoordinator(store,clock::schedule);owner.adopt(value())
            var continued=false
            owner.checkpoint{continued=true}
            assertFalse(continued)
            owner.snapshot().put("position",99)
            assertEquals(0,owner.snapshot().getInt("position"))
            take(callbacks).invoke();assertTrue(continued)
            assertEquals(1L,owner.snapshot().getLong("storageRevision"))
            clock.advance(5000);assertFalse(owner.unavailable)
        }
    }

    @Test fun lateCheckpointFromACannotContinueOrReplaceB() {
        val callbacks=LinkedBlockingQueue<() -> Unit>();val clock=Clock()
        DiagnosticRunStore(temporary.newFolder(),{callbacks.add(it)}).use { store ->
            val owner=DiagnosticRunCoordinator(store,clock::schedule);owner.adopt(value())
            var continued=false
            owner.checkpoint{continued=true}
            val old=take(callbacks)
            owner.adopt(value(other));old.invoke()
            assertFalse(continued);assertEquals(other,owner.snapshot().getString("id"))
            assertFalse(owner.unavailable)
        }
    }

    @Test fun blockedWriterTimesOutWithoutAnotherWriterAndNeverRevivesAfterRelease() {
        val callbacks=LinkedBlockingQueue<() -> Unit>();val clock=Clock();val root=temporary.newFolder()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val disk=DiagnosticRunStore.DiskFileAccess
        val access=object:DiagnosticRunStore.FileAccess by disk {
            override fun sync(file: File) { entered.countDown();check(release.await(5,TimeUnit.SECONDS));disk.sync(file) }
        }
        val store=DiagnosticRunStore(root,{callbacks.add(it)},access)
        try {
            val owner=DiagnosticRunCoordinator(store,clock::schedule);owner.adopt(value())
            var continued=false;var failed=0
            owner.onStorageFailure={failed++}
            owner.checkpoint{continued=true}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            clock.advance(4999);assertFalse(owner.unavailable)
            clock.advance(1)
            assertEquals(1,failed);assertTrue(owner.unavailable)
            assertEquals("storage_unavailable",owner.snapshot().getString("status"))
            assertEquals("failed",owner.snapshot().getString("publication"))
            owner.checkpoint{continued=true}
            release.countDown();take(callbacks).invoke()
            assertFalse(continued);assertEquals(1,failed)
            assertFalse(File(root,"store-latest.json").exists())
        } finally {release.countDown();store.close()}
    }

    @Test fun sealPublishesCompatibilitySnapshotOnlyAfterValidatedRevisionWasRead() {
        val callbacks=LinkedBlockingQueue<() -> Unit>();val clock=Clock();val root=temporary.newFolder()
        DiagnosticRunStore(root,{callbacks.add(it)}).use { store ->
            val owner=DiagnosticRunCoordinator(store,clock::schedule);owner.adopt(value())
            owner.checkpoint{};take(callbacks).invoke()
            var published=false
            owner.seal{published=true}
            assertFalse(published)
            take(callbacks).invoke() // validated immutable seal acknowledged; compatibility copy queued
            take(callbacks).invoke()
            assertTrue(published)
            assertEquals(1L,JSONObject(File(root,"$id/sealed-run.json").readText()).getLong("storageRevision"))
            assertTrue(File(root,"$id/sealed/1.json").isFile)
        }
    }

    @Test fun readFailureStopsAdvancementAndKeepsAnExplicitUnsavedResult() {
        val callbacks=LinkedBlockingQueue<() -> Unit>();val clock=Clock()
        val access=object:DiagnosticRunStore.FileAccess by DiagnosticRunStore.DiskFileAccess {
            override fun exists(file: File)=true
            override fun read(file: File): ByteArray = throw java.io.IOException("permission_denied")
        }
        DiagnosticRunStore(temporary.newFolder(),{callbacks.add(it)},access).use { store ->
            val owner=DiagnosticRunCoordinator(store,clock::schedule);owner.adopt(value())
            var continued=false
            owner.read("cases/test.json"){continued=true};take(callbacks).invoke()
            assertFalse(continued);assertTrue(owner.unavailable)
            assertTrue(owner.snapshot().getString("publicationError").contains("permission_denied"))
        }
    }
}
