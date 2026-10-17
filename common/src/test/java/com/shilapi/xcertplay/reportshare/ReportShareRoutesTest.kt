package com.shilapi.xcertplay.reportshare

import com.shilapi.xcertplay.airplay.RtspMessage
import org.junit.Assert.*
import org.junit.Test

/** Route contract without requiring a listening socket. */
class ReportShareRoutesTest {
    private fun request(path: String, method: String = "GET", report: () -> ByteArray = { "诊断报告".toByteArray() }): Triple<Int, Map<String, String>, ByteArray> {
        val server = ReportShareServer("<html>report</html>".toByteArray(), report)
        server.javaClass.getDeclaredField("token").apply { isAccessible = true }.set(server, "test-token")
        val respond = server.javaClass.getDeclaredMethod("respond", RtspMessage.Request::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return respond.invoke(server, RtspMessage.Request(method, path, "HTTP/1.1", emptyMap(), "{}".toByteArray())) as Triple<Int, Map<String, String>, ByteArray>
    }

    @Test fun settingsRoutesAreRemovedIncludingOldTokenPaths() {
        for (prefix in listOf("/r/test-token", "/b/test-token", "")) {
            for (route in listOf("import", "export")) {
                for (method in listOf("GET", "POST")) assertEquals(404, request("$prefix/$route", method).first)
            }
        }
    }

    @Test fun reportDownloadRetainsAuthorizationAndAttachmentHeaders() {
        val (status, headers, body) = request("/r/test-token/report")
        assertEquals(200, status)
        assertEquals("诊断报告", body.toString(Charsets.UTF_8))
        assertEquals("text/plain; charset=utf-8", headers["Content-Type"])
        assertEquals("no-store", headers["Cache-Control"])
        assertTrue(headers["Content-Disposition"]!!.contains(ReportShareServer.REPORT_FILE))
        assertEquals(404, request("/r/wrong-token/report").first)
        assertEquals(404, request("/report").first)
        assertEquals(404, request("/r/test-token/report", "POST").first)
    }

    @Test fun openingPageDoesNotGenerateReportUntilDownload() {
        var generated = 0
        val page = request("/r/test-token/", report = { generated++; ByteArray(0) })
        assertEquals(200, page.first)
        assertEquals(0, generated)
        assertEquals("<html>report</html>", page.third.toString(Charsets.UTF_8))
    }
}
