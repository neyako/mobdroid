package com.opirl.app.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.opirl.app.MainActivity
import com.opirl.app.R

class StreamService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stop = intent?.action == ACTION_STOP
        if (!stop || !foreground) {
            enterForeground(intent?.getBooleanExtra(EXTRA_USB, false) == true, intent?.getStringExtra(EXTRA_TITLE) ?: "Live")
            foreground = true
        }
        if (stop) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opirl:live")
                .apply { acquire() }
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "opirl:live")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(usb: Boolean, title: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Live stream", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_live)
            .setContentTitle(title)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        startForeground(
            1,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                if (usb) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        wifiLock?.release()
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_USB = "usb"
        const val EXTRA_TITLE = "title"
        const val ACTION_STOP = "com.opirl.app.STOP"
        private const val CHANNEL = "live"
    }
}
