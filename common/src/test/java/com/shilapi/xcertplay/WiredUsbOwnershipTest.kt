package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WiredUsbOwnershipTest {
    private val controllers = mutableListOf<CarPlayController>()

    @After fun tearDown() {
        controllers.forEach { it.close(); assertTrue(it.awaitClosed(2_000)) }
    }

    @Test fun closedControllerReleasesBothNewUsbPaths() {
        val controller = controller()
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        val usb = mock(Iap2UsbSession::class.java)
        val ncm = mock(NcmUsbBridge::class.java)
        controller.javaClass.getDeclaredMethod("runStack", Iap2UsbSession::class.java, NcmUsbBridge::class.java)
            .apply { isAccessible = true }.invoke(controller, usb, ncm)
        verify(usb).close()
        verify(ncm).close()
    }

    @Test fun closeDuringMuxOpenClosesTheUnpublishedMux() {
        val controller = controller()
        val usb = mock(Iap2UsbSession::class.java)
        val ncm = mock(NcmUsbBridge::class.java)
        val mux = mock(Iap2UsbMuxHost::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                runWithMux(controller, usb, ncm) {
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    mux
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        } finally {
            release.countDown()
            worker.join(2_000)
        }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError("USB worker failed", it) }
        verify(mux).close()
        verify(usb, never()).close() // Closing the mux closes its owned USB pipe.
        verify(ncm).close()
    }

    @Test fun successfullyPublishedMuxBelongsToControllerTeardown() {
        val controller = controller()
        val usb = mock(Iap2UsbSession::class.java)
        val ncm = mock(NcmUsbBridge::class.java)
        val mux = mock(Iap2UsbMuxHost::class.java)
        // End bring-up immediately after publication without opening any phone service.
        doAnswer { throw IOException("pairing unavailable") }.`when`(mux).connect(anyInt(), anyLong())
        runWithMux(controller, usb, ncm) { mux }
        verify(mux, never()).close()
        verify(usb, never()).close()
        verify(ncm).close()
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        verify(mux, times(1)).close()
    }

    @Test fun muxOpenFailureReleasesBothLocallyOwnedPaths() {
        val controller = controller()
        val usb = mock(Iap2UsbSession::class.java)
        val ncm = mock(NcmUsbBridge::class.java)
        runWithMux(controller, usb, ncm) { throw IOException("USBMUX open failed") }
        verify(usb).close()
        verify(ncm).close()
    }

    private fun runWithMux(
        controller: CarPlayController,
        usb: Iap2UsbSession,
        ncm: NcmUsbBridge,
        open: (Iap2UsbSession) -> Iap2UsbMuxHost,
    ) {
        controller.javaClass.getDeclaredMethod("runStackWithMux", Iap2UsbSession::class.java,
            NcmUsbBridge::class.java, kotlin.jvm.functions.Function1::class.java)
            .apply { isAccessible = true }.invoke(controller, usb, ncm, open)
    }

    private fun controller(): CarPlayController = CarPlayController(
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
        },
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
    ).also(controllers::add)
}
