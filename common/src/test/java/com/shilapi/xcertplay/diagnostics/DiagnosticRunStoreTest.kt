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
class DiagnosticRunStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "11111111-1111-1111-1111-111111111111"
    private val second = "22222222-2222-2222-2222-222222222222"
    private fun operation(revision: Long = 1, run: String = id) = DiagnosticStoreOperation(run, revision, "op-$revision")
    private fun bytes(run: String = id, value: Int = 7) = "{\"id\":\"$run\",\"position\":$value}".toByteArray()
    private fun await(latch: CountDownLatch) { assertTrue("worker did not reach controlled boundary", latch.await(5, TimeUnit.SECONDS)) }
    private fun take(queue: LinkedBlockingQueue<() -> Unit>) = checkNotNull(queue.poll(5, TimeUnit.SECONDS))
    private fun success(value: DiagnosticStoreResult?) = value as DiagnosticStoreResult.Success

    @Test fun checkpointsCopyBytesAndRecoverOnlyCommittedRevision() {
        val root = temporary.newFolder()
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        DiagnosticRunStore(root, { callbacks.add(it) }).use { store ->
            store.beginRun(id)
            val data = bytes()
            var result: DiagnosticStoreResult? = null
            store.checkpoint(operation(), data) { result = it }
            data.fill(0)
            take(callbacks).invoke()
            assertEquals(7, JSONObject(success(result).payload!!).getInt("position"))
            File(root, "$id/revisions/999.json.writing").writeText("incomplete")
            store.loadLatest("recover") { result = it }; take(callbacks).invoke()
            assertEquals(7, JSONObject(success(result).payload!!).getInt("position"))
            store.sealSnapshot(operation()) { result = it }; take(callbacks).invoke()
            assertTrue(result is DiagnosticStoreResult.Success)
            assertEquals(7, JSONObject(File(root, "$id/sealed/1.json").readText()).getInt("position"))
        }
    }

    @Test fun cancellationDuringPointerSyncCannotCommitTheLatestIndex() {
        val root=temporary.newFolder();val callbacks=LinkedBlockingQueue<() -> Unit>()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val disk=DiagnosticRunStore.DiskFileAccess
        val access=object:DiagnosticRunStore.FileAccess by disk {
            override fun sync(file: File) {
                if(file.name=="store-latest.json.writing") {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
                disk.sync(file)
            }
        }
        val store=DiagnosticRunStore(root,{callbacks.add(it)},access)
        try {
            store.beginRun(id)
            var result:DiagnosticStoreResult?=null
            store.checkpoint(operation(),bytes()){result=it}
            await(entered);store.invalidate();release.countDown()
            take(callbacks).invoke()
            assertTrue(result is DiagnosticStoreResult.Stale)
            assertFalse(File(root,"store-latest.json").exists())
            assertTrue(File(root,"$id/revisions/1.json").isFile)
        } finally {release.countDown();store.close()}
    }

    @Test fun corruptCommittedPayloadIsRejectedInsteadOfPresentedAsRecovered() {
        val root = temporary.newFolder(); val callbacks = LinkedBlockingQueue<() -> Unit>()
        DiagnosticRunStore(root, { callbacks.add(it) }).use { store ->
            store.beginRun(id); store.checkpoint(operation(), bytes()) {}; take(callbacks).invoke()
            val file = File(root, "$id/revisions/1.json")
            file.writeText(JSONObject(file.readText()).put("payload", "{\"id\":\"$id\",\"position\":99}").toString())
            var result: DiagnosticStoreResult? = null
            store.loadLatest("recover") { result = it }; take(callbacks).invoke()
            assertTrue(result is DiagnosticStoreResult.Failure)
            assertTrue((result as DiagnosticStoreResult.Failure).reason.contains("digest"))
        }
    }

    @Test fun blockedSyncDoesNotBlockStateThreadAndOldCompletionIsStale() {
        val root = temporary.newFolder(); val callbacks = LinkedBlockingQueue<() -> Unit>()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        val access = object : DiagnosticRunStore.FileAccess by DiagnosticRunStore.DiskFileAccess {
            override fun sync(file: File) { check(Thread.currentThread() !== caller); entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); DiagnosticRunStore.DiskFileAccess.sync(file) }
        }
        val store = DiagnosticRunStore(root, { callbacks.add(it) }, access)
        try {
            store.beginRun(id)
            var result: DiagnosticStoreResult? = null
            store.checkpoint(operation(), bytes()) { result = it }; await(entered)
            // State thread can invalidate/cancel and adopt B while the real writer is blocked.
            store.beginRun(second)
            assertNull(result)
            release.countDown(); take(callbacks).invoke()
            assertTrue(result is DiagnosticStoreResult.Stale)
            assertFalse(File(root, "store-latest.json").exists())
        } finally { release.countDown(); store.close() }
    }

    @Test fun queueRejectsBeyond32WithoutStartingAnotherWriter() {
        val root = temporary.newFolder(); val callbacks = LinkedBlockingQueue<() -> Unit>()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val access = object : DiagnosticRunStore.FileAccess by DiagnosticRunStore.DiskFileAccess {
            override fun sync(file: File) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val store = DiagnosticRunStore(root, { callbacks.add(it) }, access)
        try {
            store.beginRun(id); store.checkpoint(operation(), bytes()) {}; await(entered)
            repeat(32) { store.checkpoint(operation(it + 2L), bytes()) {} }
            var rejected: DiagnosticStoreResult? = null
            store.checkpoint(operation(34), bytes()) { rejected = it }
            take(callbacks).invoke()
            assertEquals("storage_queue_full_or_closed", (rejected as DiagnosticStoreResult.Failure).reason)
        } finally { store.close(); release.countDown() }
    }

    @Test fun deliveredCallbackChecksCurrentIdentityAgainAndCloseRejectsWork() {
        val root = temporary.newFolder(); val callbacks = LinkedBlockingQueue<() -> Unit>()
        val store = DiagnosticRunStore(root, { callbacks.add(it) })
        store.beginRun(id)
        var result: DiagnosticStoreResult? = null
        store.checkpoint(operation(), bytes()) { result = it }
        val queued = take(callbacks)
        store.invalidate(); queued.invoke()
        assertTrue(result is DiagnosticStoreResult.Stale)
        store.close()
        store.checkpoint(operation(2), bytes()) { result = it }; take(callbacks).invoke()
        assertTrue(result is DiagnosticStoreResult.Stale)
        assertFalse(File(root, "$id/revisions/2.json").exists())
    }

    @Test fun lowerRevisionDoesNotReplaceLatestAndNewRunRestartsItsOwnRevision() {
        val root = temporary.newFolder(); val callbacks = LinkedBlockingQueue<() -> Unit>()
        DiagnosticRunStore(root, { callbacks.add(it) }).use { store ->
            store.beginRun(id)
            store.checkpoint(operation(9), bytes(value = 9)) {}; take(callbacks).invoke()
            store.checkpoint(operation(1), bytes(value = 1)) {}; take(callbacks).invoke()
            var result: DiagnosticStoreResult? = null
            store.loadLatest("latest-a") { result = it }; take(callbacks).invoke()
            assertEquals(9, JSONObject(success(result).payload!!).getInt("position"))
            store.beginRun(second)
            store.checkpoint(operation(1, second), bytes(second, 2)) {}; take(callbacks).invoke()
            store.loadLatest("latest-b") { result = it }; take(callbacks).invoke()
            assertEquals(second, JSONObject(success(result).payload!!).getString("id"))
        }
    }

    @Test fun writeSyncRenameAndReadFailuresAreExplicitAndUncommittedFilesStayUnsealed() {
        for (fault in listOf("write", "sync", "rename", "read")) {
            val root = temporary.newFolder(); val callbacks = LinkedBlockingQueue<() -> Unit>()
            val disk = DiagnosticRunStore.DiskFileAccess
            val access = object : DiagnosticRunStore.FileAccess by disk {
                override fun write(file: File, bytes: ByteArray) { if (fault == "write") error("disk_full"); disk.write(file, bytes) }
                override fun sync(file: File) { if (fault == "sync") error("sync_failed"); disk.sync(file) }
                override fun rename(from: File, to: File) { if (fault == "rename") error("rename_failed"); disk.rename(from, to) }
                override fun read(file: File): ByteArray { if (fault == "read") error("read_denied"); return disk.read(file) }
            }
            DiagnosticRunStore(root, { callbacks.add(it) }, access).use { store ->
                store.beginRun(id)
                var result: DiagnosticStoreResult? = null
                store.checkpoint(operation(), bytes()) { result = it }; take(callbacks).invoke()
                if (fault == "read") { store.loadLatest("read-fault") { result = it }; take(callbacks).invoke() }
                assertTrue("failure $fault", result is DiagnosticStoreResult.Failure)
                store.sealSnapshot(operation()) { result = it }; take(callbacks).invoke()
                assertTrue(result is DiagnosticStoreResult.Failure)
                assertFalse(File(root, "$id/sealed/1.json").exists())
            }
        }
    }
}
