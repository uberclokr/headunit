package com.xterra.helm.nav.route

import java.io.File

/**
 * Computes offline routes from pre-built map data. The engine is embedded so
 * navigation works while mobile with no internet — the map data is provisioned
 * on-device like the offline map tiles.
 */
interface RouteEngine {
    /** True once map data is loaded and routing is possible. */
    val ready: Boolean

    /** Load map data from [dataDir]; true on success. Call off the main thread. */
    fun load(dataDir: File): Boolean

    /** Compute a route, or null if none is found / not ready. Off the main thread. */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route?

    fun close()
}
