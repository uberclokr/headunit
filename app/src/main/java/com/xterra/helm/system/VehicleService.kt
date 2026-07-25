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

    // Starlink dish gRPC probe: dump a raw response for a given Request field
    // to discover what the live dish exposes (e.g. get_history for usage):
    //   adb shell am broadcast -a com.xterra.helm.SL_DUMP --ei field 1005
    // Result lands in `logcat -s Helm` as an "SL dump" line.
    private val slDump = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            HelmApp.instance.net.dumpDish(i?.getIntExtra("field", 1005) ?: 1005)
        }
    }

    // Offline routing self-test (validates the embedded engine on-device):
    //   adb shell am broadcast -a com.xterra.helm.NAV_TEST
    //   ...--ef flat 44.058 --ef flon -121.315 --ef tlat 44.272 --ef tlon -121.174
    // Defaults route Bend -> Redmond, OR. Result in `logcat -s Helm`.
    private val navTest = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            HelmApp.instance.nav.debugRoute(
                i?.getFloatExtra("flat", 44.0582f)?.toDouble() ?: 44.0582,
                i?.getFloatExtra("flon", -121.3153f)?.toDouble() ?: -121.3153,
                i?.getFloatExtra("tlat", 44.2726f)?.toDouble() ?: 44.2726,
                i?.getFloatExtra("tlon", -121.1739f)?.toDouble() ?: -121.1739,
            )
        }
    }

    // Start a real turn-by-turn trip from the live GPS fix to a destination —
    // drives the on-screen route line, banner, and voice (unlike NAV_TEST,
    // which only logs). Defaults to Bend, OR:
    //   adb shell am broadcast -a com.xterra.helm.NAV_GO --ef tlat 44.06 --ef tlon -121.31
    private val navGo = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            HelmApp.instance.nav.navigateTo(
                i?.getFloatExtra("tlat", 44.0582f)?.toDouble() ?: 44.0582,
                i?.getFloatExtra("tlon", -121.3153f)?.toDouble() ?: -121.3153,
                i?.getStringExtra("name") ?: "Waypoint",
            )
        }
    }

    // Offline map-tile region download for a bbox (default = Western Oregon,
    // z6-13). Validates the raised tile-count limit and pre-caches a region
    // without needing to frame it on the map:
    //   adb shell am broadcast -a com.xterra.helm.NAV_CACHE --ei z 13
    //   ...optional --ef n 46.3 --ef s 42.0 --ef e -121.8 --ef w -124.6
    private val navCache = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val ctx = applicationContext
            val n = i?.getFloatExtra("n", 46.3f)?.toDouble() ?: 46.3
            val s = i?.getFloatExtra("s", 42.0f)?.toDouble() ?: 42.0
            val e = i?.getFloatExtra("e", -121.8f)?.toDouble() ?: -121.8
            val w = i?.getFloatExtra("w", -124.6f)?.toDouble() ?: -124.6
            val maxZ = (i?.getIntExtra("z", 13) ?: 13).toDouble()
            org.maplibre.android.MapLibre.getInstance(ctx)
            val mgr = org.maplibre.android.offline.OfflineManager.getInstance(ctx)
            mgr.setOfflineMapboxTileCountLimit(300_000L)
            val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
                .include(org.maplibre.android.geometry.LatLng(n, e))
                .include(org.maplibre.android.geometry.LatLng(s, w)).build()
            val styleUrl = com.xterra.helm.nav.StyleServer.serve(
                com.xterra.helm.nav.MapStyles.offlineStyle(com.xterra.helm.nav.MapStyles.Base.TOPO))
            val def = org.maplibre.android.offline.OfflineTilePyramidRegionDefinition(
                styleUrl, bounds, 6.0, maxZ, ctx.resources.displayMetrics.density)
            android.util.Log.i("Helm", "NAV_CACHE start z6-${maxZ.toInt()} bbox $s,$w..$n,$e")
            mgr.createOfflineRegion(
                def, """{"name":"W.Oregon z6-${maxZ.toInt()}"}""".toByteArray(),
                object : org.maplibre.android.offline.OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(region: org.maplibre.android.offline.OfflineRegion) {
                        region.setObserver(object : org.maplibre.android.offline.OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(st: org.maplibre.android.offline.OfflineRegionStatus) {
                                android.util.Log.i("Helm", "NAV_CACHE ${st.completedResourceCount} tiles · " +
                                    "${st.completedResourceSize / 1_048_576} MB" + if (st.isComplete) " ✓DONE" else "")
                            }
                            override fun onError(err: org.maplibre.android.offline.OfflineRegionError) {
                                android.util.Log.w("Helm", "NAV_CACHE err ${err.reason}: ${err.message}")
                            }
                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                android.util.Log.w("Helm", "NAV_CACHE LIMIT HIT $limit")
                            }
                        })
                        region.setDownloadState(org.maplibre.android.offline.OfflineRegion.STATE_ACTIVE)
                    }
                    override fun onError(error: String) {
                        android.util.Log.w("Helm", "NAV_CACHE create err $error")
                    }
                })
        }
    }

    // Geocoder self-test (validates online Photon search + parsing on-device):
    //   adb shell am broadcast -a com.xterra.helm.NAV_SEARCH --es q "coffee"
    private val navSearch = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val q = i?.getStringExtra("q") ?: "coffee"
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val gps = HelmApp.instance.gps.state.value
                val hits = com.xterra.helm.nav.route.Geocoder.search(
                    q, gps.lat.takeIf { gps.hasFix }, gps.lon.takeIf { gps.hasFix })
                android.util.Log.i("Helm", "NAV_SEARCH \"$q\": ${hits.size} hits")
                hits.take(6).forEach {
                    android.util.Log.i("Helm", "  ${it.name} · ${it.label} · %.4f,%.4f".format(it.lat, it.lon))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, notification())
        registerReceiver(elmCmd, IntentFilter("com.xterra.helm.ELM_CMD"), RECEIVER_EXPORTED)
        registerReceiver(bleScan, IntentFilter("com.xterra.helm.BLE_SCAN"), RECEIVER_EXPORTED)
        registerReceiver(renProbe, IntentFilter("com.xterra.helm.REN_DUMP"), RECEIVER_EXPORTED)
        registerReceiver(renProbe, IntentFilter("com.xterra.helm.REN_REG"), RECEIVER_EXPORTED)
        registerReceiver(slDump, IntentFilter("com.xterra.helm.SL_DUMP"), RECEIVER_EXPORTED)
        registerReceiver(navTest, IntentFilter("com.xterra.helm.NAV_TEST"), RECEIVER_EXPORTED)
        registerReceiver(navGo, IntentFilter("com.xterra.helm.NAV_GO"), RECEIVER_EXPORTED)
        registerReceiver(navSearch, IntentFilter("com.xterra.helm.NAV_SEARCH"), RECEIVER_EXPORTED)
        registerReceiver(navCache, IntentFilter("com.xterra.helm.NAV_CACHE"), RECEIVER_EXPORTED)
        HelmApp.instance.can.start()
        HelmApp.instance.media.start()
        HelmApp.instance.homeLink.start()
        HelmApp.instance.viofo.start()
        HelmApp.instance.gps.start()
        HelmApp.instance.net.start()
        HelmApp.instance.lora.start()
        HelmApp.instance.nav.start()
        HelmApp.instance.vnc.start()   // caps stale VNC sessions (Starlink guard)
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
