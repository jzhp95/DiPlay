package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MfiDiagnosticCheckTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingMaterialsReportCertificateAndKeySeparatelyWithoutWritingFiles() {
        val directory = temporary.newFolder()
        val before = directory.list()!!.toList()
        val result = MfiDiagnosticCheck.local(directory) { throw FileNotFoundException() }
        assertEquals(LocalMfiDiagnostic.Status.MISSING, result.status)
        assertFalse(result.certificatePresent)
        assertFalse(result.privateKeyPresent)
        assertEquals(before, directory.list()!!.toList())
    }

    @Test fun invalidInstalledMaterialsDoNotFallBackToBundledIdentity() {
        val directory = temporary.newFolder()
        java.io.File(directory, "identity.pk8").writeText("invalid private material")
        java.io.File(directory, "certificate.p7b").writeText("invalid certificate material")
        val result = MfiDiagnosticCheck.local(directory) { error("Must not fall back to assets") }
        assertEquals(LocalMfiDiagnostic.Source.INSTALLED, result.source)
        assertEquals(LocalMfiDiagnostic.Status.INVALID, result.status)
        assertTrue(result.certificatePresent)
        assertTrue(result.privateKeyPresent)
        assertNull(result.certificateBytes)
    }

    @Test fun aRespondingChipWithoutAReadableCertificateIsNotReportedReady() {
        val result = MfiDiagnosticCheck.coprocessor(registers { address, register, length ->
            if (register == 0x00 && address == 0x11) byteArrayOf(7)
            else if (register == 0x02) byteArrayOf(2)
            else if (register == 0x30) byteArrayOf(0, 0)
            else ByteArray(length) { 0xff.toByte() }
        })
        assertEquals(MfiCoprocessorDiagnostic.Status.CERTIFICATE_UNREADABLE, result.status)
        assertEquals(0x11, result.address7Bit)
        assertNull(result.certificateBytes)
    }

    @Test fun certificateBearingSecondAddressWinsOverFirstRespondingAddress() {
        val result = MfiDiagnosticCheck.coprocessor(registers { address, register, length ->
            when (register) {
                0x00 -> byteArrayOf(7)
                0x02 -> byteArrayOf(2)
                0x30 -> if (address == 0x11) byteArrayOf(0, 3) else byteArrayOf(0, 0)
                0x31 -> byteArrayOf(1, 2, 3)
                else -> throw I2cTransportException.Protocol("Unexpected register $register/$length")
            }
        })
        assertEquals(MfiCoprocessorDiagnostic.Status.READABLE, result.status)
        assertEquals(0x11, result.address7Bit)
        assertEquals(3, result.certificateBytes)
        assertEquals(2, result.protocolMajor)
    }

    @Test fun anInterruptedProbeDoesNotContinueToAnotherAddress() {
        var calls = 0
        Thread.currentThread().interrupt()
        try {
            val result = MfiDiagnosticCheck.coprocessor(object : I2cTransport {
                override fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray {
                    calls++
                    return ByteArray(readLength)
                }
            })
            assertEquals(MfiCoprocessorDiagnostic.Status.CANCELLED, result.status)
            assertEquals(0, calls)
        } finally { Thread.interrupted() }
    }

    @Test fun missingBundledCertificateStillReportsThePresentKeyWithoutInstallingIt() {
        val directory = java.io.File(temporary.root, "not-installed")
        val result = MfiDiagnosticCheck.local(directory) { name ->
            if (name == "identity.pk8") "key".byteInputStream() else throw FileNotFoundException()
        }
        assertEquals(LocalMfiDiagnostic.Source.BUNDLED, result.source)
        assertEquals(LocalMfiDiagnostic.Status.MISSING, result.status)
        assertFalse(result.certificatePresent)
        assertTrue(result.privateKeyPresent)
        assertFalse(directory.exists())
    }

    @Test fun anInstalledPathThatIsAFileIsInvalidAndCannotFallBackToBundledMaterials() {
        val path = temporary.newFile("offline-mfi")
        path.writeText("damaged installation")
        val result = MfiDiagnosticCheck.local(path) { error("An installed path must not fall back to assets") }
        assertEquals(LocalMfiDiagnostic.Source.INSTALLED, result.source)
        assertEquals(LocalMfiDiagnostic.Status.INVALID, result.status)
        assertNull(result.certificateBytes)
        assertEquals("damaged installation", path.readText())
    }

    @Test fun aFloatingBusDoesNotCountAsARespondingChip() {
        val result = MfiDiagnosticCheck.coprocessor(registers { _, _, length -> ByteArray(length) { 0xff.toByte() } })
        assertEquals(MfiCoprocessorDiagnostic.Status.NOT_FOUND, result.status)
        assertNull(result.address7Bit)
        assertNull(result.certificateBytes)
    }

    @Test fun losingExclusiveAccessStopsBeforeTheNextBusTransaction() {
        var busCalls = 0
        val result = MfiDiagnosticCheck.coprocessor(object : I2cTransport {
            override fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray {
                busCalls++
                return ByteArray(readLength) { 7 }
            }
        }) { busCalls == 0 }
        assertEquals(MfiCoprocessorDiagnostic.Status.CANCELLED, result.status)
        assertEquals("Losing ownership after register select must prevent the following read", 1, busCalls)
    }

    private fun registers(read: (Int, Int, Int) -> ByteArray): I2cTransport = object : I2cTransport {
        private var register = -1
        override fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray {
            if (writeData.isNotEmpty()) {
                assertEquals("Diagnostics must only select a register, never write a challenge", 1, writeData.size)
                register = writeData.single().toInt() and 0xff
                return ByteArray(0)
            }
            return read(address7Bit, register, readLength)
        }
    }
}
