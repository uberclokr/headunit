package com.xterra.helm.system

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the Starlink dish's local gRPC endpoint
 * (192.168.100.1:9200, plaintext HTTP/2, no auth). We only make the unary
 * GetStatus call, so instead of dragging in grpc-java + generated stubs this
 * hand-frames the one request and walks the response with a tiny protobuf
 * reader. Field numbers are from the community-extracted SpaceX API protos
 * (clarkzjw/starlink-grpc-golang, matches sparky8512/starlink-grpc-tools):
 *
 *   Request.get_status            = 1004  (empty message)
 *   Response.dish_get_status      = 2004:
 *     device_state                = 2     (.uptime_s = 1, varint)
 *     obstruction_stats           = 1004  (.fraction_obstructed = 1 float,
 *                                          .currently_obstructed = 5 bool)
 *     alerts                      = 1005  (any nonzero field = an alert)
 *     downlink_throughput_bps     = 1007  (float)
 *     uplink_throughput_bps       = 1008  (float)
 *     pop_ping_latency_ms         = 1009  (float)
 *
 * OkHttp with H2_PRIOR_KNOWLEDGE speaks h2c directly — exactly what the dish
 * expects. The WG VPN would otherwise swallow 192.168.100.1, so NetRepository
 * pins a /32 bypass first (same treatment as the Viofo camera).
 */
object StarlinkClient {

    data class DishStatus(
        val uptimeS: Long = 0,
        val latencyMs: Float? = null,
        val downMbps: Float? = null,
        val upMbps: Float? = null,
        val obstructedPct: Float? = null,
        val currentlyObstructed: Boolean = false,
        val alertCount: Int = 0,
    )

    private val http = OkHttpClient.Builder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /** One GetStatus round-trip; null when the dish is unreachable. */
    fun getStatus(host: String = "192.168.100.1"): DishStatus? = runCatching {
        // protobuf: field 1004, wiretype 2 (LEN), length 0
        // tag = (1004 shl 3) or 2 = 8034 → varint E2 3E
        val pb = byteArrayOf(0xE2.toByte(), 0x3E, 0x00)
        // gRPC frame: 1 compression flag + 4-byte big-endian length + message
        val frame = byteArrayOf(0, 0, 0, 0, pb.size.toByte()) + pb
        val resp = http.newCall(
            Request.Builder()
                .url("http://$host:9200/SpaceX.API.Device.Device/Handle")
                .addHeader("te", "trailers")
                .post(frame.toRequestBody("application/grpc".toMediaType()))
                .build()
        ).execute()
        resp.use {
            val body = it.body?.bytes() ?: return null
            if (body.size < 5) return null
            parse(body.copyOfRange(5, body.size))
        }
    }.getOrNull()

    private fun parse(msg: ByteArray): DishStatus? {
        var status = DishStatus()
        var found = false
        val root = PbReader(msg)
        while (root.hasMore()) {
            val (field, wire) = root.tag() ?: break
            if (field == 2004 && wire == 2) {
                found = true
                val st = root.sub() ?: break
                while (st.hasMore()) {
                    val (f, w) = st.tag() ?: break
                    when {
                        f == 2 && w == 2 -> {           // device_state
                            val ds = st.sub() ?: break
                            while (ds.hasMore()) {
                                val (df, dw) = ds.tag() ?: break
                                if (df == 1 && dw == 0)
                                    status = status.copy(uptimeS = ds.varint())
                                else ds.skip(dw)
                            }
                        }
                        f == 1004 && w == 2 -> {        // obstruction_stats
                            val os = st.sub() ?: break
                            while (os.hasMore()) {
                                val (of, ow) = os.tag() ?: break
                                when {
                                    of == 1 && ow == 5 -> status = status.copy(
                                        obstructedPct = os.float() * 100f)
                                    of == 5 && ow == 0 -> status = status.copy(
                                        currentlyObstructed = os.varint() != 0L)
                                    else -> os.skip(ow)
                                }
                            }
                        }
                        f == 1005 && w == 2 -> {        // alerts: count set flags
                            val al = st.sub() ?: break
                            var n = 0
                            while (al.hasMore()) {
                                val (_, aw) = al.tag() ?: break
                                if (aw == 0) { if (al.varint() != 0L) n++ }
                                else al.skip(aw)
                            }
                            status = status.copy(alertCount = n)
                        }
                        f == 1007 && w == 5 ->
                            status = status.copy(downMbps = st.float() / 1e6f)
                        f == 1008 && w == 5 ->
                            status = status.copy(upMbps = st.float() / 1e6f)
                        f == 1009 && w == 5 ->
                            status = status.copy(latencyMs = st.float())
                        else -> st.skip(w)
                    }
                }
            } else root.skip(wire)
        }
        return if (found) status else null
    }

    /** Just enough protobuf wire-format to walk a response. */
    private class PbReader(private val b: ByteArray, private var p: Int = 0,
                           private val end: Int = b.size) {
        fun hasMore() = p < end

        fun varint(): Long {
            var v = 0L; var shift = 0
            while (p < end) {
                val x = b[p++].toLong()
                v = v or ((x and 0x7F) shl shift)
                if (x and 0x80L == 0L) return v
                shift += 7
            }
            return v
        }

        fun tag(): Pair<Int, Int>? {
            if (!hasMore()) return null
            val t = varint()
            return (t ushr 3).toInt() to (t and 7).toInt()
        }

        fun float(): Float {
            if (p + 4 > end) { p = end; return 0f }
            val bits = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return Float.fromBits(bits)
        }

        fun sub(): PbReader? {
            val len = varint().toInt()
            if (len < 0 || p + len > end) return null
            val r = PbReader(b, p, p + len)
            p += len
            return r
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> p = (p + 8).coerceAtMost(end)
                2 -> { val len = varint().toInt(); p = (p + len).coerceAtMost(end) }
                5 -> p = (p + 4).coerceAtMost(end)
                else -> p = end   // unknown wire type: bail on this message
            }
        }
    }
}
