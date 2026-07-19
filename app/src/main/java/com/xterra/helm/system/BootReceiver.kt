package com.xterra.helm.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Cold-boot path: bring the vehicle service up before the launcher UI. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, VehicleService::class.java))
        }
    }
}
