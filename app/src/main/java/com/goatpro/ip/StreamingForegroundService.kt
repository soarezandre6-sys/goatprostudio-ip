package com.goatpro.ip

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class StreamingForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireLocks()
        StreamingCameraLifecycle.setActive(true)

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GOAT Cam transmitindo")
            .setContentText("Câmera e rede mantidas ativas em segundo plano")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        StreamingCameraLifecycle.setActive(true)
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        StreamingCameraLifecycle.setActive(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Transmissão GOAT Cam",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém câmera e transmissão ativas em segundo plano"
                setShowBadge(false)
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GOATCam:StreamingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "GOATCam:StreamingWifiLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
        runCatching {
            wifiLock?.takeIf { it.isHeld }?.release()
        }
        wifiLock = null
    }

    companion object {
        const val CHANNEL_ID = "goat_cam_streaming"
        const val NOTIFICATION_ID = 2401
    }
}
