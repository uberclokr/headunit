package com.xterra.helm.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class SenseCapDecoderTest {

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun packet06(lat: Double, lon: Double, tempC: Float, batt: Int, motion: Int): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x06)
        o.write(byteArrayOf(0, 0, 0))                 // events
        o.write(motion)
        o.write(be32(1_700_000_000))                  // utc
        o.write(be32((lon * 1_000_000).toInt()))
        o.write(be32((lat * 1_000_000).toInt()))
        o.write(be16((tempC * 10).toInt()))
        o.write(be16(1200))                           // light
        o.write(batt)
        return o.toByteArray()
    }

    @Test fun decodesPacket06Position() {
        val s = SenseCapDecoder.decode(packet06(44.058_1, -121.312_4, 23.4f, 87, 3))!!
        assertNotNull(s.pos)
        assertEquals(44.0581, s.pos!!.lat, 1e-6)
        assertEquals(-121.3124, s.pos.lon, 1e-6)
        assertEquals(23.4f, s.tempC!!, 0.01f)
        assertEquals(87, s.batteryPct)
        assertEquals(3, s.motionCount)
    }

    @Test fun negativeLatLonDecodeCorrectly() {
        val s = SenseCapDecoder.decode(packet06(-33.8688, 151.2093, 10f, 50, 0))!!
        assertEquals(-33.8688, s.pos!!.lat, 1e-6)
        assertEquals(151.2093, s.pos.lon, 1e-6)
    }

    @Test fun zeroFixIsRejected() {
        // No GNSS lock → 0/0; must not be shown as a real position.
        val s = SenseCapDecoder.decode(packet06(0.0, 0.0, 20f, 90, 0))!!
        assertEquals(null, s.pos)
        assertEquals(90, s.batteryPct)   // battery still read
    }
}
