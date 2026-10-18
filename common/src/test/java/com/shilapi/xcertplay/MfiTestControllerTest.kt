package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import com.shilapi.xcertplay.mfi.LocalMfiDiagnostic
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class MfiTestControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun passiveRefreshDoesNotInstallMaterialsOrRequestUsbAccess() {
        val context = ProbeContext(temporary.newFolder())
        val usb = context.getSystemService(UsbManager::class.java)
        val device = device()
        shadowOf(usb).addOrUpdateUsbDevice(device, false)
        val controller = MfiTestController(context) {}
        try {
            controller.refresh()
            settle(controller)
            assertEquals(LocalMfiDiagnostic.Status.MISSING, controller.snapshot().local?.status)
            assertEquals(1, controller.snapshot().devices.size)
            assertEquals(MfiTestController.Permission.MISSING, controller.snapshot().permission)
            assertNull(controller.snapshot().chip)
            assertTrue(context.receivers.isEmpty())
            assertFalse(usb.hasPermission(device))
            assertFalse(File(context.noBackupFilesDir, "offline-mfi").exists())
        } finally { controller.close() }
    }

    @Test fun absentHardwareIsSeparateFromMissingUsbService() {
        val context = ProbeContext(temporary.newFolder())
        val controller = MfiTestController(context) {}
        try {
            controller.refresh(); settle(controller)
            assertEquals(true, controller.snapshot().usbAvailable)
            assertTrue(controller.snapshot().devices.isEmpty())
            assertNull(controller.snapshot().chip)
        } finally { controller.close() }
        val unavailable = MfiTestController(ProbeContext(temporary.newFolder(), hasUsb = false)) {}
        try {
            unavailable.refresh(); settle(unavailable)
            assertEquals(false, unavailable.snapshot().usbAvailable)
            assertFalse(unavailable.snapshot().busy)
        } finally { unavailable.close() }
    }

    @Test fun anActiveCarPlaySessionBlocksProbeWithoutRequestingPermissionOrDisconnecting() {
        val owner = CarPlayBackgroundSession
        val previous = ReflectionHelpers.getField<Any?>(owner, "stopAction")
        var disconnected = false
        val stopAction: ((() -> Unit) -> Unit) = { disconnected = true }
        ReflectionHelpers.setField(owner, "stopAction", stopAction)
        val context = ProbeContext(temporary.newFolder())
        val controller = MfiTestController(context) {}
        try {
            controller.probe()
            assertEquals(MfiTestController.Operation.SESSION_BUSY, controller.snapshot().operation)
            assertTrue(context.receivers.isEmpty())
            assertFalse(disconnected)
        } finally {
            controller.close()
            ReflectionHelpers.setField(owner, "stopAction", previous)
        }
    }

    @Test fun cancellingAnAuthorizationUnregistersAndIgnoresItsLateGrant() {
        val context = ProbeContext(temporary.newFolder())
        val usb = context.getSystemService(UsbManager::class.java)
        val device = device()
        shadowOf(usb).addOrUpdateUsbDevice(device, false)
        val controller = MfiTestController(context) {}
        try {
            controller.probe()
            assertEquals(MfiTestController.Operation.WAITING_PERMISSION, controller.snapshot().operation)
            val receiver = context.receivers.keys.single()
            controller.cancel()
            assertTrue(context.receivers.isEmpty())
            receiver.onReceive(context, permission(context, device, true))
            settle(controller)
            assertEquals(MfiTestController.Operation.CANCELLED, controller.snapshot().operation)
            assertNull(controller.snapshot().chip)
        } finally { controller.close() }
    }

    @Test fun aDeniedPermissionDoesNotPretendThatTheChipWasTested() {
        val context = ProbeContext(temporary.newFolder())
        val usb = context.getSystemService(UsbManager::class.java)
        val device = device()
        shadowOf(usb).addOrUpdateUsbDevice(device, false)
        val controller = MfiTestController(context) {}
        try {
            controller.probe()
            context.receivers.keys.single().onReceive(context, permission(context, device, false))
            assertEquals(MfiTestController.Permission.DENIED, controller.snapshot().permission)
            assertFalse(controller.snapshot().busy)
            assertNull(controller.snapshot().chip)
            assertTrue(context.receivers.isEmpty())
        } finally { controller.close() }
    }

    @Test fun closingDuringAuthorizationRemovesItsReceiverAndCallbacks() {
        val context = ProbeContext(temporary.newFolder())
        val usb = context.getSystemService(UsbManager::class.java)
        val device = device()
        shadowOf(usb).addOrUpdateUsbDevice(device, false)
        var updates = 0
        val controller = MfiTestController(context) { updates++ }
        controller.probe()
        val receiver = context.receivers.keys.single()
        controller.close()
        val before = updates
        receiver.onReceive(context, permission(context, device, true))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(context.receivers.isEmpty())
        assertEquals(before, updates)
    }

    private fun settle(controller: MfiTestController) {
        ReflectionHelpers.getField<ExecutorService>(controller, "worker").submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun device(): UsbDevice = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
        ReflectionHelpers.setField(this, "mName", "/dev/bus/usb/001/005")
        ReflectionHelpers.setField(this, "mVendorId", 0x1a86)
        ReflectionHelpers.setField(this, "mProductId", 0x5512)
    }
    private fun permission(context: Context, device: UsbDevice, granted: Boolean) =
        Intent("${context.packageName}.DIAGNOSTICS_CH341_PERMISSION")
            .putExtra(UsbManager.EXTRA_DEVICE, device).putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, granted)

    private class ProbeContext(private val directory: File, private val hasUsb: Boolean = true) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val receivers = mutableMapOf<BroadcastReceiver, IntentFilter>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSystemService(name: String): Any? = if (name == USB_SERVICE && !hasUsb) null else super.getSystemService(name)
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
            if (receiver != null) receivers[receiver] = filter
            return super.registerReceiver(receiver, filter)
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            receivers.remove(receiver)
            super.unregisterReceiver(receiver)
        }
    }
}
