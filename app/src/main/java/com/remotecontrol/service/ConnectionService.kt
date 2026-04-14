package com.remotecontrol.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.remotecontrol.App
import com.remotecontrol.R
import com.remotecontrol.ui.MainActivity

/**
 * Foreground service that keeps the WebSocket connection alive
 * even when the app is in the background or screen is off.
 * Only stops when user explicitly clicks "disconnect" or force-kills the app.
 */
class ConnectionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // If killed by system, restart automatically
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("LinkView")
            .setContentText("待机中，等待连接...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    fun updateNotification(text: String) {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("LinkView")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LinkView::ConnectionWakeLock"
        ).apply {
            acquire()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.remotecontrol.STOP_CONNECTION"

        var instance: ConnectionService? = null
            private set
    }
}
