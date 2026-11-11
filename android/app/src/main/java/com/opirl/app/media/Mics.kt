package com.opirl.app.media

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.StringRes
import com.opirl.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MicKind(@StringRes val label: Int) {
    PHONE(R.string.mic_phone),
    USB(R.string.mic_usb),
    WIRED(R.string.mic_headset),
    BLUETOOTH(R.string.mic_bluetooth),
}

data class Mic(val key: String, val kind: MicKind, val name: String)

class MicPort(val kind: MicKind, val product: String, val address: String)

fun micKind(type: Int): MicKind? = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicKind.PHONE
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> MicKind.USB
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicKind.WIRED
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> MicKind.BLUETOOTH
    else -> null
}

fun micKey(port: MicPort): String =
    if (port.kind == MicKind.PHONE) "PHONE:${port.address}" else "${port.kind.name}:${port.product}:${port.address}"

fun listMics(ports: List<MicPort>): List<Mic> {
    val phones = ports.count { it.kind == MicKind.PHONE }
    return ports.map { port ->
        Mic(micKey(port), port.kind, if (port.kind == MicKind.PHONE) port.address.takeIf { phones > 1 }.orEmpty() else port.product)
    }.distinctBy { it.key }
}

fun findMic(mics: List<Mic>, key: String?): Mic? =
    if (key == null) null else mics.firstOrNull { it.key == key } ?: mics.firstOrNull { it.kind.name == key }

class MicCatalog(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _mics = MutableStateFlow(list())
    val mics: StateFlow<List<Mic>> = _mics.asStateFlow()
    private var communication = false

    init {
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    _mics.value = list()
                }

                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                    _mics.value = list()
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun devices(): List<Pair<AudioDeviceInfo, MicPort>> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { device ->
            val kind = micKind(device.type) ?: return@mapNotNull null
            device to MicPort(kind, device.productName?.toString()?.trim().orEmpty(), device.address.orEmpty())
        }

    private fun list(): List<Mic> = listMics(devices().map { it.second })

    @Synchronized
    fun open(key: String?): MicInput {
        val devices = devices()
        val mic = findMic(listMics(devices.map { it.second }), key)
        val device = mic?.let { wanted -> devices.firstOrNull { micKey(it.second) == wanted.key }?.first }
        val bluetooth = device != null && mic.kind == MicKind.BLUETOOTH
        if (bluetooth) {
            communication = true
            if (Build.VERSION.SDK_INT >= 31) {
                if (!audioManager.setCommunicationDevice(device)) Log.w(TAG, "Bluetooth microphone not accepted")
            } else {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
            }
        } else {
            close()
        }
        return MicInput(device, if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.CAMCORDER)
    }

    @Synchronized
    fun close() {
        if (!communication) return
        communication = false
        if (Build.VERSION.SDK_INT >= 31) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
    }

    private companion object {
        const val TAG = "MicCatalog"
    }
}
