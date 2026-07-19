package com.xterra.helm.system

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HomeLink(
    val reachable: Boolean = false,
    val latencyMs: Int? = null,
    val lastOkAtMs: Long = 0,
)

/**
 * ICMP heartbeat to home base across the WireGuard tunnel. One mechanism,
 * two jobs: the periodic traffic keeps the WG handshake warm (Starlink CGNAT
 * forgets idle flows), and the echo reply is the truth signal for the HOME
 * dot in the status strip — home unreachable means the tunnel is dead no
 * matter what the wg interface claims.
 *
 * Shells out to /system/bin/ping: InetAddress.isReachable() only emits real
 * ICMP with root, and TCP-probe fallback would defeat the keepalive purpose.
 */
class HomeLinkRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(HomeLink())
    val state: StateFlow<HomeLink> = _state

    // ── configure for your hardware ─────────────────────────────
    var host = "192.168.1.36"     // home base; only routable while WG is up
    var intervalMs = 30_000L
    // ────────────────────────────────────────────────────────────

    fun start() = scope.launch {
        while (isActive) {
            // Starlink drops the odd packet; retry once before declaring down
            // rather than letting the dot flap on every lost echo.
            val rtt = ping() ?: run { delay(2_000); ping() }
            _state.value = if (rtt != null) {
                HomeLink(reachable = true, latencyMs = rtt,
                         lastOkAtMs = System.currentTimeMillis())
            } else {
                _state.value.copy(reachable = false, latencyMs = null)
            }
            delay(intervalMs)
        }
    }

    /** Single echo; RTT in ms on success, null on timeout/error. */
    private fun ping(): Int? = runCatching {
        val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "3", host)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor() == 0
        p.destroy()
        if (!ok) null
        // Exit 0 with unparseable output still counts as reachable.
        else Regex("""time=([\d.]+)\s*ms""").find(out)
            ?.groupValues?.get(1)?.toFloatOrNull()?.toInt() ?: 0
    }.getOrNull()
}
