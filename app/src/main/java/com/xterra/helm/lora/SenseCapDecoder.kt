package com.xterra.helm.lora

/**
 * Decodes the SenseCAP T1000 application payload (already LoRaWAN-decrypted).
 * Covers the two position packets: ID 0x06 (GNSS + sensor) and 0x09 (GNSS
 * only). All multi-byte fields are big-endian; lat/lon are int32 scaled 1e6.
 * A payload can chain several packets; we scan for the first position one.
 * Byte map (packet 0x06) from the SenseCAP T1000 payload spec:
 *   [0] id  [1..3] events  [4] motion  [5..8] utc  [9..12] lon  [13..16] lat
 *   [17..18] tempC×10  [19..20] light  [21] battery%
 * Packet 0x09 is the same through byte 16, then [17] battery% (no temp/light).
 */
object SenseCapDecoder {

    fun decode(payload: ByteArray): SenseCapSample? {
        var i = 0
        while (i < payload.size) {
            when (payload[i].toInt() and 0xFF) {
                0x06 -> if (i + 22 <= payload.size) return pos(payload, i, withSensor = true)
                0x09 -> if (i + 18 <= payload.size) return pos(payload, i, withSensor = false)
            }
            // Unknown/leading packet: we can't length-skip every SenseCAP id, so
            // advance one byte and keep scanning for a position packet.
            i++
        }
        return null
    }

    private fun pos(b: ByteArray, o: Int, withSensor: Boolean): SenseCapSample {
        val motion = b[o + 4].toInt() and 0xFF
        val utc = u32(b, o + 5)
        val lon = s32(b, o + 9) / 1_000_000.0
        val lat = s32(b, o + 13) / 1_000_000.0
        val valid = lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)
        val battery: Int
        val temp: Float?
        if (withSensor) {
            temp = s16(b, o + 17) / 10f
            battery = b[o + 21].toInt() and 0xFF
        } else {
            temp = null
            battery = b[o + 17].toInt() and 0xFF
        }
        return SenseCapSample(
            pos = if (valid) NodePosition(lat, lon) else null,
            batteryPct = battery.takeIf { it in 0..100 },
            tempC = temp,
            motionCount = motion,
            fixTimeMs = utc.takeIf { it > 0 }?.let { it * 1000 },
        )
    }

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    private fun s32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun s16(b: ByteArray, i: Int): Int {
        val v = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        return if (v >= 0x8000) v - 0x10000 else v
    }
}
