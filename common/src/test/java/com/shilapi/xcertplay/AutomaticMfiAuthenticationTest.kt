package com.shilapi.xcertplay

import android.content.*
import android.content.res.AssetManager
import android.hardware.usb.*
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.*
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowUsbDeviceConnection
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [AutomaticMfiAuthenticationTest.Bridge::class,
    AutomaticMfiAuthenticationTest.Connection::class, DiPlayAuthenticationTest.NavigationOutputs::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AutomaticMfiAuthenticationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: ProbeContext
    private lateinit var controller: CarPlayController
    private val statuses = mutableListOf<CarPlayStatus>()
    private var closed = false
    @Before fun setup() {
        context = ProbeContext(temporary.newFolder())
        Bridge.failure = ""; Bridge.calls = 0; Bridge.signatureCommands = 0
        Connection.closes = 0; Connection.beforeClose = {}
    }
    @After fun cleanup() {
        if (::controller.isInitialized && !closed) { controller.close(); assertTrue(controller.awaitClosed(3_000)) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun anAbsentCh341UsesTheInstalledOfflineIdentity() {
        offline()
        start()
        assertLocal()
        assertFalse(statuses.contains(CarPlayStatus.WaitingForMfi))
        assertFalse(context.receivers.values.any { it.hasAction(permissionAction()) })
    }

    @Test fun wirelessWithoutAUsbServiceCanStillLoadOfflineAuthentication() {
        context = ProbeContext(temporary.newFolder(), hasUsb = false)
        offline()
        start(CarPlayTransport.WIRELESS)
        assertTrue(statuses.contains(CarPlayStatus.MfiReady))
        assertFalse(statuses.filterIsInstance<CarPlayStatus.Failed>().any { it.message.contains("USB service") })
        assertLocal()
    }

    @Test fun aHealthyCh341DoesNotRequireAnyOfflineMaterials() {
        val invalid = File(context.noBackupFilesDir, "offline-mfi").apply { mkdirs() }
        File(invalid, "identity.pk8").writeText("invalid test material")
        device(granted = true)
        start()
        assertTrue(statuses.contains(CarPlayStatus.MfiReady))
        assertArrayEquals(byteArrayOf(1, 2, 3), session().client.readCertificate())
        assertTrue("The automatic path must check signing before selecting the chip", Bridge.signatureCommands > 0)
        assertEquals("The chip remains owned until shutdown", 0, Connection.closes)
    }

    @Test fun aSelectedChipFailureReportsOneFailureWithoutSwitchingIdentity() {
        offline(); device(granted = true); start()
        val selected = session()
        Bridge.failure = "signature"
        repeat(2) { runCatching { selected.client.signChallenge(byteArrayOf(1, 2, 3)) } }
        settle()
        assertSame("An active exchange must never switch its certificate source", selected, session())
        assertEquals(1, statuses.filterIsInstance<CarPlayStatus.Failed>().size)
        assertEquals(1, statuses.count { it == CarPlayStatus.MfiReady })
    }

    @Test fun anExplicitNewAttemptCanRecoverAfterASelectedChipFailure() {
        offline(); device(granted = true); start()
        Bridge.failure = "signature"
        runCatching { session().client.signChallenge(byteArrayOf(1)) }
        settle()
        Bridge.failure = ""
        controller.reconnectMfi(); settle()
        assertEquals(2, statuses.count { it == CarPlayStatus.MfiReady })
    }

    @Test fun aPhoneAuthenticationRejectionIsClassifiedForTheSameBoundedRecovery() {
        offline(); start()
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "fail",
            ReflectionHelpers.ClassParameter.from(Throwable::class.java,
                com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationException("iPhone sent AuthenticationFailed")),
            ReflectionHelpers.ClassParameter.from(Int::class.javaObjectType, null))
        settle()
        assertTrue(statuses.filterIsInstance<CarPlayStatus.Failed>().single().authenticationFailure)
    }

    @Test fun aQueuedFailureFromThePreviousAttemptCannotFailTheNewAttempt() {
        offline(); device(granted = true); start()
        Bridge.failure = "signature"
        runCatching { session().client.signChallenge(byteArrayOf(1)) }
        Bridge.failure = ""
        controller.reconnectMfi()
        settle()
        assertFalse(statuses.any { it is CarPlayStatus.Failed })
        assertEquals(2, statuses.count { it == CarPlayStatus.MfiReady })
    }

    @Test fun aRetiredChipFailureCannotInvalidateTheReplacementIdentity() {
        offline(); device(granted = true); start()
        val retired = session()
        controller.reconnectMfi(); settle()
        Bridge.failure = "signature"
        runCatching { retired.client.signChallenge(byteArrayOf(1, 2, 3)) }
        settle()
        assertFalse(statuses.any { it is CarPlayStatus.Failed })
    }

    @Test fun aDeniedUsbGrantFallsBackOnceAndIgnoresTheLateGrant() {
        offline()
        val device = device(granted = false)
        start()
        assertTrue(statuses.contains(CarPlayStatus.RequestingMfiPermission))
        val receiver = context.receivers.entries.single { it.value.hasAction(permissionAction()) }.key
        receiver.onReceive(context, permission(device, false))
        settle()
        assertLocal()
        receiver.onReceive(context, permission(device, true))
        settle()
        assertEquals(1, statuses.count { it == CarPlayStatus.MfiReady })
        assertEquals(0, Bridge.calls)
        assertFalse(context.receivers.containsKey(receiver))
    }

    @Test fun certificateReadFailureClosesUsbBeforeUsingOffline() {
        offline(); device(granted = true); Bridge.failure = "certificate"
        start()
        assertLocal()
        assertEquals(1, Connection.closes)
        assertFalse(statuses.any { it is CarPlayStatus.Failed })
    }

    @Test fun signatureCommunicationFailureAlsoFallsBackToOffline() {
        offline(); device(granted = true); Bridge.failure = "signature"
        start()
        assertLocal()
        assertEquals(1, Connection.closes)
        assertTrue(Bridge.signatureCommands > 0)
    }

    @Test fun anOpenFailureFallsBackWithoutClaimingReadinessForTheChip() {
        offline()
        device(granted = true, endpoints = false)
        start()
        assertLocal()
        assertEquals(0, Bridge.calls)
    }

    @Test fun offlineFailureIsReportedWithoutEndlessUsbPolling() {
        start()
        assertTrue(statuses.filterIsInstance<CarPlayStatus.Failed>().single().authenticationFailure)
        assertFalse(statuses.contains(CarPlayStatus.MfiReady))
        assertFalse(statuses.contains(CarPlayStatus.WaitingForMfi))
    }

    @Test fun closingDuringPermissionWaitPreventsLateFallbackOrUsbOpen() {
        offline()
        val device = device(granted = false)
        start()
        val receiver = context.receivers.entries.single { it.value.hasAction(permissionAction()) }.key
        controller.close(); assertTrue(controller.awaitClosed(3_000)); closed = true
        receiver.onReceive(context, permission(device, false))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(statuses.contains(CarPlayStatus.MfiReady))
        assertEquals(0, Bridge.calls)
    }

    @Test fun anUnansweredPermissionPromptFallsBackAfterTheAutomaticDeadline() {
        offline(); device(granted = false)
        start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        settle()
        assertLocal()
        assertFalse(context.receivers.values.any { it.hasAction(permissionAction()) })
    }

    @Test fun reconnectingRechecksCh341AfterAnOfflineFallback() {
        offline(); start()
        assertLocal()
        device(granted = true)
        controller.reconnectMfi(); settle()
        assertArrayEquals(byteArrayOf(1, 2, 3), session().client.readCertificate())
        assertEquals(2, statuses.count { it == CarPlayStatus.MfiReady })
    }

    @Test fun reconnectingDuringPermissionWaitReplacesTheReceiverAndRejectsOldResults() {
        offline()
        val device = device(granted = false)
        start()
        val old = context.receivers.entries.single { it.value.hasAction(permissionAction()) }.key
        controller.reconnectMfi(); settle()
        val current = context.receivers.entries.single { it.value.hasAction(permissionAction()) }.key
        assertNotSame("A new attempt needs its own permission receiver", old, current)
        old.onReceive(context, permission(device, false)); settle()
        assertFalse(statuses.contains(CarPlayStatus.MfiReady))
        shadowOf(context.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        current.onReceive(context, permission(device, true)); settle()
        assertArrayEquals(byteArrayOf(1, 2, 3), session().client.readCertificate())
        assertEquals(1, statuses.count { it == CarPlayStatus.MfiReady })
    }

    @Test fun anOldUsbFailureBlockedOnTheLockCannotChangeTheNewAttempt() {
        offline(); device(granted = true); Bridge.failure = "certificate"
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        Connection.beforeClose = {
            worker.set(Thread.currentThread()); closing.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            start(wait = false)
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            val lock = ReflectionHelpers.getField<Any>(controller, "lifecycleLock")
            synchronized(lock) {
                release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (worker.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, worker.get().state)
                Connection.beforeClose = {}
                Bridge.failure = ""
                controller.reconnectMfi()
            }
            settle()
            assertArrayEquals("The new attempt must retain its healthy chip", byteArrayOf(1, 2, 3), session().client.readCertificate())
            assertEquals(1, statuses.count { it == CarPlayStatus.MfiReady })
        } finally { release.countDown(); Connection.beforeClose = {} }
    }

    @Test fun absentInstallationLoadsBundledMaterialsWithoutInstallingThem() {
        val fixture = temporary.newFolder()
        createSyntheticMfiIdentity(fixture)
        val apk = temporary.newFile("test-assets.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            for (name in listOf("identity.pk8", "certificate.p7b")) {
                zip.putNextEntry(ZipEntry("assets/offline-mfi/$name"))
                zip.write(File(fixture, name).readBytes()); zip.closeEntry()
            }
        }
        context.bundled = ReflectionHelpers.newInstance(AssetManager::class.java).also {
            ReflectionHelpers.callInstanceMethod<Int>(it, "addAssetPath",
                ReflectionHelpers.ClassParameter.from(String::class.java, apk.absolutePath))
        }
        start()
        assertTrue(statuses.contains(CarPlayStatus.MfiReady))
        assertArrayEquals(File(fixture, "certificate.p7b").readBytes(), session().client.readCertificate())
        assertFalse(File(context.noBackupFilesDir, "offline-mfi").exists())
    }

    private fun offline() = createSyntheticMfiIdentity(File(context.noBackupFilesDir, "offline-mfi"))
    private fun assertLocal() {
        assertTrue("Authentication never became ready: $statuses", statuses.contains(CarPlayStatus.MfiReady))
        assertArrayEquals(File(context.noBackupFilesDir, "offline-mfi/certificate.p7b").readBytes(), session().client.readCertificate())
    }
    private fun session() = ReflectionHelpers.getField<MfiSession>(controller, "mfiSession")
    private fun start(transport: CarPlayTransport = CarPlayTransport.WIRED, wait: Boolean = true) {
        controller = CarPlayController(context, CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.AUTO, transport = transport,
            ch341Devices = listOf(UsbDeviceId(0x1a86, 0x5512)),
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, statuses::add)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "startMfi")
        if (wait) settle()
    }
    private fun settle() {
        repeat(4) {
            ReflectionHelpers.getField<ExecutorService>(controller, "executor").submit {}.get(8, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun permissionAction() = "${context.packageName}.CH341_USB_PERMISSION"
    private fun permission(device: UsbDevice, granted: Boolean) = Intent(permissionAction())
        .putExtra(UsbManager.EXTRA_DEVICE, device).putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, granted)
    private fun device(granted: Boolean, endpoints: Boolean = true): UsbDevice {
        fun endpoint(address: Int) = ReflectionHelpers.newInstance(UsbEndpoint::class.java).apply {
            ReflectionHelpers.setField(this, "mAddress", address)
            ReflectionHelpers.setField(this, "mAttributes", UsbConstants.USB_ENDPOINT_XFER_BULK)
            ReflectionHelpers.setField(this, "mMaxPacketSize", 32)
        }
        val intf = ReflectionHelpers.newInstance(UsbInterface::class.java).apply {
            ReflectionHelpers.setField(this, "mEndpoints", if (endpoints) arrayOf(endpoint(0x81), endpoint(0x02)) else emptyArray<UsbEndpoint>())
        }
        val conf = ReflectionHelpers.newInstance(UsbConfiguration::class.java).apply {
            ReflectionHelpers.setField(this, "mInterfaces", arrayOf(intf))
        }
        return ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", "/dev/bus/usb/001/005")
            ReflectionHelpers.setField(this, "mVendorId", 0x1a86)
            ReflectionHelpers.setField(this, "mProductId", 0x5512)
            ReflectionHelpers.setField(this, "mConfigurations", arrayOf(conf))
            shadowOf(context.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(this, granted)
        }
    }
    private class ProbeContext(private val directory: File, private val hasUsb: Boolean = true) :
        ContextWrapper(RuntimeEnvironment.getApplication()) {
        val receivers = mutableMapOf<BroadcastReceiver, IntentFilter>()
        var bundled: AssetManager? = null
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getAssets(): AssetManager = bundled ?: super.getAssets()
        override fun getSystemService(name: String): Any? = if (name == USB_SERVICE && !hasUsb) null else super.getSystemService(name)
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
            if (receiver != null) receivers[receiver] = filter
            return super.registerReceiver(receiver, filter)
        }
        override fun registerReceiver(receiver:BroadcastReceiver?,filter:IntentFilter,permission:String?,handler:android.os.Handler?):Intent? {
            if(receiver!=null)receivers[receiver]=filter
            if(permission!=null)assertEquals("${packageName}.USB_PERMISSION_RESULT",permission)
            return super.registerReceiver(receiver,filter,permission,handler)
        }
        override fun registerReceiver(receiver:BroadcastReceiver?,filter:IntentFilter,flags:Int):Intent? {
            if(receiver!=null)receivers[receiver]=filter
            return super.registerReceiver(receiver,filter,flags)
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) { receivers.remove(receiver); super.unregisterReceiver(receiver) }
    }
    @Implements(Ch341I2cTransport::class, isInAndroidSdk = false)
    class Bridge {
        companion object { var failure = ""; var calls = 0; var signatureCommands = 0 }
        private var register = 0
        @Implementation fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray {
            calls++
            if (writeData.isNotEmpty()) {
                register = writeData[0].toInt() and 0xff
                if (register == 0x10 && writeData.size > 1) signatureCommands++
                return ByteArray(0)
            }
            if (failure == "certificate" && register == 0x31 || failure == "signature" && register == 0x12)
                throw I2cTransportException.DeviceUnavailable("Synthetic bridge transfer failure")
            return when (register) {
                0x00 -> byteArrayOf(7)
                0x01 -> byteArrayOf(1)
                0x02 -> byteArrayOf(3)
                0x30 -> byteArrayOf(0, 3)
                0x31 -> byteArrayOf(1, 2, 3)
                0x10 -> byteArrayOf(0x10)
                0x11 -> byteArrayOf(0, 64)
                0x12 -> ByteArray(64) { 1 }
                else -> ByteArray(readLength)
            }
        }
    }
    @Implements(UsbDeviceConnection::class)
    class Connection : ShadowUsbDeviceConnection() {
        companion object { var closes = 0; var beforeClose: () -> Unit = {} }
        @Implementation override fun claimInterface(intf: UsbInterface, force: Boolean): Boolean = true
        @Implementation override fun releaseInterface(intf: UsbInterface): Boolean = true
        @Implementation(methodName = "close") fun closeConnection() { closes++; beforeClose() }
    }
}
