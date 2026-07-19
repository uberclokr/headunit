package com.xterra.helm.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Newport, OR — the reference point from the task spec. */
class SolarCalcTest {

    private val lat = 44.6
    private val lon = -124.05

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun `july local noon is high sun`() {
        // solar noon at lon -124 ≈ 20:16 UTC; ~67° max altitude mid-July
        val e = SolarCalc.elevationDeg(lat, lon, at("2026-07-15T20:15:00Z"))
        assertTrue("expected strongly positive, got $e", e > 40.0)
        assertTrue("physically impossible: $e", e < 90.0)
    }

    @Test fun `july local midnight is deep night`() {
        // local midnight PDT = 07:00 UTC; min elevation ≈ dec - (90 - lat) ≈ -24°
        val e = SolarCalc.elevationDeg(lat, lon, at("2026-07-16T07:00:00Z"))
        assertTrue("expected strongly negative, got $e", e < -15.0)
    }

    @Test fun `sunrise crossing is bracketed`() {
        // Newport sunrise ~05:40 PDT (12:40 UTC) mid-July
        assertTrue(SolarCalc.elevationDeg(lat, lon, at("2026-07-15T12:00:00Z")) < 0.0)
        assertTrue(SolarCalc.elevationDeg(lat, lon, at("2026-07-15T14:00:00Z")) > 0.0)
    }

    @Test fun `winter solstice noon matches geometry`() {
        // max altitude = 90 - lat + dec ≈ 90 - 44.6 - 23.4 ≈ 22°
        val e = SolarCalc.elevationDeg(lat, lon, at("2026-12-21T20:15:00Z"))
        assertEquals(22.0, e, 4.0)
    }

    @Test fun `isDaylight uses civil twilight threshold`() {
        assertTrue(SolarCalc.isDaylight(lat, lon, at("2026-07-15T20:15:00Z")))
        assertFalse(SolarCalc.isDaylight(lat, lon, at("2026-07-16T07:00:00Z")))
        // ~20 min before sunrise: sun below horizon but above -6° — still "day" for theming
        assertTrue(SolarCalc.elevationDeg(lat, lon, at("2026-07-15T12:20:00Z")) < 0.0)
        assertTrue(SolarCalc.isDaylight(lat, lon, at("2026-07-15T12:20:00Z")))
    }

    @Test fun `equator equinox noon is near zenith`() {
        // 2026-03-20 equinox; solar noon at lon 0 ≈ 12:07 UTC
        val e = SolarCalc.elevationDeg(0.0, 0.0, at("2026-03-20T12:07:00Z"))
        assertTrue("expected near-zenith, got $e", e > 85.0)
    }
}
