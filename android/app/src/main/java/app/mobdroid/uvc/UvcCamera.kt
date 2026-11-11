package app.mobdroid.uvc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class UvcCamera private constructor(
    private val context: Context,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val handle: Long,
    private val modes: List<UvcMode>,
) : AutoCloseable {
    val name: String = device.productName ?: device.deviceName
    val formats: List<UvcFormat> = modes.map { it.format }

    @Volatile
    var onError: ((String) -> Unit)? = null

    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val main = Handler(Looper.getMainLooper())
    private val worker = ScheduledThreadPoolExecutor(
        1,
        ThreadFactory { Thread(it, "uvc-worker").apply { isDaemon = true } },
    ).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private var streaming = false
    private var watchdog: ScheduledFuture<*>? = null

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val detached = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (detached?.deviceName == device.deviceName) {
                fail("The camera was unplugged")
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun start(format: UvcFormat, surface: Surface) {
        val mode = modes.firstOrNull { it.format == format }
            ?: throw IllegalArgumentException("The camera does not offer $format")
        check(!closed.get()) { "The camera is closed" }
        try {
            call(START_TIMEOUT_MS) {
                check(!closed.get()) { "The camera is closed" }
                stopStream()
                UvcNative.start(handle, format.mjpeg, format.width, format.height, mode.fpsMatch, surface)
                streaming = true
                scheduleWatchdog()
            }
        } catch (e: TimeoutException) {
            abandonStart()
            throw IOException("Timed out starting $format", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            abandonStart()
            throw IOException("Interrupted while starting $format", e)
        }
    }

    fun stop() {
        if (closed.get()) return
        try {
            call(STOP_TIMEOUT_MS) { stopStream() }
        } catch (_: TimeoutException) {
        } catch (_: IllegalStateException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onError = null
        try {
            context.unregisterReceiver(detachReceiver)
        } catch (_: IllegalArgumentException) {
        }
        val teardown = try {
            worker.submit { teardown() }
        } catch (_: RejectedExecutionException) {
            return
        }
        try {
            teardown.get(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
        } catch (_: ExecutionException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun <T> call(timeoutMs: Long, block: () -> T): T {
        val future = try {
            worker.submit(Callable { block() })
        } catch (e: RejectedExecutionException) {
            throw IllegalStateException("The camera is closed", e)
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun abandonStart() {
        try {
            worker.execute { stopStream() }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun stopStream() {
        watchdog?.cancel(false)
        watchdog = null
        if (streaming) {
            streaming = false
            UvcNative.stop(handle)
        }
    }

    private fun teardown() {
        try {
            stopStream()
            UvcNative.close(handle)
        } finally {
            connection.close()
            worker.shutdown()
        }
    }

    private fun scheduleWatchdog() {
        val delay = max(STALL_MS - UvcNative.idleMs(handle), MIN_CHECK_MS)
        watchdog = worker.schedule(Runnable { checkStall() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun checkStall() {
        if (closed.get() || !streaming) return
        if (UvcNative.idleMs(handle) >= STALL_MS) {
            fail("The camera stopped sending frames")
        } else {
            scheduleWatchdog()
        }
    }

    private fun fail(message: String) {
        if (closed.get() || !failed.compareAndSet(false, true)) return
        main.post { onError?.invoke(message) }
    }

    companion object {
        private const val START_TIMEOUT_MS = 10_000L
        private const val STOP_TIMEOUT_MS = 1_500L
        private const val CLOSE_TIMEOUT_MS = 3_000L
        private const val STALL_MS = 2_000L
        private const val MIN_CHECK_MS = 250L

        fun open(context: Context, device: UsbDevice): UvcCamera {
            val appContext = context.applicationContext
            val manager = appContext.getSystemService(UsbManager::class.java)
            val connection = manager.openDevice(device) ?: throw IOException("Cannot open ${device.deviceName}")
            var handle = 0L
            try {
                handle = UvcNative.open(connection.fileDescriptor)
                val modes = UvcModes.parse(UvcNative.frameDescriptors(handle))
                return UvcCamera(appContext, device, connection, handle, modes)
            } catch (e: Throwable) {
                if (handle != 0L) UvcNative.close(handle)
                connection.close()
                throw e
            }
        }
    }
}
