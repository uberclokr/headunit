package com.xterra.helm.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xterra.helm.HelmApp
import com.xterra.helm.cameras.ReverseOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Always-on foreground service: owns CAN polling and media session tracking
 * so they survive Helm's UI being backgrounded, and spawns the reverse
 * overlay watcher. On an always-powered install this effectively
 * runs forever.
 */
class VehicleService : LifecycleService() {

    // ELM debug console, for bus captures / PID hunts from a shell:
    //   adb shell am broadcast -a com.xterra.helm.ELM_CMD --es cmd "ATSH7E1;2101;ATSH7E0"
    // Results land in `logcat -s Helm` as INJ lines.
    private val elmCmd = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i?.getStringExtra("cmd")?.let { HelmApp.instance.can.injectElm(it) }
        }
    }

    // BLE discovery, to find the Renogy MAC:
    //   adb shell am broadcast -a com.xterra.helm.BLE_SCAN
    // Devices land in `logcat -s Helm` as BLE lines.
    private val bleScan = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            HelmApp.instance.battery.discover()
        }
    }

    // Renogy register probe (identify device / find the right map):
    //   adb shell am broadcast -a com.xterra.helm.REN_DUMP
    //   adb shell am broadcast -a com.xterra.helm.REN_REG --ei reg 20546 --ei count 6
    // Results land in `logcat -s Helm` as REN lines.
    private val renProbe = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val b = HelmApp.instance.battery
            if (i?.action == "com.xterra.helm.REN_DUMP") b.probeStandard()
            else b.probe(i?.getIntExtra("reg", -1) ?: -1, i?.getIntExtra("count", 1) ?: 1)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, notification())
        registerReceiver(elmCmd, IntentFilter("com.xterra.helm.ELM_CMD"), RECEIVER_EXPORTED)
        registerReceiver(bleScan, IntentFilter("com.xterra.helm.BLE_SCAN"), RECEIVER_EXPORTED)
        registerReceiver(renProbe, IntentFilter("com.xterra.helm.REN_DUMP"), RECEIVER_EXPORTED)
        registerReceiver(renProbe, IntentFilter("com.xterra.helm.REN_REG"), RECEIVER_EXPORTED)
        HelmApp.instance.can.start()
        HelmApp.instance.media.start()
        HelmApp.instance.homeLink.start()
        HelmApp.instance.viofo.start()
        HelmApp.instance.gps.start()
        HelmApp.instance.net.start()
        // Companion-app back end: status API + camera relay, served over WG.
        ApiServer.start()
        RtspRelay.start()
        // Companion-app API + camera relay (VPN-facing, see ApiServer).
        ApiServer.start()
        RtspRelay.start()
        startForegroundService(Intent(this, ReverseOverlayService::class.java))
        // Apply persisted config (settings panel) to the subsystems that used
        // to hardcode it, then start the battery link and the AP watchdog.
        lifecycleScope.launch(Dispatchers.IO) {
            val s = HelmApp.instance.settings.awaitLoaded()
            HotspotManager.ssid = s.hotspotSsid
            HotspotManager.pass = s.hotspotPass
            HotspotManager.staBssid = s.staBssid
            HelmApp.instance.battery.mac = s.renogyMac
            HelmApp.instance.battery.start()
            // After awaitLoaded so the persisted ZERO is readable at start.
            HelmApp.instance.tilt.start()
            while (true) { HotspotManager.ensure(); delay(30_000) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun notification(): Notification {
        val ch = NotificationChannel("helm_veh", "Vehicle link", NotificationManager.IMPORTANCE_MIN)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
        return Notification.Builder(this, "helm_veh")
            .setContentTitle("Helm vehicle link active")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .build()
    }

    companion object { const val NOTIF_ID = 40 }
}
