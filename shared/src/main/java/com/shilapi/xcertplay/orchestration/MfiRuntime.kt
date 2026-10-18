package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiDeviceScanner
import com.shilapi.xcertplay.transport.I2cTransport
import java.io.Closeable
import java.io.IOException

/** Signals the normal, retryable state where the MFi address probe found no chip. */
internal class MfiCoprocessorNotFoundException(
    message: String = "No MFi authentication coprocessor responded to the device probe",
) : IOException(message)

/** An opened MFi coprocessor client plus the handle that releases its backing transport. */
class MfiSession(
    val client: MfiAuthenticator,
    private val closeable: Closeable?,
) : Closeable {
    override fun close() {
        closeable?.close()
    }
}

/** Runs the documented address probe and wraps the first responding MFi coprocessor. */
object MfiRuntime {
    fun scan(transport: I2cTransport): MfiAuthenticationClient {
        val discovery = MfiDeviceScanner(
            transport = transport,
            probeTimeoutMillis = MFI_STARTUP_PROBE_TIMEOUT_MILLIS,
        ).scan()
        val chip = discovery.chip
            ?: throw MfiCoprocessorNotFoundException(
                "No MFi authentication coprocessor responded: " +
                    discovery.failures.joinToString { "0x${it.address7Bit.toString(16)}=${it.error}" },
            )
        return MfiAuthenticationClient(transport, chip.address7Bit)
    }

    private const val MFI_STARTUP_PROBE_TIMEOUT_MILLIS = 15_000L
}

/** Pins one identity and retires it after the first provider failure. Never retries an exchange. */
internal class SessionMfiAuthenticator(
    private val delegate: MfiAuthenticator,
    private val onFailure: (Exception) -> Unit,
) : MfiAuthenticator {
    private val lock = Any()
    private var failed: Exception? = null
    private fun <T> call(operation: () -> T): T = synchronized(lock) {
        failed?.let { throw it }
        try {
            operation()
        } catch (error: Exception) {
            failed = error
            onFailure(error)
            throw error
        }
    }
    override val certificateType get() = call { delegate.certificateType }
    override fun protocolMajor() = call { delegate.protocolMajor() }
    override fun readCertificate(maximumOutputLength: Int) = call { delegate.readCertificate(maximumOutputLength) }
    override fun signChallenge(challenge: ByteArray) = call { delegate.signChallenge(challenge) }
    override fun baaCertificates() = call { delegate.baaCertificates() }
}
