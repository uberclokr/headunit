package com.xterra.helm.nav.route

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Live turn-by-turn state derived by snapping the vehicle onto a [Route].
 */
data class Guidance(
    val onRoute: Boolean,
    val offRouteM: Double,          // perpendicular distance to the route
    val nextStep: RouteStep?,       // the upcoming maneuver (null once arrived)
    val distanceToNextM: Double,    // along-route distance to that maneuver
    val remainingM: Double,         // along-route distance to the destination
    val remainingMs: Long,
    val arrived: Boolean,
)

/**
 * Pure navigation math: project the vehicle onto the route polyline, then read
 * off progress, the next maneuver, distance-to-turn, and off-route state.
 * Local distances use an equirectangular meters projection about the query
 * point — accurate to well under a metre at driving scales, and cheap enough
 * to run every GPS fix. No engine dependency; fully unit-tested.
 */
object Navigator {

    /** Beyond this perpendicular distance from the route we're "off route". */
    const val OFF_ROUTE_M = 40.0

    /** Within this of the destination we call it arrived. */
    const val ARRIVE_M = 25.0

    private const val R = 6_371_000.0   // earth radius, metres

    fun haversine(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val la = Math.toRadians(aLat); val lb = Math.toRadians(bLat)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(la) * kotlin.math.cos(lb) *
            kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * R * kotlin.math.asin(min(1.0, sqrt(h)))
    }

    /** Local metres offsets (east, north) of [p] relative to [origin]. */
    private fun toMeters(origin: GeoPoint, lat: Double, lon: Double): Pair<Double, Double> {
        val mPerDegLat = R * Math.PI / 180.0
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(origin.lat))
        return (lon - origin.lon) * mPerDegLon to (lat - origin.lat) * mPerDegLat
    }

    /** Cumulative along-route metres at each polyline vertex. */
    fun cumulative(poly: List<GeoPoint>): DoubleArray {
        val c = DoubleArray(poly.size)
        for (i in 1 until poly.size)
            c[i] = c[i - 1] + haversine(poly[i - 1].lat, poly[i - 1].lon, poly[i].lat, poly[i].lon)
        return c
    }

    private data class Snap(val segIndex: Int, val alongM: Double, val offM: Double)

    /** Nearest point on the polyline to (lat,lon): its segment, along-distance, and offset. */
    private fun snap(poly: List<GeoPoint>, cum: DoubleArray, lat: Double, lon: Double): Snap {
        var best = Snap(0, 0.0, Double.MAX_VALUE)
        val origin = GeoPoint(lat, lon)
        for (i in 0 until poly.size - 1) {
            val (ax, ay) = toMeters(origin, poly[i].lat, poly[i].lon)
            val (bx, by) = toMeters(origin, poly[i + 1].lat, poly[i + 1].lon)
            val dx = bx - ax; val dy = by - ay
            val segLen2 = dx * dx + dy * dy
            // The query point is the origin → (0,0).
            val t = if (segLen2 == 0.0) 0.0 else (((0 - ax) * dx + (0 - ay) * dy) / segLen2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val off = hypot(px, py)
            if (off < best.offM) {
                val segLen = haversine(poly[i].lat, poly[i].lon, poly[i + 1].lat, poly[i + 1].lon)
                best = Snap(i, cum[i] + t * segLen, off)
            }
        }
        return best
    }

    fun guide(route: Route, lat: Double, lon: Double): Guidance {
        val poly = route.polyline
        if (poly.size < 2) return Guidance(false, Double.MAX_VALUE, null, 0.0, 0.0, 0, false)
        val cum = cumulative(poly)
        val total = cum.last()
        val s = snap(poly, cum, lat, lon)
        val remaining = max(0.0, total - s.alongM)
        val destOff = route.destination?.let { haversine(lat, lon, it.lat, it.lon) } ?: Double.MAX_VALUE
        val arrived = remaining <= ARRIVE_M || destOff <= ARRIVE_M

        // Next maneuver: the first step whose start vertex is ahead of us.
        val nextStep = route.steps.firstOrNull { step ->
            val idx = step.polyIndex.coerceIn(0, cum.size - 1)
            cum[idx] > s.alongM + 1.0 && step.maneuver != Maneuver.DEPART
        }
        val distToNext = nextStep?.let {
            max(0.0, cum[it.polyIndex.coerceIn(0, cum.size - 1)] - s.alongM)
        } ?: remaining
        val remMs = if (total > 0) (route.timeMs * (remaining / total)).toLong() else 0L

        return Guidance(
            onRoute = s.offM <= OFF_ROUTE_M,
            offRouteM = s.offM,
            nextStep = if (arrived) null else nextStep,
            distanceToNextM = distToNext,
            remainingM = remaining,
            remainingMs = remMs,
            arrived = arrived,
        )
    }
}
