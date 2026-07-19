package com.xterra.helm.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaParserTest {

    /** Append a computed *HH checksum to a bare body (no leading $). */
    private fun nmea(body: String): String {
        var x = 0
        for (c in body) x = x xor c.code
        return "\$$body*%02X".format(x)
    }

    @Test fun `gga parses position fix sats hdop altitude`() {
        val fix = NmeaParser().feed(
            "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        )
        assertNotNull(fix)
        fix!!
        assertTrue(fix.hasFix)
        assertEquals(8, fix.sats)
        assertEquals(0.9f, fix.hdop, 1e-6f)
        assertEquals(48.0 + 7.038 / 60.0, fix.lat, 1e-9)
        assertEquals(11.0 + 31.000 / 60.0, fix.lon, 1e-9)
        assertEquals(545.4, fix.altM, 1e-9)
    }

    @Test fun `rmc parses speed course validity`() {
        val fix = NmeaParser().feed(
            "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"
        )
        assertNotNull(fix)
        fix!!
        assertTrue(fix.hasFix)
        assertEquals(22.4f * 0.514444f, fix.speedMps, 1e-4f)
        assertEquals(84.4f, fix.courseDeg, 1e-6f)
        assertEquals(48.0 + 7.038 / 60.0, fix.lat, 1e-9)
    }

    @Test fun `southern western hemispheres are negative`() {
        val fix = NmeaParser().feed(
            nmea("GPGGA,123519,4436.000,S,12403.000,W,1,08,0.9,545.4,M,46.9,M,,")
        )
        assertNotNull(fix)
        assertEquals(-44.6, fix!!.lat, 1e-9)
        assertEquals(-124.05, fix.lon, 1e-9)
    }

    @Test fun `gga quality zero means no fix`() {
        val fix = NmeaParser().feed(
            nmea("GPGGA,123519,4807.038,N,01131.000,E,0,00,99.9,,M,,M,,")
        )
        assertNotNull(fix)
        assertFalse(fix!!.hasFix)
    }

    @Test fun `gsa sets fix dimension`() {
        val p = NmeaParser()
        p.feed("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47")
        val fix = p.feed("\$GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1*39")
        assertNotNull(fix)
        assertEquals(3, fix!!.fixDim)
        assertTrue(fix.hasFix)
    }

    @Test fun `talker id is ignored`() {
        val fix = NmeaParser().feed(
            nmea("GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")
        )
        assertNotNull(fix)
        assertTrue(fix!!.hasFix)
    }

    @Test fun `bad checksum rejected`() {
        assertNull(NmeaParser().feed(
            "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*48"
        ))
    }

    @Test fun `missing checksum tolerated`() {
        val fix = NmeaParser().feed(
            "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        )
        assertNotNull(fix)
        assertTrue(fix!!.hasFix)
    }

    @Test fun `garbage and empty input return null`() {
        val p = NmeaParser()
        assertNull(p.feed(""))
        assertNull(p.feed("not nmea at all"))
        assertNull(p.feed("\$GP"))                       // too short
        assertNull(p.feed("\$GPXTE,A,A,0.67,L,N*6F"))    // unknown type, valid frame
        assertNull(p.feed(nmea("GPZZZ,1,2,3")))          // unknown type
    }

    @Test fun `rmc keeps prior position when fields empty`() {
        val p = NmeaParser()
        p.feed("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47")
        val fix = p.feed(nmea("GPRMC,123520,V,,,,,,,230394,,"))
        assertNotNull(fix)
        assertEquals(48.0 + 7.038 / 60.0, fix!!.lat, 1e-9)
        assertTrue(fix.hasFix) // V doesn't clear a prior valid fix
    }
}
