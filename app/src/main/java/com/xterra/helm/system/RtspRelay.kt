package com.xterra.helm.system

import android.util.Log
import com.xterra.helm.HelmApp
import java.net.ServerSocket
import java.net.Socket

/**
 * Dumb TCP relay: phone → head unit :8554 → camera :554. The phone (on the
 * WG VPN) can't reach the camera's hotspot subnet, but the head unit can —
 * so the companion app plays rtsp://<head-unit-wg-ip>:8554/live and every
 * byte is pumped straight through. Works because the companion forces RTSP
 * over TCP (interleaved), so the whole session lives on this one connection;
 * the Viofo ignores the host part of the request URI.
 */
object RtspRelay {
    const val PORT = 8554
    private const val TAG = "Helm"
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        Thread({
            val server = ServerSocket(PORT)
            Log.i(TAG, "RTSP relay on :$PORT")
            while (true) {
                val client = try { server.accept() } catch (_: Exception) { break }
                Thread({ relay(client) }, "rtsp-relay").apply { isDaemon = true }.start()
            }
        }, "rtsp-relay-acc").apply { isDaemon = true }.start()
    }

    private fun relay(client: Socket) {
        val ip = HelmApp.instance.viofo.state.value.ip
        if (ip == null) { runCatching { client.close() }; return }
        val cam = runCatching { Socket(ip, 554) }.getOrElse {
            Log.w(TAG, "relay: camera connect failed: ${it.message}")
            runCatching { client.close() }; return
        }
        // Two pumps; either side closing tears down both.
        fun pump(from: Socket, to: Socket) = Thread({
            runCatching {
                val buf = ByteArray(64 * 1024)
                val input = from.getInputStream(); val out = to.getOutputStream()
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); out.flush()
                }
            }
            runCatching { from.close() }; runCatching { to.close() }
        }, "rtsp-pump").apply { isDaemon = true }.start()
        pump(client, cam)
        pump(cam, client)
    }
}
