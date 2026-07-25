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
 * Starlink 24/7 and uploaded ~59 GB in one billing period. Helm can't touch a
 * different app's sockets normally, so this uses root (`ss`) to enumerate the
 * server's `:5900` connections and destroy any that outlive the configured cap
 * ([HelmSettings.vncTimeoutMin], default 5 min). A forgotten viewer therefore
 * can't run more than a few minutes. Guard on/off + timeout persist in
 * [SettingsRepository]; the Settings pane exposes status + manual controls.
 *
 * The cap is a hard *session* age, not idle detection: a VNC server pushes the
 * screen continuously regardless of viewer input, so "idle" isn't observable
 * from outside — bounding every session is the reliable way to cap the data.
 */
class VncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(VncState())
    val state: StateFlow<VncState> = _state

    // peer(ip:port) → first time this manager saw the connection.
    private val firstSeen = HashMap<String, Long>()

    fun start() {
        scope.launch {
            while (isActive) {
                runCatching { poll() }
                delay(POLL_MS)
            }
        }
    }

    private fun poll() {
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

        // Enforce the cap. Per-socket kill isn't available (ss -K needs a filter
        // this build won't parse), so stop the whole server — which also leaves
        // VNC OFF until someone deliberately restarts it (a good safety default:
        // a forgotten viewer can't silently resume).
        val maxAgeSec = conns.maxOfOrNull { it.ageSec } ?: 0L
        if (cfg.vncGuard && conns.isNotEmpty() && maxAgeSec >= cfg.vncTimeoutMin * 60L) {
            RootShell.run("am force-stop $PKG")
            firstSeen.clear()
            _state.value = VncState(false, emptyList(),
                "auto-stopped VNC after ${cfg.vncTimeoutMin} min · ${clock(now)}")
        } else {
            _state.value = _state.value.copy(serverUp = serverUp, conns = conns)
        }
    }

    /** Stop the server now (cuts the viewer + the listener). */
    fun stopServer() = scope.launch {
        RootShell.run("am force-stop $PKG")
        firstSeen.clear()
        _state.value = VncState(false, emptyList(), "server stopped · ${clock()}")
    }

    /** Best-effort start of the server (resumes its saved capture token). */
    fun startServer() = scope.launch {
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
    }
}
