package com.xterra.helm.system

import android.util.Log
import com.google.gson.Gson
import com.xterra.helm.BuildConfig
import com.xterra.helm.HelmApp
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Lightweight status API for the companion phone app, served over the WG VPN
 * (and any local interface — the whole network footprint is private). Raw
 * ServerSocket HTTP like [com.xterra.helm.nav.StyleServer]: no framework, no
 * TLS (VPN provides transport privacy), one thread per request.
 *
 *   GET /               tiny landing page linking the companion APK
 *   GET /api/status     JSON snapshot of gps / ecm / battery / net / tilt / cam
 *   GET /api/cam/channel?ch=N   lease the Viofo live channel (0/1/2); the
 *                       companion re-calls it as a keepalive while viewing,
 *                       and it reverts to rear ~5 s after the calls stop
 *   GET /companion.apk  the companion app binary (filesDir/companion.apk,
 *                       pushed at build time; 404 until it exists)
 *
 * The RTSP relay for camera video is separate — see [RtspRelay].
 */
object ApiServer {
    const val PORT = 8080
    private const val TAG = "Helm"
    private val gson = Gson()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        if (BuildConfig.API_TOKEN.isEmpty())
            Log.w(TAG, "API_TOKEN unset — /api/* is UNAUTHENTICATED (set secret.properties)")
        Thread({
            val server = ServerSocket(PORT)
            Log.i(TAG, "API server on :$PORT")
            while (true) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                Thread({ runCatching { handle(sock) }; runCatching { sock.close() } },
                    "api-req").apply { isDaemon = true }.start()
            }
        }, "api-server").apply { isDaemon = true }.start()
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 10_000
        val br = sock.getInputStream().bufferedReader()
        val reqLine = br.readLine() ?: return
        var token: String? = null
        while (true) {
            val l = br.readLine() ?: break; if (l.isEmpty()) break
            if (l.startsWith("X-Helm-Token:", ignoreCase = true))
                token = l.substringAfter(':').trim()
        }
        val path = reqLine.split(" ").getOrNull(1) ?: "/"
        val out = sock.getOutputStream()

        // Auth gates /api/* only — the landing page and /companion.apk stay
        // open so a fresh phone (no token baked yet) can still fetch the app.
        // Enforced only when a token is configured (see start() warning).
        if (path.startsWith("/api/") && BuildConfig.API_TOKEN.isNotEmpty()
            && token != BuildConfig.API_TOKEN) {
            val body = """{"error":"unauthorized"}""".toByteArray()
            out.write(head(401, "application/json", body.size)); out.write(body); out.flush()
            return
        }

        when {
            path.startsWith("/api/status") -> {
                val body = gson.toJson(snapshot()).toByteArray()
                out.write(head(200, "application/json", body.size)); out.write(body)
            }
            path.startsWith("/api/cam/channel") -> {
                val ch = Regex("""ch=(\d)""").find(path)?.groupValues?.get(1)?.toIntOrNull()
                if (ch in 0..2) {
                    // Lease-based, not sticky: holds the companion's channel
                    // while it keeps calling, reverts to rear ~5 s after it
                    // stops (ViofoLocator.requestCompanionChannel).
                    HelmApp.instance.viofo.requestCompanionChannel(ch!!)
                    val body = """{"ok":true,"ch":$ch}""".toByteArray()
                    out.write(head(200, "application/json", body.size)); out.write(body)
                } else {
                    val body = """{"ok":false}""".toByteArray()
                    out.write(head(400, "application/json", body.size)); out.write(body)
                }
            }
            path.startsWith("/api/usage/config") -> {
                // Manual plan cap + billing anchor (the dish can't report them);
                // same store the settings pane writes to:
                //   /api/usage/config?anchorDay=24&capGb=100
                val s = HelmApp.instance.settings.state.value
                val anchor = Regex("""anchorDay=(\d+)""").find(path)?.groupValues?.get(1)
                    ?.toIntOrNull() ?: s.starlinkAnchorDay
                val cap = Regex("""capGb=([\d.]+)""").find(path)?.groupValues?.get(1)
                    ?.toFloatOrNull() ?: s.starlinkCapGb
                HelmApp.instance.settings.setStarlinkPlan(anchor, cap)
                val body = """{"ok":true,"anchorDay":$anchor,"capGb":$cap}""".toByteArray()
                out.write(head(200, "application/json", body.size)); out.write(body)
            }
            path.startsWith("/companion.apk") -> {
                val f = File(HelmApp.instance.filesDir, "companion.apk")
                if (f.isFile) {
                    out.write(head(200, "application/vnd.android.package-archive",
                        f.length().toInt()))
                    f.inputStream().use { it.copyTo(out, 64 * 1024) }
                } else {
                    val body = "companion.apk not deployed yet".toByteArray()
                    out.write(head(404, "text/plain", body.size)); out.write(body)
                }
            }
            else -> {
                val body = LANDING.toByteArray()
                out.write(head(200, "text/html", body.size)); out.write(body)
            }
        }
        out.flush()
    }

    private fun head(code: Int, type: String, len: Int): ByteArray =
        ("HTTP/1.1 $code ${if (code == 200) "OK" else "ERR"}\r\n" +
            "Content-Type: $type\r\nContent-Length: $len\r\n" +
            "Connection: close\r\n\r\n").toByteArray()

    /** Everything the companion dashboard shows, in one poll. */
    private fun snapshot(): Map<String, Any?> {
        val app = HelmApp.instance
        val gps = app.gps.state.value
        val can = app.can.state.value
        val batt = app.battery.state.value
        val net = app.net.state.value
        val home = app.homeLink.state.value
        val tilt = app.tilt.state.value
        val cam = app.viofo.state.value
        return mapOf(
            "time" to System.currentTimeMillis(),
            "gps" to mapOf(
                "fix" to gps.hasFix, "fixDim" to gps.fixDim, "sats" to gps.sats,
                "hdop" to gps.hdop, "lat" to gps.lat, "lon" to gps.lon,
                "altM" to gps.altM, "speedMps" to gps.speedMps,
                "courseDeg" to gps.courseDeg,
            ),
            "ecm" to mapOf(
                "connected" to can.connected, "rpm" to can.rpm,
                "speedKmh" to can.speedKmh, "coolantC" to can.coolantC,
                "intakeC" to can.intakeC, "fuelPct" to can.fuelLevelPct,
                "starterV" to can.batteryV, "instantMpg" to can.instantMpg,
                "avgMpg" to can.avgMpg, "dtcCount" to can.dtcCount,
                "milOn" to can.milOn, "dtcCodes" to can.dtcCodes,
                "reverse" to can.reverse,
                "doorOpen" to can.doorOpen, "bcmByte0" to can.bcmByte0,
            ),
            "battery" to mapOf(
                "connected" to batt.connected, "socPct" to batt.socPct,
                "volts" to batt.volts, "amps" to batt.amps, "watts" to batt.watts,
                "remainingAh" to batt.remainingAh, "capacityAh" to batt.capacityAh,
                "tempC" to batt.tempC, "hoursRemaining" to batt.hoursRemaining,
                "charging" to batt.charging,
            ),
            "net" to mapOf(
                "staSsid" to net.staSsid, "staRssi" to net.staRssi,
                "apUp" to net.apUp, "apClients" to net.apClients,
                "inetMs" to net.inetMs,
                "usageBytes" to net.usageBytes, "usageCapGb" to net.usageCapGb,
                "usageCycleStartEpochDay" to net.usageCycleStartEpochDay,
                "wgReachable" to home.reachable, "wgMs" to home.latencyMs,
                "dish" to net.dish?.let { d ->
                    mapOf("uptimeS" to d.uptimeS, "latencyMs" to d.latencyMs,
                        "downMbps" to d.downMbps, "upMbps" to d.upMbps,
                        "obstructedPct" to d.obstructedPct,
                        "currentlyObstructed" to d.currentlyObstructed,
                        "alertCount" to d.alertCount)
                },
            ),
            "tilt" to mapOf(
                "rollDeg" to tilt.rollDeg, "pitchDeg" to tilt.pitchDeg,
                "latG" to tilt.latG, "lonG" to tilt.lonG,
            ),
            "cam" to mapOf(
                "reachable" to cam.reachable, "ip" to cam.ip,
                "rtspRelayPort" to RtspRelay.PORT,
            ),
        )
    }

    private val LANDING = """
        <html><head><meta name=viewport content="width=device-width,initial-scale=1">
        <title>Helm</title>
        <style>body{background:#0b0f14;color:#e8edf2;font-family:monospace;
        padding:2em}a{color:#ffb454;font-size:1.4em}</style></head>
        <body><h2>HELM &mdash; Xterra head unit</h2>
        <p><a href="/companion.apk">&#8681; download companion app (apk)</a></p>
        <p style="color:#8a94a0">install, then open &mdash; it is pre-configured
        for this vehicle's VPN address.</p></body></html>
    """.trimIndent()
}
