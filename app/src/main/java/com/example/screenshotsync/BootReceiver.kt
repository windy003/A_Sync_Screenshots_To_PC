package com.example.screenshotsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后，如果用户之前开启过后台同步，则自动重启服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.serviceEnabled) {
            SyncService.start(context)
        }
    }
}
