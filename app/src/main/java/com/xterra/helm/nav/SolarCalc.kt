package com.xterra.helm.nav

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Solar elevation for day/night theming. NOAA low-accuracy algorithm —
 * ±0.5° is plenty for "is it dark outside." Pure/testable.
 *
 * Chosen over sampling the rear camera for brightness: it needs no video
 * stream, works while the camera sleeps, and doesn't get fooled by shade,
 * headlights, or a muddy lens. The truck always has GPS + a clock.
 */
object SolarCalc {

    /** Sun elevation in degrees at [epochMillis] for [latDeg]/[lonDeg]. */
    fun elevationDeg(latDeg: Double, lonDeg: Double, epochMillis: Long): Double {
        val days = epochMillis / 86_400_000.0 + 2440587.5 - 2451545.0 // Julian days since J2000
        val g = Math.toRadians((357.529 + 0.98560028 * days) % 360.0) // mean anomaly
        val q = (280.459 + 0.98564736 * days) % 360.0                 // mean longitude
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g)) // ecliptic longitude
        val e = Math.toRadians(23.439 - 0.00000036 * days)            // obliquity
        val ra = Math.toDegrees(kotlin.math.atan2(cos(e) * sin(l), cos(l))) // right ascension
        val dec = asin(sin(e) * sin(l))                               // declination
        val gmst = (18.697374558 + 24.06570982441908 * days) % 24.0   // sidereal time (h)
        val lha = Math.toRadians(((gmst * 15.0 + lonDeg - ra) % 360.0 + 540.0) % 360.0 - 180.0)
        val lat = Math.toRadians(latDeg)
        return Math.toDegrees(asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(lha)))
    }

    /**
     * Day for theming = sun above -6° (civil twilight): headlights-on dusk
     * counts as night, bright overcast counts as day.
     */
    fun isDaylight(latDeg: Double, lonDeg: Double, epochMillis: Long): Boolean =
        elevationDeg(latDeg, lonDeg, epochMillis) > -6.0
}
