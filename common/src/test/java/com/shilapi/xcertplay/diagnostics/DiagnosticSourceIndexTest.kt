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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DiagnosticSourceIndexTest {
    @get:Rule val temporary=TemporaryFolder()
    private val first="a".repeat(64)
    private val second="b".repeat(64)
    @Test fun repeatedPathsAndRepeatedRunsReadEachAssetOnlyOncePerApk() {
        val root=temporary.newFolder();val index=DiagnosticSourceIndex(root);var opened=0
        val value=index.load(first,listOf("sample","sample")){opened++;"abc".byteInputStream()}
        assertEquals(1,opened)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",value.getJSONObject("hashes").getString("sample"))
        index.load(first,listOf("sample")){error("cache should avoid opening unchanged asset")}
        assertEquals(1,opened)
    }
    @Test fun sameFilenameFromNewApkIsRehashedInsteadOfReusingOldContent() {
        val index=DiagnosticSourceIndex(temporary.newFolder())
        val old=index.load(first,listOf("sample")){"abc".byteInputStream()}
        val fresh=index.load(second,listOf("sample")){"changed".byteInputStream()}
        assertNotEquals(old.getJSONObject("hashes").getString("sample"),fresh.getJSONObject("hashes").getString("sample"))
        assertEquals(second,fresh.getString("apkSha256"))
    }
    @Test fun incompleteOrCorruptIndexCannotSupplyStaleHashesWhenInputReadFails() {
        val root=temporary.newFolder();val index=DiagnosticSourceIndex(root)
        index.load(first,listOf("sample")){"abc".byteInputStream()}
        val file=File(root,"$first.json")
        file.writeText(JSONObject(file.readText()).put("hashes",JSONObject().put("sample","0".repeat(64))).toString())
        try {
            index.load(first,listOf("sample")){throw java.io.IOException("asset_unavailable")}
            fail("corrupt cache must not be accepted")
        } catch(expected:java.io.IOException){assertEquals("asset_unavailable",expected.message)}
    }
    @Test fun failedSyncDoesNotPublishAPartialIndexAndNextAttemptRecomputes() {
        val root=temporary.newFolder();val disk=DiagnosticRunStore.DiskFileAccess
        val access=object:DiagnosticRunStore.FileAccess by disk {
            override fun sync(file:File){throw java.io.IOException("disk_full")}
        }
        try {
            DiagnosticSourceIndex(root,access).load(first,listOf("sample")){"abc".byteInputStream()}
            fail("sync failure must propagate")
        } catch(expected:java.io.IOException){assertEquals("disk_full",expected.message)}
        assertFalse(File(root,"$first.json").exists())
        var opened=0
        DiagnosticSourceIndex(root).load(first,listOf("sample")){opened++;"abc".byteInputStream()}
        assertEquals(1,opened)
    }
}
