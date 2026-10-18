package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiDiagnostic
import com.shilapi.xcertplay.mfi.MfiCoprocessorDiagnostic
import com.shilapi.xcertplay.mfi.MfiDiagnosticCheck
import com.shilapi.xcertplay.transport.Ch341DeviceMatcher
import com.shilapi.xcertplay.transport.Ch341I2cTransport
import com.shilapi.xcertplay.transport.Ch341UsbHost
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Owns only diagnostic work. It never starts, stops or changes a CarPlay session or authentication source. */
internal class MfiTestController(context: Context, private val changed: (State) -> Unit) : Closeable {
    enum class Permission { UNKNOWN, MISSING, GRANTED, DENIED }
    enum class Operation { IDLE, REFRESHING, WAITING_PERMISSION, PROBING, SESSION_BUSY, FAILED, CANCELLED, PERMISSION_TIMEOUT }
    data class State(
        val local: LocalMfiDiagnostic? = null,
        val usbAvailable: Boolean? = null,
        val devices: List<String> = emptyList(),
        val permission: Permission = Permission.UNKNOWN,
        val chip: MfiCoprocessorDiagnostic? = null,
        val operation: Operation = Operation.IDLE,
    ) {
        val busy get() = operation in listOf(Operation.REFRESHING, Operation.WAITING_PERMISSION, Operation.PROBING)
    }
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var task: Future<*>? = null
    private var receiver: Closeable? = null
    private var waitingForPermission = false
    private var state = State()

    fun snapshot() = state

