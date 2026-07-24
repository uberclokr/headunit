package com.xterra.helm.nav.route

/** A WGS84 point. */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * Maneuver kinds we surface in guidance, mapped from the routing engine's
 * turn "signs". Kept small and display-oriented — the engine's finer codes
 * collapse into these.
 */
enum class Maneuver {
    DEPART, CONTINUE,
    SLIGHT_LEFT, TURN_LEFT, SHARP_LEFT,
    SLIGHT_RIGHT, TURN_RIGHT, SHARP_RIGHT,
    KEEP_LEFT, KEEP_RIGHT, UTURN, ROUNDABOUT, ARRIVE;

    val isRight get() = this == SLIGHT_RIGHT || this == TURN_RIGHT || this == SHARP_RIGHT || this == KEEP_RIGHT
    val isLeft get() = this == SLIGHT_LEFT || this == TURN_LEFT || this == SHARP_LEFT || this == KEEP_LEFT
}

/**
 * One routing step: perform [maneuver] (onto [street]) at [at], then travel
 * [distanceM] to the next step. [polyIndex] is where this step begins in the
 * route's polyline, so guidance can locate maneuvers along the line.
 */
data class RouteStep(
    val maneuver: Maneuver,
    val street: String,
    val distanceM: Double,
    val timeMs: Long,
    val at: GeoPoint,
    val polyIndex: Int,
)

/** A computed route: the full geometry, ordered steps, and totals. */
data class Route(
    val polyline: List<GeoPoint>,
    val steps: List<RouteStep>,
    val distanceM: Double,
    val timeMs: Long,
) {
    val destination: GeoPoint? get() = polyline.lastOrNull()
}
