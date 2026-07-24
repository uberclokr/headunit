package com.xterra.helm.nav.route

import android.content.Context
import android.util.Log
import com.xterra.helm.HelmApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Everything the nav UI binds to for turn-by-turn. */
data class NavState(
    val engineReady: Boolean = false,   // offline graph loaded
    val route: Route? = null,           // active route (null = not navigating)
    val guidance: Guidance? = null,     // live progress along it
    val destinationName: String? = null,
    val computing: Boolean = false,     // route being (re)calculated
    val error: String? = null,
)

/**
 * Owns the embedded [RouteEngine] and runs turn-by-turn: computes a route from
 * the live GPS fix to a destination, then re-snaps guidance on every fix,
 * auto-rerouting when off-route and clearing on arrival. All offline — the
 * graph is provisioned on-device like the map tiles (see [graphDir]).
 */
class NavRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine: RouteEngine = GraphHopperEngine()

    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state

    @Volatile private var route: Route? = null
    @Volatile private var lastRerouteMs = 0L

    /** The offline routing graph folder on device. */
    fun graphDir(): File = File(context.filesDir, "graph")

    fun start() {
        scope.launch {
            val ok = engine.load(graphDir())
            _state.value = _state.value.copy(engineReady = ok)
        }
        // Re-derive guidance on every GPS fix while a route is active.
        scope.launch {
            HelmApp.instance.gps.state.collect { gps ->
                val r = route ?: return@collect
                if (!gps.hasFix) return@collect
                val g = Navigator.guide(r, gps.lat, gps.lon)
                _state.value = _state.value.copy(guidance = g)
                if (g.arrived) { clear(); return@collect }
                if (!g.onRoute && System.currentTimeMillis() - lastRerouteMs > REROUTE_MIN_MS) {
                    lastRerouteMs = System.currentTimeMillis()
                    r.destination?.let { computeTo(it.lat, it.lon, _state.value.destinationName) }
                }
            }
        }
    }

    /** Begin navigating from the current fix to (destLat, destLon). */
    fun navigateTo(destLat: Double, destLon: Double, name: String? = null) {
        lastRerouteMs = System.currentTimeMillis()
        computeTo(destLat, destLon, name)
    }

    private fun computeTo(destLat: Double, destLon: Double, name: String?) = scope.launch {
        if (!engine.ready) {
            _state.value = _state.value.copy(error = "offline map not installed"); return@launch
        }
        val gps = HelmApp.instance.gps.state.value
        if (!gps.hasFix) {
            _state.value = _state.value.copy(error = "no GPS fix"); return@launch
        }
        _state.value = _state.value.copy(computing = true, error = null, destinationName = name)
        val r = engine.route(gps.lat, gps.lon, destLat, destLon)
        route = r
        _state.value = _state.value.copy(
            route = r, computing = false,
            error = if (r == null) "no route found" else null,
            guidance = r?.let { Navigator.guide(it, gps.lat, gps.lon) },
        )
        if (r != null) Log.i(TAG, "route: %.1f km, %d steps".format(r.distanceM / 1000, r.steps.size))
    }

    fun clear() {
        route = null
        _state.value = _state.value.copy(route = null, guidance = null, destinationName = null, error = null)
    }

    companion object {
        private const val TAG = "Helm"
        private const val REROUTE_MIN_MS = 8_000L   // don't reroute more than this often
    }
}
