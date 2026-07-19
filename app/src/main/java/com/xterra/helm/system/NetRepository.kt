package com.xterra.helm.system

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One place for "how's the truck's connectivity" — the STA link to lavalink,
 * our own AP + client count, raw internet RTT, and the Starlink dish's own
 * health via its local gRPC (see [StarlinkClient]).
 *
 * The dish (192.168.100.1) sits behind the Starlink router, and the WG VPN
 * would swallow that /32 like it does the camera's — so we pin the same
 * main-table bypass ViofoLocator uses before the first poll.
 */
data class NetStatus(
    val staSsid: String? = null,
    val staRssi: Int? = null,          // dBm
    val staFreqMhz: Int? = null,
    val apUp: Boolean = false,
    val apClients: Int = 0,
    val inetMs: Int? = null,           // ping 1.1.1.1 through whatever route is live
    val dish: StarlinkClient.DishStatus? = null,
)

class NetRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(NetStatus())
    val state: StateFlow<NetStatus> = _state

    private val sock = "/data/vendor/wifi/wpa/sockets"

    fun start() = scope.launch {
        // Dish bypass: via the wlan0 gateway, main table, beats the tun0 rule.
        RootShell.run(
            "ip route replace 192.168.100.1/32 via 192.168.1.1 dev wlan0 2>/dev/null; " +
            "ip rule del to 192.168.100.1/32 pref 5001 2>/dev/null; " +
            "ip rule add to 192.168.100.1/32 lookup main pref 5001")
        while (isActive) {
            val wpa = RootShell.run("wpa_cli -i wlan0 -p $sock status") ?: ""
            val sig = RootShell.run("wpa_cli -i wlan0 -p $sock signal_poll") ?: ""
            val stations = RootShell.run(
                "iw dev ${HotspotManager.AP_IFACE} station dump 2>/dev/null | grep -c ^Station")
            val ping = RootShell.run("ping -c1 -W2 1.1.1.1 2>/dev/null")
                ?.let { Regex("""time=([\d.]+)""").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
            _state.value = NetStatus(
                staSsid = Regex("(?m)^ssid=(.+)").find(wpa)?.groupValues?.get(1),
                staRssi = Regex("(?m)^RSSI=(-?\\d+)").find(sig)?.groupValues?.get(1)?.toIntOrNull(),
                staFreqMhz = Regex("(?m)^FREQUENCY=(\\d+)").find(sig)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(?m)^freq=(\\d+)").find(wpa)?.groupValues?.get(1)?.toIntOrNull(),
                apUp = HotspotManager.apUp(),
                apClients = stations?.trim()?.toIntOrNull() ?: 0,
                inetMs = ping?.toInt(),
                dish = StarlinkClient.getStatus(),
            )
            delay(10_000)
        }
    }
}
