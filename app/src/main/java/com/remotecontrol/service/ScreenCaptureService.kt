package com.remotecontrol.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.remotecontrol.App
import com.remotecontrol.R
import com.remotecontrol.ui.MainActivity

/**
 * Foreground service to keep MediaProjection alive.
 * Android requires a foreground service with type "mediaProjection"
 * to capture the screen while the app is in the background.
 */
class ScreenCaptureService : Service() {

    private val binder = LocalBinder()
    private var projectionData: Intent? = null

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                projectionData = intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                startForegroundNotification()
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun getProjectionData(): Intent? = projectionData

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_capturing))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        projectionData = null
    }

    companion object {
        const val ACTION_START = "com.remotecontrol.START_CAPTURE"
        const val ACTION_STOP = "com.remotecontrol.STOP_CAPTURE"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val NOTIFICATION_ID = 1001
    }
}
