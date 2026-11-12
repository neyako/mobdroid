package com.opirl.app.control

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.opirl.app.settings.ControllerAction
import com.opirl.app.settings.ControllerFunction
import com.opirl.app.settings.StreamDeckKey
import com.opirl.app.settings.StreamDeckLayout
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "StreamDeck"
private const val TIMEOUT_MS = 1000
private const val READ_TIMEOUT_MS = 2000
private const val HID_SET_REPORT = 0x09
private const val HID_FEATURE = 0x0300
private const val HID_OUTPUT = 0x0200

fun streamDeckKeyBitmap(kind: StreamDeckKind, key: StreamDeckKey): Bitmap {
    val size = kind.pixels
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val background = key.color or 0xFF000000.toInt()
    canvas.drawColor(background)
    if (key.text.isNotBlank()) {
        val bright = Color.red(background) * 299 + Color.green(background) * 587 + Color.blue(background) * 114 > 150_000
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = if (bright) Color.BLACK else Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
        }
        var layout: StaticLayout
        var textSize = size / 3.5f
        do {
            paint.textSize = textSize
            layout = StaticLayout.Builder.obtain(key.text, 0, key.text.length, paint, size - 8)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
            textSize -= 1f
        } while (layout.height > size - 4 && textSize > 8f)
        canvas.translate(4f, (size - layout.height) / 2f)
        layout.draw(canvas)
    }
    return bitmap
}

fun streamDeckKeyImage(kind: StreamDeckKind, key: StreamDeckKey): ByteArray {
    val bitmap = streamDeckKeyBitmap(kind, key)
    val size = kind.pixels
    try {
        if (!kind.jpeg) {
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            return streamDeckBmp(size, streamDeckMiniPixels(size, pixels))
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, size, size, Matrix().apply { postRotate(180f) }, false)
        try {
            return ByteArrayOutputStream(8 * 1024).also { rotated.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            if (rotated !== bitmap) rotated.recycle()
        }
    } finally {
        bitmap.recycle()
    }
}

private class StreamDeckDevice(
    val device: UsbDevice,
    val kind: StreamDeckKind,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint?,
    private val onKey: (Int, Boolean) -> Unit,
) {
    private val writes = LinkedBlockingQueue<() -> Unit>(256)
    private val shown = arrayOfNulls<StreamDeckKey>(kind.keys)

    @Volatile private var closed = false

    fun start() {
        thread("streamdeck-read") { read() }
        thread("streamdeck-write") {
            try {
                while (!closed) runCatching(writes.take())
            } catch (_: InterruptedException) {
            }
        }
        post {
            feature(streamDeckReset(kind))
            feature(streamDeckBrightness(kind, 70))
        }
    }

    fun show(layout: StreamDeckLayout?) = post {
        for (index in 0 until kind.keys) {
            val key = layout?.key(index) ?: StreamDeckKey()
            val wanted = key.copy(action = ControllerAction())
            if (shown[index] == wanted) continue
            val reports = streamDeckImageReports(kind, index, streamDeckKeyImage(kind, wanted))
            if (reports.all(::write)) shown[index] = wanted
        }
    }

    fun close() {
        if (closed) return
        closed = true
        writes.clear()
        writes.offer {}
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    private fun post(task: () -> Unit) {
        if (!closed) writes.offer(task)
    }

    private fun thread(name: String, block: () -> Unit) = Thread(block, name).apply {
        isDaemon = true
        start()
    }

    private fun feature(report: ByteArray): Boolean = connection.controlTransfer(
        UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_DIR_OUT or 0x01,
        HID_SET_REPORT,
        HID_FEATURE or (report[0].toInt() and 0xFF),
        usbInterface.id,
        report,
        report.size,
        TIMEOUT_MS,
    ) >= 0

    private fun write(report: ByteArray): Boolean {
        if (closed) return false
        val sent = if (output != null) {
            connection.bulkTransfer(output, report, report.size, TIMEOUT_MS)
        } else {
            connection.controlTransfer(
                UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_DIR_OUT or 0x01,
                HID_SET_REPORT,
                HID_OUTPUT or (report[0].toInt() and 0xFF),
                usbInterface.id,
                report,
                report.size,
                TIMEOUT_MS,
            )
        }
        return sent >= 0
    }

    private fun read() {
        val buffer = ByteArray(maxOf(input.maxPacketSize, 64))
        var previous = BooleanArray(kind.keys)
        while (!closed) {
            val length = connection.bulkTransfer(input, buffer, buffer.size, READ_TIMEOUT_MS)
            if (length < 0) continue
            val states = streamDeckKeyStates(kind, buffer, length) ?: continue
            for (index in states.indices) {
                if (states[index] != previous[index]) onKey(index, states[index])
            }
            previous = states
        }
    }
}

class StreamDecks(
    context: Context,
    private val layout: () -> StreamDeckLayout?,
    private val perform: (ControllerAction, Boolean) -> Unit,
) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(UsbManager::class.java)
    private val permissionAction = "${this.context.packageName}.STREAM_DECK_PERMISSION"
    private var device: StreamDeckDevice? = null
    private var asked: String? = null
    private var active = false
    private var registered = false
    private val _connected = MutableStateFlow<StreamDeckKind?>(null)
    val connected: StateFlow<StreamDeckKind?> = _connected.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val target = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (granted && target != null) refresh()
        }
    }

    @Synchronized
    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        if (on) {
            refresh()
        } else {
            device?.close()
            device = null
            _connected.value = null
            asked = null
        }
    }

    @Synchronized
    fun refresh() {
        if (!active) return
        val attached = manager.deviceList.values.firstOrNull { StreamDeckKind.of(it.vendorId, it.productId) != null }
        val current = device
        if (current != null && current.device.deviceName == attached?.deviceName) return
        current?.close()
        device = null
        _connected.value = null
        if (attached == null) {
            asked = null
            return
        }
        if (!manager.hasPermission(attached)) {
            if (asked != attached.deviceName) {
                asked = attached.deviceName
                requestPermission(attached)
            }
            return
        }
        open(attached)
    }

    @Synchronized
    fun show() {
        device?.show(layout())
    }

    private fun requestPermission(target: UsbDevice) {
        if (!registered) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(permissionAction), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(permissionAction).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
        runCatching { manager.requestPermission(target, intent) }.onFailure { Log.w(TAG, "Permission request failed", it) }
    }

    private fun open(target: UsbDevice) {
        val kind = StreamDeckKind.of(target.vendorId, target.productId) ?: return
        val usbInterface = (0 until target.interfaceCount).map(target::getInterface)
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID } ?: return
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return
        val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        val connection = runCatching { manager.openDevice(target) }.getOrNull() ?: return
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            return
        }
        val opened = StreamDeckDevice(target, kind, connection, usbInterface, input, output, ::onKey)
        device = opened
        _connected.value = kind
        opened.start()
        opened.show(layout())
    }

    private fun onKey(index: Int, pressed: Boolean) {
        val action = layout()?.key(index)?.action ?: return
        if (action.function != ControllerFunction.UNUSED) perform(action, pressed)
    }
}
