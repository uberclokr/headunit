package com.xterra.helm.nav.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    // L-shaped route: ~721 m east, then ~1001 m north, at lat 44.
    private val p0 = GeoPoint(44.0000, -121.0000)
    private val p1 = GeoPoint(44.0000, -120.9910)
    private val p2 = GeoPoint(44.0090, -120.9910)
    private val route = Route(
        polyline = listOf(p0, p1, p2),
        steps = listOf(
            RouteStep(Maneuver.DEPART, "Start Rd", 721.0, 60_000, p0, 0),
            RouteStep(Maneuver.TURN_LEFT, "North Rd", 1001.0, 90_000, p1, 1),
            RouteStep(Maneuver.ARRIVE, "", 0.0, 0, p2, 2),
        ),
        distanceM = 1722.0, timeMs = 150_000,
    )

    @Test fun atStartTheNextManeuverIsTheTurn() {
        val g = Navigator.guide(route, 44.0000, -121.0000)
        assertTrue(g.onRoute)
        assertFalse(g.arrived)
        assertEquals(Maneuver.TURN_LEFT, g.nextStep!!.maneuver)
        assertEquals(721.0, g.distanceToNextM, 50.0)   // ≈ first segment length
        assertEquals(1722.0, g.remainingM, 50.0)
    }

    @Test fun progressShrinksDistanceToTheTurn() {
        val atStart = Navigator.guide(route, 44.0000, -121.0000).distanceToNextM
        val halfway = Navigator.guide(route, 44.0000, -120.9955).distanceToNextM
        assertTrue("distance to turn should shrink", halfway < atStart)
        assertEquals(360.0, halfway, 60.0)             // ≈ half the first segment
    }

    @Test fun pastTheTurnTheNextManeuverIsArrive() {
        val g = Navigator.guide(route, 44.0045, -120.9910)   // on the northbound leg
        assertEquals(Maneuver.ARRIVE, g.nextStep!!.maneuver)
        assertTrue(g.onRoute)
    }

    @Test fun offRouteIsDetected() {
        // ~100 m north of the eastbound leg.
        val g = Navigator.guide(route, 44.0009, -120.9955)
        assertFalse(g.onRoute)
        assertTrue(g.offRouteM > Navigator.OFF_ROUTE_M)
    }

    @Test fun arrivalAtDestination() {
        val g = Navigator.guide(route, 44.0090, -120.9910)
        assertTrue(g.arrived)
        assertNull(g.nextStep)
        assertEquals(0.0, g.remainingM, Navigator.ARRIVE_M)
    }
}
