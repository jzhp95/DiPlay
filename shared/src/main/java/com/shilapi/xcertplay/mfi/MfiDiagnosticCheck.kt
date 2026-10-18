package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.transport.I2cTransport
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Results contain only presence, validation and sizes; never identity or protocol payload bytes. */
data class LocalMfiDiagnostic(
    val source: Source,
    val certificatePresent: Boolean,
    val privateKeyPresent: Boolean,
    val status: Status,
    val certificateBytes: Int? = null,
) {
    enum class Source { INSTALLED, BUNDLED }
    enum class Status { MISSING, INVALID, READY }
}

data class MfiCoprocessorDiagnostic(
    val status: Status,
    val address7Bit: Int? = null,
    val protocolMajor: Int? = null,
    val certificateBytes: Int? = null,
) {
    enum class Status { NOT_FOUND, CERTIFICATE_UNREADABLE, READABLE, CANCELLED }
}

/** Standalone, blocking checks. Call from a worker and interrupt it when its owner leaves. */
object MfiDiagnosticCheck {
    fun local(directory: File, openBundledFile: (String) -> InputStream): LocalMfiDiagnostic {
        if (directory.exists() && !directory.isDirectory) return LocalMfiDiagnostic(
            LocalMfiDiagnostic.Source.INSTALLED, false, false, LocalMfiDiagnostic.Status.INVALID)
        val installed = directory.isDirectory
        val source = if (installed) LocalMfiDiagnostic.Source.INSTALLED else LocalMfiDiagnostic.Source.BUNDLED
        val openFile: (String) -> InputStream = if (installed) {
            { name -> File(directory, name).inputStream() }
        } else openBundledFile
        fun present(name: String) = try {
            openFile(name).use { true }
        } catch (_: IOException) { false }
          catch (_: SecurityException) { false }
        val certificate = present("certificate.p7b")
        val key = present("identity.pk8")
        if (!certificate || !key) return LocalMfiDiagnostic(source, certificate, key, LocalMfiDiagnostic.Status.MISSING)
        return try {
            val client = LocalMfiAuthenticationClient.load(openFile)
            LocalMfiDiagnostic(source, true, true, LocalMfiDiagnostic.Status.READY, client.readCertificate().size)
        } catch (_: Exception) {
            LocalMfiDiagnostic(source, true, true, LocalMfiDiagnostic.Status.INVALID)
        }
    }

    fun coprocessor(transport: I2cTransport, canContinue: () -> Boolean = { true }): MfiCoprocessorDiagnostic = try {
        inspectCoprocessor(object : I2cTransport {
            override fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray {
                if (Thread.currentThread().isInterrupted || !canContinue()) throw ProbeCancelled()
                return transport.transaction(address7Bit, writeData, readLength)
            }
        })
    } catch (_: ProbeCancelled) {
        MfiCoprocessorDiagnostic(MfiCoprocessorDiagnostic.Status.CANCELLED)
    }

    private fun inspectCoprocessor(transport: I2cTransport): MfiCoprocessorDiagnostic {
        var responding: MfiCoprocessorDiagnostic? = null
        for (address in MfiDeviceScanner.DEFAULT_CANDIDATE_ADDRESSES) {
            if (Thread.currentThread().isInterrupted) return MfiCoprocessorDiagnostic(MfiCoprocessorDiagnostic.Status.CANCELLED)
            val discovery = MfiDeviceScanner(transport, listOf(address), PROBE_TIMEOUT_MILLIS).scan()
            if (discovery.interrupted) return MfiCoprocessorDiagnostic(MfiCoprocessorDiagnostic.Status.CANCELLED)
            if (discovery.chip == null) continue
            val client = MfiAuthenticationClient(transport, address)
            var major: Int? = null
            try {
                major = client.protocolMajor()
                val bytes = client.readCertificate(MAX_CERTIFICATE_BYTES)
                return MfiCoprocessorDiagnostic(MfiCoprocessorDiagnostic.Status.READABLE, address, major, bytes.size)
            } catch (cancelled: ProbeCancelled) {
                throw cancelled
            } catch (_: Exception) {
                if (Thread.currentThread().isInterrupted) return MfiCoprocessorDiagnostic(MfiCoprocessorDiagnostic.Status.CANCELLED)
                if (responding == null) responding = MfiCoprocessorDiagnostic(
                    MfiCoprocessorDiagnostic.Status.CERTIFICATE_UNREADABLE, address, major)
            }
        }
        return responding ?: MfiCoprocessorDiagnostic(MfiCoprocessorDiagnostic.Status.NOT_FOUND)
    }

    private class ProbeCancelled : RuntimeException()
    private const val PROBE_TIMEOUT_MILLIS = 500L
    private const val MAX_CERTIFICATE_BYTES = 1280
}
