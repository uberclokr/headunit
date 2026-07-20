package com.xterra.helm.system

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * One place for "how's the truck's connectivity" — the STA link to lavalink,
 * our own AP + client count, raw internet RTT, and the Starlink dish's own
 * health via its local gRPC (see [StarlinkClient]).
 *
 * The dish (192.168.100.1) sits behind the Starlink router, and the WG VPN
 * would swallow that /32 like it does the camera's — so we pin the same
 * main-table bypass ViofoLocator uses before the first poll.
 *
 * Data usage: the dish exposes no usage counter, so [UsageAccumulator]
 * integrates its get_history throughput ring into a running total, persisted
 * across reboots by [UsageStore] and zeroed each billing cycle. All local —
 * querying the dish costs no subscription data.
 */
data class NetStatus(
    val staSsid: String? = null,
    val staRssi: Int? = null,          // dBm
    val staFreqMhz: Int? = null,
    val apUp: Boolean = false,
    val apClients: Int = 0,
    val inetMs: Int? = null,           // ping 1.1.1.1 through whatever route is live
    val dish: StarlinkClient.DishStatus? = null,
    val usageBytes: Long = 0,          // this billing cycle, integrated from the dish
    val usageCapGb: Float? = null,     // plan cap if configured (else no denominator)
    val usageCycleStartEpochDay: Long = 0,
)

class NetRepository(context: Context, private val settings: SettingsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(NetStatus())
    val state: StateFlow<NetStatus> = _state

    private val sock = "/data/vendor/wifi/wpa/sockets"

    private val usageStore = UsageStore(context)
    private val usage = UsageAccumulator()
    private var cycleStartEpochDay = 0L

    fun start() = scope.launch {
        // Dish bypass: via the wlan0 gateway, main table, beats the tun0 rule.
        RootShell.run(
            "ip route replace 192.168.100.1/32 via 192.168.1.1 dev wlan0 2>/dev/null; " +
            "ip rule del to 192.168.100.1/32 pref 5001 2>/dev/null; " +
            "ip rule add to 192.168.100.1/32 lookup main pref 5001")

        // Restore persisted usage before the first poll so we continue, not reset.
        val persisted = usageStore.load()
        usage.restore(persisted.totalBytes, persisted.lastCurrent)
        cycleStartEpochDay = persisted.cycleStartEpochDay

        var cycle = 0
        while (isActive) {
            val wpa = RootShell.run("wpa_cli -i wlan0 -p $sock status") ?: ""
            val sig = RootShell.run("wpa_cli -i wlan0 -p $sock signal_poll") ?: ""
            val stations = RootShell.run(
                "iw dev ${HotspotManager.AP_IFACE} station dump 2>/dev/null | grep -c ^Station")
            val ping = RootShell.run("ping -c1 -W2 1.1.1.1 2>/dev/null")
                ?.let { Regex("""time=([\d.]+)""").find(it)?.groupValues?.get(1)?.toFloatOrNull() }

            // Plan cap + reset day come from settings (editable in the pane).
            val cfg = settings.state.value
            val capGb = cfg.starlinkCapGb.takeIf { it > 0f }

            // Roll the billing cycle over if we've crossed the anchor day (or
            // the anchor was edited to define a different cycle boundary).
            val cs = cycleStartFor(LocalDate.now(), cfg.starlinkAnchorDay).toEpochDay()
            if (cs != cycleStartEpochDay) {
                usage.resetCycle()
                cycleStartEpochDay = cs
                usageStore.save(UsagePersist(usage.totalBytes, usage.snapshot().second, cs))
            }
            // Integrate the dish's throughput ring into the running total.
            StarlinkClient.getHistory()?.let { h ->
                usage.add(h.current, h.downlinkBps, h.uplinkBps)
            }
            // Persist ~every 60 s — reboot-survival granularity, not per poll.
            if (cycle % 6 == 0) {
                val (tot, last) = usage.snapshot()
                usageStore.save(UsagePersist(tot, last, cycleStartEpochDay))
            }

            _state.value = NetStatus(
                staSsid = Regex("(?m)^ssid=(.+)").find(wpa)?.groupValues?.get(1),
                staRssi = Regex("(?m)^RSSI=(-?\\d+)").find(sig)?.groupValues?.get(1)?.toIntOrNull(),
                staFreqMhz = Regex("(?m)^FREQUENCY=(\\d+)").find(sig)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(?m)^freq=(\\d+)").find(wpa)?.groupValues?.get(1)?.toIntOrNull(),
                apUp = HotspotManager.apUp(),
                apClients = stations?.trim()?.toIntOrNull() ?: 0,
                inetMs = ping?.toInt(),
                dish = StarlinkClient.getStatus(),
                usageBytes = usage.totalBytes,
                usageCapGb = capGb,
                usageCycleStartEpochDay = cycleStartEpochDay,
            )
            cycle++
            delay(10_000)
        }
    }

    /**
     * One-shot dish gRPC probe (SL_DUMP broadcast). Runs on our scope so the
     * dish /32 bypass pinned in start() is already in place. Result to logcat.
     */
    fun dumpDish(field: Int) = scope.launch {
        Log.i("Helm", "SL dump req=$field ->\n" + StarlinkClient.dump(field))
    }

    companion object {
        /** Start of the billing cycle containing [today] for [anchorDay] (1..28). */
        fun cycleStartFor(today: LocalDate, anchorDay: Int): LocalDate {
            val a = anchorDay.coerceIn(1, 28)
            return if (today.dayOfMonth >= a) today.withDayOfMonth(a)
            else today.minusMonths(1).withDayOfMonth(a)
        }
    }
}
