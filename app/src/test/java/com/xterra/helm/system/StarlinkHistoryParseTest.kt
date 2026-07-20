package com.xterra.helm.system

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Guards the get_history protobuf parse — specifically that BOTH throughput
 * arrays come through intact. The original bug read `p + varint()` in one
 * expression, capturing p before the length varint advanced it, which read
 * one float short on downlink and left uplink empty.
 */
class StarlinkHistoryParseTest {

    private fun varint(v: Long): ByteArray {
        val o = ByteArrayOutputStream(); var x = v
        while (true) {
            val b = (x and 0x7F).toInt(); x = x ushr 7
            if (x != 0L) o.write(b or 0x80) else { o.write(b); break }
        }
        return o.toByteArray()
    }

    private fun tag(field: Int, wire: Int) = varint(((field.toLong()) shl 3) or wire.toLong())

    private fun packed(vals: FloatArray): ByteArray {
        val o = ByteArrayOutputStream()
        for (f in vals) {
            val bits = f.toRawBits()
            o.write(bits and 0xFF); o.write((bits ushr 8) and 0xFF)
            o.write((bits ushr 16) and 0xFF); o.write((bits ushr 24) and 0xFF)
        }
        return o.toByteArray()
    }

    private fun lenField(field: Int, payload: ByteArray): ByteArray =
        tag(field, 2) + varint(payload.size.toLong()) + payload

    private fun varField(field: Int, value: Long): ByteArray = tag(field, 0) + varint(value)

    @Test fun parsesCurrentAndBothThroughputArrays() {
        val down = floatArrayOf(8_000_000f, 16_000_000f, 24_000_000f)
        val up = floatArrayOf(1_000_000f, 2_000_000f, 3_000_000f)
        // dish_get_history payload: current=1, downlink=1003, uplink=1004
        val inner = varField(1, 4242L) +
            lenField(1003, packed(down)) +
            lenField(1004, packed(up))
        // Response: apiVersion (field 3) then dish_get_history (field 2006).
        val msg = varField(3, 42L) + lenField(2006, inner)

        val h = StarlinkClient.parseHistory(msg)!!
        assertEquals(4242L, h.current)
        assertEquals(down.toList(), h.downlinkBps.toList())   // full array, not one short
        assertEquals(up.toList(), h.uplinkBps.toList())       // present, not empty
    }
}
