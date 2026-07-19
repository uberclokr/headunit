package com.xterra.helm.cameras

import android.util.Log
import com.xterra.helm.system.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

data class ViofoLink(
    val ip: String? = null,
    val mac: String? = null,
    val reachable: Boolean = false,
    val lastOkAtMs: Long = 0,
)

/**
 * Finds the Viofo on the truck LAN by MAC, keeps a TCP heartbeat on its RTSP
 * port, and — because the WG VPN owns all app traffic (ip rule 13000 sends
 * uid 0-99999 to tun0) — pins a per-camera bypass so RTSP flows out [iface]:
 *
 *   ip neigh replace <ip> lladdr <mac> nud permanent dev wlan0   (sticky ARP)
 *   ip route replace <ip>/32 dev wlan0                           (static /32)
 *   ip rule add to <ip>/32 lookup main pref 5000                 (beats tun0)
 *
 * Only the camera's /32 is pinned: home base lives in the same 192.168.1.0/24
 * *behind* the tunnel, so a subnet-wide bypass would break the WG heartbeat.
 * On heartbeat loss the whole discover→pin cycle reruns, so a DHCP move or
 * camera reboot self-heals. Needs root (present on this image).
 */
class ViofoLocator(private val registry: CameraRegistry) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ViofoLink())
    val state: StateFlow<ViofoLink> = _state

    // ── configure for your hardware ─────────────────────────────
    // Interfaces to search, in order. wlan1 = the head unit's own SoftAP
    // (the camera joins "helmnet" there now); wlan0 = the truck LAN, kept as
    // a fallback for when the camera is on an external router instead.
    var ifaces = listOf("wlan1", "wlan0")
    var macPrefixes = listOf("9c:b8:b4") // this truck's Viofo (AMPAK module).
        // Empty list falls back to RTSP-port fingerprinting; discovery logs
        // every neighbor's MAC.
    var rtspPort = 554
    var heartbeatMs = 10_000L
    // ────────────────────────────────────────────────────────────

    private var pinnedIp: String? = null
    private var pinnedIface = "wlan1"     // interface the current pin routes via

    fun start() = scope.launch {
        while (isActive) {
            val ip = _state.value.ip
            if (ip != null && (heartbeat(ip) || run { delay(2_000); heartbeat(ip) })) {
                _state.value = _state.value.copy(
                    reachable = true, lastOkAtMs = System.currentTimeMillis())
                delay(heartbeatMs)
                continue
            }
            if (_state.value.reachable) Log.i(TAG, "Viofo heartbeat lost, rediscovering")
            _state.value = _state.value.copy(reachable = false)
            val found = discover()
            // Camera asleep (parking mode) is the common case — don't hammer
            // the AP with back-to-back sweeps; it shares air with the WG link.
            if (found == null) { delay(60_000); continue }
            val (foundIp, foundMac, foundIface) = found
            pin(foundIp, foundMac, foundIface)
            registry.retarget(foundIp)
            // One camera, one live source: default to rear so the stream is
            // always backup-ready. Verified on A329S fw V1.3: cmd 3028
            // par 0=front 1=interior 2=rear 5=three-channel composite.
            setLiveSource(foundIp, CH_REAR)
            _state.value = ViofoLink(foundIp, foundMac, true, System.currentTimeMillis())
            Log.i(TAG, "Viofo at $foundIp ($foundMac), pinned off-VPN via $foundIface")
        }
    }

    /**
     * The channel the CAM pane last chose. The reverse overlay forces REAR
     * while it's up, then restores this so the pane gets its view back.
     */
    val paneChannel = MutableStateFlow(CH_REAR)

    /**
     * Selects which channel the camera's (single) live stream carries, via
     * the WIFIAPP HTTP API. Idempotent and ~100 ms; safe to re-assert before
     * every overlay show. Plain in-process HTTP — the /32 pin routes it.
     */
    fun selectChannel(ch: Int) {
        val ip = _state.value.ip ?: return
        scope.launch { setLiveSource(ip, ch) }
    }

    /** Channel change from the CAM pane UI — remembered for overlay restore. */
    fun selectPaneChannel(ch: Int) {
        paneChannel.value = ch
        selectChannel(ch)
    }

    private fun setLiveSource(ip: String, ch: Int): Boolean = runCatching {
        val c = URL("http://$ip/?custom=1&cmd=3028&par=$ch")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2000
        val ok = c.inputStream.bufferedReader().readText().contains("<Status>0</Status>")
        c.disconnect()
        if (!ok) Log.w(TAG, "Viofo live-source select ch=$ch refused")
        ok
    }.getOrElse { false }

    /** TCP probe of the RTSP port — exercises the exact path LibVLC uses. */
    private fun heartbeat(ip: String): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, rtspPort), 1500); true }
    }.getOrElse { false }

    /**
     * Find the camera across candidate interfaces (hotspot first, then LAN).
     * Sweeps each as root — app-uid ICMP would ride the VPN — and returns the
     * first interface whose neighbor table shows the Viofo MAC.
     * Returns (ip, mac, iface).
     */
    private fun discover(): Triple<String, String, String>? {
        for (iface in ifaces) {
            val base = RootShell.run("ip -4 addr show dev $iface 2>/dev/null")
                ?.let { Regex("""inet (\d+\.\d+\.\d+)\.\d+/""").find(it)?.groupValues?.get(1) }
                ?: continue
            // 32-wide batches instead of 254-at-once: a full-burst sweep can
            // starve the AP (and the WG tunnel riding it) for seconds.
            RootShell.run(
                "i=1; while [ \$i -le 254 ]; do j=0; while [ \$j -lt 32 ] && [ \$i -le 254 ]; do " +
                "ping -I $iface -c1 -W1 $base.\$i >/dev/null 2>&1 & i=\$((i+1)); j=\$((j+1)); done; wait; done",
                timeoutMs = 60_000)
            val neigh = RootShell.run("ip neigh show dev $iface") ?: continue
            val hosts = Regex("""^(\d+\.\d+\.\d+\.\d+) lladdr ([0-9a-f:]{17})""", RegexOption.MULTILINE)
                .findAll(neigh)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
            if (hosts.isEmpty()) continue
            Log.i(TAG, "$iface neighbors: " + hosts.joinToString { "${it.first}=${it.second}" })

            val byMac = hosts.firstOrNull { (_, mac) ->
                macPrefixes.any { mac.startsWith(it.lowercase()) }
            }
            if (byMac != null) return Triple(byMac.first, byMac.second, iface)
            if (macPrefixes.isNotEmpty()) continue

            // No OUI configured: fingerprint by open RTSP port. Needs a temp
            // subnet bypass (probes would otherwise ride the VPN); removed in
            // finally — only the /32 pin is ever left behind.
            RootShell.run("ip rule add to $base.0/24 lookup main pref 4999")
            try {
                hosts.firstOrNull { (ip, _) -> heartbeat(ip) }
                    ?.let { return Triple(it.first, it.second, iface) }
            } finally {
                RootShell.run("ip rule del to $base.0/24 pref 4999")
            }
        }
        return null
    }

    private fun pin(ip: String, mac: String, iface: String) {
        // Tear down any prior pin (IP or interface may have changed).
        pinnedIp?.takeIf { it != ip || pinnedIface != iface }?.let { old ->
            RootShell.run("ip rule del to $old/32 pref 5000 2>/dev/null; " +
                          "ip route del $old/32 dev $pinnedIface 2>/dev/null; " +
                          "ip neigh del $old dev $pinnedIface 2>/dev/null")
        }
        RootShell.run(
            "ip neigh replace $ip lladdr $mac nud permanent dev $iface; " +
            "ip route replace $ip/32 dev $iface; " +
            "ip rule del to $ip/32 pref 5000 2>/dev/null; " +
            "ip rule add to $ip/32 lookup main pref 5000")
        pinnedIp = ip
        pinnedIface = iface
    }

    companion object {
        private const val TAG = "Helm"
        const val CH_FRONT = 0
        const val CH_INTERIOR = 1
        const val CH_REAR = 2
        const val CH_ALL = 5
    }
}
