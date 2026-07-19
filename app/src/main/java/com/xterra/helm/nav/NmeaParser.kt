package com.xterra.helm.nav

/**
 * Incremental NMEA-0183 parser. Feed raw sentences; it accumulates GGA (fix
 * quality, sats, HDOP, altitude, position), RMC (position, speed, course,
 * validity) and GSA (2D/3D fix dimension) into a running [GpsFix] and returns
 * the updated fix on any recognized sentence. Pure/testable — no I/O.
 *
 * Talker id is ignored (GP/GN/GL/GA all accepted) so multi-constellation
 * receivers work.
 */
class NmeaParser {
    private var cur = GpsFix()

    fun feed(raw: String): GpsFix? {
        val line = raw.trim()
        if (!line.startsWith("$") || line.length < 7) return null
        val body = line.substringBefore('*')
        if (!checksumOk(line)) return null
        val f = body.split(',')
        val type = f[0].drop(3)        // strip "$GP"/"$GN" etc.
        return when (type) {
            "GGA" -> gga(f)
            "RMC" -> rmc(f)
            "GSA" -> gsa(f)
            else -> null
        }
    }

    private fun gga(f: List<String>): GpsFix {
        val quality = f.getOrNull(6)?.toIntOrNull() ?: 0
        val sats = f.getOrNull(7)?.toIntOrNull() ?: cur.sats
        val hdop = f.getOrNull(8)?.toFloatOrNull() ?: cur.hdop
        val lat = coord(f.getOrNull(2), f.getOrNull(3))
        val lon = coord(f.getOrNull(4), f.getOrNull(5))
        val alt = f.getOrNull(9)?.toDoubleOrNull() ?: cur.altM
        cur = cur.copy(
            hasFix = quality > 0,
            sats = sats, hdop = hdop, altM = alt,
            lat = lat ?: cur.lat, lon = lon ?: cur.lon,
            epochMillis = System.currentTimeMillis(),
        )
        return cur
    }

    private fun rmc(f: List<String>): GpsFix {
        val valid = f.getOrNull(2) == "A"
        val lat = coord(f.getOrNull(3), f.getOrNull(4))
        val lon = coord(f.getOrNull(5), f.getOrNull(6))
        val speedKts = f.getOrNull(7)?.toFloatOrNull() ?: 0f
        val course = f.getOrNull(8)?.toFloatOrNull() ?: cur.courseDeg
        cur = cur.copy(
            hasFix = valid || cur.hasFix,
            lat = lat ?: cur.lat, lon = lon ?: cur.lon,
            speedMps = speedKts * 0.514444f,   // knots → m/s
            courseDeg = course,
            epochMillis = System.currentTimeMillis(),
        )
        return cur
    }

    private fun gsa(f: List<String>): GpsFix {
        // field 2: 1 = no fix, 2 = 2D, 3 = 3D
        val dim = f.getOrNull(2)?.toIntOrNull() ?: 1
        cur = cur.copy(fixDim = if (dim >= 2) dim else 0, hasFix = dim >= 2 && cur.hasFix)
        return cur
    }

    /** ddmm.mmmm / dddmm.mmmm + hemisphere → signed decimal degrees. */
    private fun coord(value: String?, hemi: String?): Double? {
        if (value.isNullOrBlank() || hemi.isNullOrBlank()) return null
        val dot = value.indexOf('.')
        if (dot < 3) return null
        val deg = value.substring(0, dot - 2).toDoubleOrNull() ?: return null
        val min = value.substring(dot - 2).toDoubleOrNull() ?: return null
        var dd = deg + min / 60.0
        if (hemi == "S" || hemi == "W") dd = -dd
        return dd
    }

    /** Validate the *HH XOR checksum; tolerate sentences that omit it. */
    private fun checksumOk(line: String): Boolean {
        val star = line.indexOf('*')
        if (star < 0) return true                 // no checksum present — accept
        val given = line.substring(star + 1).trim().toIntOrNull(16) ?: return false
        var x = 0
        for (i in 1 until star) x = x xor line[i].code
        return x == given
    }
}