    /** Local validation and USB enumeration are passive; neither claims an interface nor asks permission. */
    fun refresh() {
        if (closed) return
        cancelWork()
        val token = generation
        publish(state.copy(operation = Operation.REFRESHING))
        task = worker.submit {
            val local = MfiDiagnosticCheck.local(File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)) { name ->
                context.assets.open("offline-mfi/$name")
            }
            if (!current(token)) return@submit
            val usb = context.getSystemService(UsbManager::class.java)
            val next = try {
                val devices = usb?.let { host(it).discover() }.orEmpty()
                State(local, usb != null, devices.map(Ch341UsbHost::describe), permission(usb, devices))
            } catch (_: Exception) {
                State(local, usb != null, operation = Operation.FAILED)
            }
            post(token) { publish(next) }
        }
    }

    /** Explicitly opens the configured bridge only when no CarPlay session owns the connection. */
    fun probe() {
        if (closed) return
        cancelWork()
        if (CarPlayBackgroundSession.hasSession()) {
            publish(state.copy(operation = Operation.SESSION_BUSY))
            return
        }
        val token = generation
        val usb = context.getSystemService(UsbManager::class.java)
        if (usb == null) {
            publish(state.copy(usbAvailable = false, devices = emptyList(), operation = Operation.IDLE))
            return
        }
        try {
            val host = host(usb) { current(token) && !CarPlayBackgroundSession.hasSession() }
            val devices = host.discover()
            val device = devices.firstOrNull()
            publish(state.copy(usbAvailable = true, devices = devices.map(Ch341UsbHost::describe),
                permission = permission(usb, devices), chip = null, operation = Operation.IDLE))
            if (device == null) return
            if (usb.hasPermission(device)) { open(token, host, device); return }
            waitingForPermission = true
            receiver = host.registerPermissionReceiver { result ->
                if (!current(token) || !waitingForPermission) return@registerPermissionReceiver
                when (result) {
                    is Ch341UsbHost.PermissionResult.Granted -> {
                        waitingForPermission = false
                        unregister()
                        open(token, host, result.device)
                    }
                    is Ch341UsbHost.PermissionResult.Denied -> {
                        waitingForPermission = false
                        unregister()
                        publish(state.copy(permission = Permission.DENIED, operation = Operation.IDLE))
                    }
                }
            }
            when (host.requestPermission(device)) {
                is Ch341UsbHost.PermissionRequest.AlreadyGranted -> {
                    waitingForPermission = false
                    unregister()
                    open(token, host, device)
                }
                is Ch341UsbHost.PermissionRequest.Requested -> {
                    publish(state.copy(operation = Operation.WAITING_PERMISSION))
                    pollPermission(token, usb, host, device, System.nanoTime() + PERMISSION_TIMEOUT_MILLIS * 1_000_000L)
                }
            }
        } catch (_: Exception) {
            waitingForPermission = false
            unregister()
            publish(state.copy(operation = Operation.FAILED))
        }
    }

    private fun pollPermission(token: Int, usb: UsbManager, host: Ch341UsbHost, device: UsbDevice, deadline: Long) {
        main.postDelayed({
            if (!current(token) || !waitingForPermission) return@postDelayed
            if (CarPlayBackgroundSession.hasSession()) {
                waitingForPermission = false
                unregister()
                publish(state.copy(operation = Operation.SESSION_BUSY))
            } else if (usb.hasPermission(device)) {
                waitingForPermission = false
                unregister()
                open(token, host, device)
            } else if (System.nanoTime() >= deadline) {
                waitingForPermission = false
                unregister()
                publish(state.copy(operation = Operation.PERMISSION_TIMEOUT))
            } else pollPermission(token, usb, host, device, deadline)
        }, PERMISSION_POLL_MILLIS)
    }

    private fun open(token: Int, host: Ch341UsbHost, device: UsbDevice) {
        if (!current(token)) return
        if (CarPlayBackgroundSession.hasSession()) {
            publish(state.copy(operation = Operation.SESSION_BUSY))
            return
        }
        publish(state.copy(permission = Permission.GRANTED, operation = Operation.PROBING))
        task = worker.submit {
            if (!current(token)) return@submit
            if (CarPlayBackgroundSession.hasSession()) {
                post(token) { publish(state.copy(operation = Operation.SESSION_BUSY)) }
                return@submit
            }
            // Already on our worker: keep open, all I2C calls and close in the same cancellable task.
            try {
                host.openAsync(device, Executor { it.run() }) { result ->
                when (result) {
                    is Ch341UsbHost.OpenResult.Connected -> result.session.use { session ->
                        if (!current(token)) return@use
                        if (CarPlayBackgroundSession.hasSession()) {
                            post(token) { publish(state.copy(operation = Operation.SESSION_BUSY)) }
                            return@use
                        }
                        try {
                            // Leave RST untouched; the deployed bridge latches the certificate-bearing address at power-up.
                            val chip = MfiDiagnosticCheck.coprocessor(Ch341I2cTransport(session)) {
                                current(token) && !CarPlayBackgroundSession.hasSession()
                            }
                            post(token) {
                                val operation = if (chip.status == MfiCoprocessorDiagnostic.Status.CANCELLED) {
                                    if (CarPlayBackgroundSession.hasSession()) Operation.SESSION_BUSY else Operation.CANCELLED
                                } else Operation.IDLE
                                publish(state.copy(chip = chip, operation = operation))
                            }
                        } catch (_: Exception) {
                            post(token) { publish(state.copy(operation = Operation.FAILED)) }
                        }
                    }
                    is Ch341UsbHost.OpenResult.Failed -> post(token) { publish(state.copy(operation = if (CarPlayBackgroundSession.hasSession()) Operation.SESSION_BUSY else Operation.FAILED)) }
                    }
                }
            } catch (_: Exception) {
                post(token) { publish(state.copy(operation = if (CarPlayBackgroundSession.hasSession()) Operation.SESSION_BUSY else Operation.FAILED)) }
            }
        }
    }

    fun cancel() {
        val wasBusy = state.busy
        cancelWork()
        if (wasBusy) publish(state.copy(operation = Operation.CANCELLED))
    }
    private fun cancelWork() {
        generation++
        waitingForPermission = false
        task?.cancel(true)
        task = null
        main.removeCallbacksAndMessages(null)
        unregister()
        // An opened USB session belongs to the worker's use block and closes there, off the UI thread.
    }
    private fun unregister() {
        receiver?.let { runCatching { it.close() } }
        receiver = null
    }
    private fun permission(usb: UsbManager?, devices: List<UsbDevice>) = when {
        usb == null || devices.isEmpty() -> Permission.UNKNOWN
        usb.hasPermission(devices.first()) -> Permission.GRANTED
        else -> Permission.MISSING
    }
    private fun host(usb: UsbManager, canOpen: () -> Boolean = { true }) = Ch341UsbHost(context, usb,
        Ch341DeviceMatcher(listOf(UsbDeviceId(0x1a86, 0x5512))), "${context.packageName}.DIAGNOSTICS_CH341_PERMISSION", diagnostic = true, canOpen = canOpen)
    private fun current(token: Int) = !closed && generation == token && !Thread.currentThread().isInterrupted
    private fun post(token: Int, action: () -> Unit) {
        if (current(token)) main.post { if (current(token)) action() }
    }
    private fun publish(next: State) { state = next; if (!closed) changed(next) }
    override fun close() { closed = true; cancelWork(); worker.shutdownNow() }
    companion object {
        private const val PERMISSION_POLL_MILLIS = 500L
        private const val PERMISSION_TIMEOUT_MILLIS = 15_000L
    }
}
