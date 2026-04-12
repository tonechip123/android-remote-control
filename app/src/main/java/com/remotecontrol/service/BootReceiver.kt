package com.remotecontrol.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.remotecontrol.ui.MainActivity

/**
 * Auto-starts the app on device boot so the phone
 * stays available for remote control.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }
}
