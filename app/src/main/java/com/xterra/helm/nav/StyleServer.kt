package com.xterra.helm.nav

import java.net.InetAddress
import java.net.ServerSocket

/**
 * Minimal loopback HTTP server that hands MapLibre's offline downloader the
 * map style. The offline FileSource won't load a `file://` style (it fetches
 * only the style doc and stalls at req=1/done=0), but it loads `http://` fine.
 * Serving from 127.0.0.1 sidesteps the VPN and needs no dependency.
 *
 * Only the style is served here; the raster tiles it references are still
 * pulled from the real USGS servers during the download.
 */
object StyleServer {
    @Volatile private var styleJson: String = ""
    private var port = 0

    /** Publish [json] and return the URL to hand to the offline definition. */
    @Synchronized
    fun serve(json: String): String {
        styleJson = json
        if (port == 0) start()
        return "http://127.0.0.1:$port/style.json"
    }

    private fun start() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        Thread {
            while (true) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                Thread {
                    try {
                        // Consume the request line + headers so the client
                        // isn't reset before we reply.
                        val br = sock.getInputStream().bufferedReader()
                        while (true) { val l = br.readLine() ?: break; if (l.isEmpty()) break }
                        val body = styleJson.toByteArray()
                        val head = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        sock.getOutputStream().apply {
                            write(head.toByteArray()); write(body); flush()
                        }
                    } catch (_: Exception) {
                    } finally { runCatching { sock.close() } }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }
}
