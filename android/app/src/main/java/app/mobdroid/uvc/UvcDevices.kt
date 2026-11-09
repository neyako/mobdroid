package app.mobdroid.uvc

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

class UvcDevices(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(UsbManager::class.java)
    private val permissionAction = "${this.context.packageName}.UVC_PERMISSION"
    private val lock = Any()
    private val pending = HashMap<String, MutableList<(Boolean) -> Unit>>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                ?: return
            val granted = intent.action == permissionAction &&
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            complete(device.deviceName, granted)
        }
    }

    fun list(): List<UsbDevice> = manager.deviceList.values.filter(::hasVideoStreaming)

    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onResult(false)
            return
        }
        synchronized(lock) {
            val callbacks = pending.getOrPut(device.deviceName) { ArrayList() }
            callbacks.add(onResult)
            if (callbacks.size > 1) return
            if (!registered) {
                val filter = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            }
        }
        try {
            manager.requestPermission(device, permissionIntent())
        } catch (_: SecurityException) {
            complete(device.deviceName, false)
        }
    }

    private fun complete(deviceName: String, granted: Boolean) {
        val callbacks = synchronized(lock) {
            val removed = pending.remove(deviceName)
            if (pending.isEmpty() && registered) {
                context.unregisterReceiver(receiver)
                registered = false
            }
            removed
        }
        callbacks?.forEach { it(granted) }
    }

    private fun permissionIntent(): PendingIntent {
        val intent = Intent(permissionAction).setPackage(context.packageName)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }
}

private fun hasVideoStreaming(device: UsbDevice): Boolean = (0 until device.interfaceCount).any {
    val usbInterface = device.getInterface(it)
    usbInterface.interfaceClass == UsbConstants.USB_CLASS_VIDEO && usbInterface.interfaceSubclass == 2
}
