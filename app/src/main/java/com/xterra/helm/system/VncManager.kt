package com.xterra.helm.system

import com.xterra.helm.HelmApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A live VNC client connection to the on-device server. */
data class VncConn(val peer: String, val ageSec: Long)

data class VncState(
    val serverUp: Boolean = false,       // droidVNC listening on :5900
    val conns: List<VncConn> = emptyList(),
    val lastAction: String? = null,
)

/**
 * Watchdog + manager for **droidVNC-NG** — a separate remote-screen server app
 * (uid 10108) that, once left connected, streamed the animated dashboard over
 * Starlink 24/7 and uploaded ~59 GB in one billing period.
 *
 * The guard caps how long any *session* may run, but must NOT take the server
 * down: droidVNC can't be restarted headlessly on this Android-14 image (its
 * intent-control API needs an access key that was never set, and MediaProjection
 * consent is single-use), so a force-stop would leave remote access dead until
 * someone physically taps the app. Instead the cap is enforced at the network
 * layer: an OUTPUT `REJECT --reject-with tcp-reset` on `:5900` resets the
 * actively-streaming session within an RTT while the LISTEN socket is untouched
 * (netfilter filters per packet, not per socket) — so the server stays available
 * for new connections at all times. Verified on-device: a streaming connection
 * drops, the listener survives.
 *
 * Trade-off (intentional, per the "always available" requirement): the reset
 * can't target one viewer of several (this build's `ss` won't parse a filter for
 * `ss -K`), so all current sessions drop together, and a viewer that auto-
 * reconnects simply gets another full window before the next cut. To take VNC
 * fully offline (e.g. a viewer hammering reconnect), use STOP SERVER, which
 * force-stops the app.
 *
 * The cap is a hard *session* age, not idle detection: a VNC server pushes the
 * screen continuously regardless of viewer input, so "idle" isn't observable
 * from outside — bounding every session is the reliable way to cap the data.
 * Guard on/off + timeout persist in [SettingsRepository]; the Settings pane
 * exposes status + manual controls.
 */
class VncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(VncState())
    val state: StateFlow<VncState> = _state

    // peer(ip:port) → first time this manager saw the connection.
    private val firstSeen = HashMap<String, Long>()

    fun start() {
        // Drop any reset rule left behind by a previous run/crash so the server
        // isn't accidentally unreachable, then poll forever.
        scope.launch {
            RootShell.run("iptables -D OUTPUT -p tcp --sport $PORT -j $REJECT")
            while (isActive) {
                runCatching { poll() }
                delay(POLL_MS)
            }
        }
    }

    private suspend fun poll() {
        val cfg = HelmApp.instance.settings.state.value
        val now = System.currentTimeMillis()
        // This ss build ignores filter expressions (they dump every socket), so
        // list with flags only and match the :5900 rows in code.
        val serverUp = RootShell.run("ss -tlnH").orEmpty()
            .lineSequence().any { hasPort(it, PORT) }

        val seen = HashSet<String>()
        val conns = ArrayList<VncConn>()
        RootShell.run("ss -tnH state established").orEmpty().lineSequence().forEach { line ->
            val peer = vncPeer(line) ?: return@forEach
            seen += peer
            val first = firstSeen.getOrPut(peer) { now }
            conns += VncConn(peer.substringBeforeLast(':'), (now - first) / 1000)
        }
        firstSeen.keys.retainAll(seen)   // forget connections that have closed

        // Enforce the cap by resetting the session (not stopping the server).
        val maxAgeSec = conns.maxOfOrNull { it.ageSec } ?: 0L
        if (cfg.vncGuard && conns.isNotEmpty() && maxAgeSec >= cfg.vncTimeoutMin * 60L) {
            cutSessions()
            _state.value = VncState(
                serverUp = serverUp, conns = emptyList(),
                lastAction = "auto-disconnected VNC after ${cfg.vncTimeoutMin} min · ${clock(now)}")
        } else {
            _state.value = _state.value.copy(serverUp = serverUp, conns = conns)
        }
    }

    /**
     * Reset every current VNC session without dropping the listener. The reset
     * only fires while the socket transmits, but a VNC server streams the
     * framebuffer continuously, so this tears the session down promptly; hold
     * the rule a few seconds to be sure, then remove it so new viewers connect.
     */
    private suspend fun cutSessions() {
        RootShell.run("iptables -D OUTPUT -p tcp --sport $PORT -j $REJECT")  // clear any stale copy
        RootShell.run("iptables -I OUTPUT -p tcp --sport $PORT -j $REJECT")
        delay(RST_HOLD_MS)
        RootShell.run("iptables -D OUTPUT -p tcp --sport $PORT -j $REJECT")
        firstSeen.clear()
    }

    /** Disconnect clients now, leaving the server up and accepting connections. */
    fun disconnectNow() = scope.launch {
        cutSessions()
        _state.value = _state.value.copy(conns = emptyList(),
            lastAction = "clients disconnected · ${clock()}")
    }

    /** Take VNC fully offline (force-stop). Needs a manual restart from the app. */
    fun stopServer() = scope.launch {
        RootShell.run("am force-stop $PKG")
        firstSeen.clear()
        _state.value = VncState(false, emptyList(), "server stopped · ${clock()}")
    }

    /**
     * Best-effort headless start. Grants the MediaProjection appop so capture
     * can start without the consent dialog, then launches the service. On this
     * ROM the intent API also wants an access key, so this often won't bring the
     * listener up — starting from the droidVNC app is the reliable path.
     */
    fun startServer() = scope.launch {
        RootShell.run("appops set $PKG PROJECT_MEDIA allow")
        RootShell.run("am start-foreground-service $PKG/.MainService")
        _state.value = _state.value.copy(lastAction = "starting server · ${clock()}")
        delay(1800); runCatching { poll() }
    }

    /** Peer "ip:port" from an established-TCP `ss` row whose local port is 5900. */
    private fun vncPeer(line: String): String? {
        val addrs = line.trim().split(Regex("\\s+"))
            .filter { it.contains(':') && it.substringAfterLast(':').toIntOrNull() != null }
        if (addrs.none { it.substringAfterLast(':').toIntOrNull() == PORT }) return null  // not VNC
        val peer = addrs.firstOrNull { it.substringAfterLast(':').toIntOrNull() != PORT } ?: return null
        val port = peer.substringAfterLast(':')
        var ip = peer.substringBeforeLast(':').trim('[', ']')
        if (ip.startsWith("::ffff:")) ip = ip.removePrefix("::ffff:")
        return "$ip:$port"
    }

    private fun hasPort(line: String, port: Int) = line.trim().split(Regex("\\s+"))
        .any { it.contains(':') && it.substringAfterLast(':').toIntOrNull() == port }

    private fun clock(ms: Long = System.currentTimeMillis()) =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))

    companion object {
        private const val PKG = "net.christianbeier.droidvnc_ng"
        private const val PORT = 5900
        private const val POLL_MS = 20_000L
        private const val RST_HOLD_MS = 3_000L
        // Kept short so the whole rule fits one root command; see cutSessions().
        private const val REJECT = "REJECT --reject-with tcp-reset"
    }
}
