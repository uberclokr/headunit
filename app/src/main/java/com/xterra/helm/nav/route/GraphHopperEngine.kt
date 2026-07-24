package com.xterra.helm.nav.route

import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import java.io.File

/**
 * GraphHopper embedded offline routing. Loads a pre-built graph folder (built
 * on a PC with `graphhopper import` from a regional .osm.pbf and copied to the
 * device — see docs) and answers route requests with geometry + turn
 * instructions. Contraction Hierarchies are prepared at import time, so query
 * time uses the precomputed shortcuts and never invokes GraphHopper's runtime
 * expression compiler (Janino) — which would not run under ART. The graph MUST
 * be built with the same GraphHopper version pinned in build.gradle.
 */
class GraphHopperEngine : RouteEngine {
    @Volatile private var hopper: GraphHopper? = null
    override val ready: Boolean get() = hopper != null

    override fun load(dataDir: File): Boolean = runCatching {
        // A built graph writes these; bail if absent (we never import in-app).
        if (!File(dataDir, "edges").exists() && !File(dataDir, "nodes").exists()) {
            Log.i(TAG, "no GraphHopper graph in $dataDir")
            return false
        }
        // Memory-map the graph instead of loading it into the app's Java heap.
        // A regional graph is hundreds of MB; RAM_STORE (the default) OOMs the
        // process on load. MMAP reads the same on-disk files as demand-paged
        // mmap, so heap stays flat regardless of graph size. Same file format —
        // no rebuild needed. (GraphHopper DAType values: RAM/RAM_STORE/MMAP/MMAP_RO.)
        val cfg = GraphHopperConfig()
        cfg.putObject("graph.location", dataDir.absolutePath)
        cfg.putObject("graph.dataaccess.default_type", "MMAP")
        // Validated by init() even on pure load (only consumed during import);
        // must be present or importOrLoad() throws. Matches the build config.
        cfg.putObject("import.osm.ignored_highways", "footway,cycleway,path,pedestrian,steps")
        cfg.setProfiles(listOf(Profile(PROFILE).setVehicle("car").setWeighting("fastest")))
        cfg.setCHProfiles(listOf(CHProfile(PROFILE)))
        val gh = GraphHopper()
        gh.init(cfg)
        gh.importOrLoad()               // no OSM file set → loads the existing graph
        hopper = gh
        Log.i(TAG, "GraphHopper graph loaded (mmap) from $dataDir")
        true
    }.getOrElse { Log.w(TAG, "GraphHopper load failed: ${it.message}"); false }

    override fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route? {
        val gh = hopper ?: return null
        return runCatching {
            val rsp = gh.route(GHRequest(fromLat, fromLon, toLat, toLon).setProfile(PROFILE))
            if (rsp.hasErrors()) { Log.w(TAG, "route: ${rsp.errors}"); return null }
            val path = rsp.best
            val poly = ArrayList<GeoPoint>()
            val steps = ArrayList<RouteStep>()
            path.instructions.forEachIndexed { i, inst ->
                val start = poly.size
                val pts = inst.points
                for (j in 0 until pts.size()) {
                    val gp = GeoPoint(pts.getLat(j), pts.getLon(j))
                    if (poly.isEmpty() || poly.last() != gp) poly.add(gp)
                }
                steps.add(RouteStep(
                    maneuver = mapSign(inst.sign, i == 0),
                    street = inst.name ?: "",
                    distanceM = inst.distance,
                    timeMs = inst.time,
                    at = if (pts.size() > 0) GeoPoint(pts.getLat(0), pts.getLon(0))
                    else poly.lastOrNull() ?: GeoPoint(toLat, toLon),
                    polyIndex = start,
                ))
            }
            Route(poly, steps, path.distance, path.time)
        }.getOrElse { Log.w(TAG, "route error: ${it.message}"); null }
    }

    override fun close() { runCatching { hopper?.close() }; hopper = null }

    /** GraphHopper turn "sign" → our display maneuver. */
    private fun mapSign(sign: Int, first: Boolean): Maneuver = when {
        first -> Maneuver.DEPART
        else -> when (sign) {
            -3 -> Maneuver.SHARP_LEFT
            -2 -> Maneuver.TURN_LEFT
            -1 -> Maneuver.SLIGHT_LEFT
            0 -> Maneuver.CONTINUE
            1 -> Maneuver.SLIGHT_RIGHT
            2 -> Maneuver.TURN_RIGHT
            3 -> Maneuver.SHARP_RIGHT
            4, 5 -> Maneuver.ARRIVE
            6 -> Maneuver.ROUNDABOUT
            -7 -> Maneuver.KEEP_LEFT
            7 -> Maneuver.KEEP_RIGHT
            -8, 8, -98 -> Maneuver.UTURN
            else -> Maneuver.CONTINUE
        }
    }

    companion object {
        private const val TAG = "Helm"
        private const val PROFILE = "car"
    }
}
