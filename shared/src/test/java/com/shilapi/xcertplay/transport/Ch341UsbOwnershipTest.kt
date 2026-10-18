package com.shilapi.xcertplay.transport

import android.hardware.usb.*
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.shadows.ShadowUsbDeviceConnection
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [Ch341UsbOwnershipTest.Manager::class, Ch341UsbOwnershipTest.Connection::class])
class Ch341UsbOwnershipTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val usb get() = context.getSystemService(UsbManager::class.java)
    private val matcher = Ch341DeviceMatcher(listOf(UsbDeviceId(0x1a86, 0x5512)))
    private val direct = Executor { it.run() }
    @Before fun setup() { Manager.beforeReturn = {}; Connection.claims = 0; Connection.closes = 0; Connection.forced = false }

    @Test fun anOpenSessionCannotBeStolenByADiagnosticHost() {
        val device = device()
        val first = Ch341UsbHost(context, usb, matcher)
        val second = Ch341UsbHost(context, usb, matcher, diagnostic = true)
        val owner = connected(first, device)
        try {
            var result: Ch341UsbHost.OpenResult? = null
            second.openAsync(device, direct) { result = it }
            assertTrue("An active USB owner must keep its interface", result is Ch341UsbHost.OpenResult.Failed)
            assertEquals(1, Connection.claims)
        } finally { owner.close() }
        connected(second, device).close()
        assertEquals(2, Connection.claims)
        assertFalse("Diagnostics must never detach another USB driver", Connection.forced)
    }

    @Test fun cancellationDuringOpenClosesWithoutClaimingAndReleasesOwnership() {
        val device = device()
        var allowed = true
        Manager.beforeReturn = { allowed = false }
        val host = Ch341UsbHost(context, usb, matcher, diagnostic = true, canOpen = { allowed })
        var result: Ch341UsbHost.OpenResult? = null
        host.openAsync(device, direct) { result = it }
        assertTrue(result is Ch341UsbHost.OpenResult.Failed)
        assertEquals(0, Connection.claims)
        assertEquals(1, Connection.closes)
        Manager.beforeReturn = {}
        allowed = true
        connected(host, device).close()
        assertEquals(1, Connection.claims)
        assertFalse(Connection.forced)
    }

    @Test fun aNormalConnectionWaitsForTheProbeToReleaseItsSession() {
        val device = device()
        val probe = connected(Ch341UsbHost(context, usb, matcher, diagnostic = true), device)
        val normal = Ch341UsbHost(context, usb, matcher)
        val opening = FutureTask { connected(normal, device) }
        val thread = Thread(opening).apply { start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (thread.state != Thread.State.TIMED_WAITING && !opening.isDone && System.nanoTime() < deadline) Thread.yield()
            assertFalse("The connection must wait instead of stealing the probe", opening.isDone)
            probe.close()
            opening.get(3, TimeUnit.SECONDS).close()
            probe.close()
            assertEquals("Each session closes once", 2, Connection.closes)
        } finally { probe.close(); thread.interrupt(); thread.join(3_000) }
    }

    private fun connected(host: Ch341UsbHost, device: UsbDevice): Ch341UsbSession {
        var result: Ch341UsbHost.OpenResult? = null
        host.openAsync(device, direct) { result = it }
        assertTrue("USB open failed: $result", result is Ch341UsbHost.OpenResult.Connected)
        return (result as Ch341UsbHost.OpenResult.Connected).session
    }
    private fun device(): UsbDevice {
        fun endpoint(address: Int) = ReflectionHelpers.newInstance(UsbEndpoint::class.java).apply {
            ReflectionHelpers.setField(this, "mAddress", address)
            ReflectionHelpers.setField(this, "mAttributes", UsbConstants.USB_ENDPOINT_XFER_BULK)
            ReflectionHelpers.setField(this, "mMaxPacketSize", 32)
        }
        val intf = ReflectionHelpers.newInstance(UsbInterface::class.java).apply {
            ReflectionHelpers.setField(this, "mEndpoints", arrayOf(endpoint(0x81), endpoint(0x02)))
        }
        val configuration = ReflectionHelpers.newInstance(UsbConfiguration::class.java).apply {
            ReflectionHelpers.setField(this, "mInterfaces", arrayOf(intf))
        }
        return ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", "/dev/bus/usb/001/005")
            ReflectionHelpers.setField(this, "mVendorId", 0x1a86)
            ReflectionHelpers.setField(this, "mProductId", 0x5512)
            ReflectionHelpers.setField(this, "mConfigurations", arrayOf(configuration))
            shadowOf(usb).addOrUpdateUsbDevice(this, true)
        }
    }
    @Implements(UsbManager::class)
    class Manager : ShadowUsbManager() {
        companion object { var beforeReturn: () -> Unit = {} }
        @Implementation override fun openDevice(device: UsbDevice): UsbDeviceConnection? =
            super.openDevice(device).also { beforeReturn() }
    }
    @Implements(UsbDeviceConnection::class)
    class Connection : ShadowUsbDeviceConnection() {
        companion object { var claims = 0; var closes = 0; var forced = false }
        @Implementation override fun claimInterface(intf: UsbInterface, force: Boolean): Boolean {
            claims++; forced = force; return true
        }
        @Implementation override fun releaseInterface(intf: UsbInterface): Boolean = true
        @Implementation(methodName = "close") fun closeConnection() { closes++ }
    }
}
