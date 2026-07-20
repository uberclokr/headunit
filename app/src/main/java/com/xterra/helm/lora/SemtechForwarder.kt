package com.xterra.helm.lora

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Semtech UDP "legacy" packet-forwarder server — the only protocol the wAP
 * LR8G speaks (it forwards to a network server on UDP :1700). We are that
 * server. Handles the four GWMP packet types:
 *   PUSH_DATA(0x00) → rxpk uplinks; ACK 0x01
 *   PULL_DATA(0x02) → keepalive that tells us where to send downlinks; ACK 0x04
 *   PULL_RESP(0x03) → our downlink (JoinAccept), sent to the last PULL_DATA peer
 *   TX_ACK(0x05)    → gateway's downlink result
 *
 * One gateway per vehicle, so we track a single downlink peer. Runs on IO with
 * an auto-restart loop like every other external link here.
 */
class SemtechForwarder(
    private val onUplink: (RxPacket) -> Unit,
    private val onGateway: (eui: String) -> Unit,
) {
    data class RxPacket(
        val phy: ByteArray, val tmst: Long, val freqMHz: Double,
        val datr: String, val rssi: Int, val snr: Float,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var peer: InetAddress? = null
    @Volatile private var peerPort: Int = 0
    @Volatile var gatewayEui: String? = null; private set
    private var job: kotlinx.coroutines.Job? = null

    /** (Re)start the listener on [port]; safe to call repeatedly (toggle). */
    fun start(port: Int) {
        stop()
        job = scope.launch {
            while (isActive) {
                try {
                    val s = DatagramSocket(port).also { socket = it }
                    Log.i(TAG, "LoRa forwarder on :$port")
                    val buf = ByteArray(4096)
                    while (isActive) {
                        val pkt = DatagramPacket(buf, buf.size)
                        s.receive(pkt)                 // blocks; close() throws → caught
                        handle(pkt, s)
                    }
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "LoRa forwarder: ${e.message}")
                    close(); if (isActive) kotlinx.coroutines.delay(3000)
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null; close() }
    private fun close() { runCatching { socket?.close() }; socket = null }

    private fun handle(pkt: DatagramPacket, s: DatagramSocket) {
        val d = pkt.data
        if (pkt.length < 4 || d[0].toInt() != PROTO_VER) return
        val token = byteArrayOf(d[1], d[2])
        when (d[3].toInt() and 0xFF) {
            PUSH_DATA -> {
                // [4..11] gateway EUI, then JSON.
                val eui = LoRaWan.hex(d.copyOfRange(4, 12))
                if (eui != gatewayEui) { gatewayEui = eui; onGateway(eui) }
                s.send(ack(pkt, token, PUSH_ACK))
                val json = String(d, 12, pkt.length - 12)
                parseRxpk(json)
            }
            PULL_DATA -> {
                val eui = LoRaWan.hex(d.copyOfRange(4, 12))
                if (eui != gatewayEui) { gatewayEui = eui; onGateway(eui) }
                peer = pkt.address; peerPort = pkt.port
                s.send(ack(pkt, token, PULL_ACK))
            }
            TX_ACK -> { /* downlink outcome; JSON tail has error if any */ }
        }
    }

    private fun ack(src: DatagramPacket, token: ByteArray, type: Int): DatagramPacket {
        val out = byteArrayOf(PROTO_VER.toByte(), token[0], token[1], type.toByte())
        return DatagramPacket(out, out.size, src.address, src.port)
    }

    private fun parseRxpk(json: String) {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        val arr = root.getAsJsonArray("rxpk") ?: return
        for (el in arr) {
            val o = el.asJsonObject
            val data = o.get("data")?.asString ?: continue
            val phy = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: continue
            onUplink(RxPacket(
                phy = phy,
                tmst = o.get("tmst")?.asLong ?: 0,
                freqMHz = o.get("freq")?.asDouble ?: 0.0,
                datr = o.get("datr")?.asString ?: "",
                rssi = o.get("rssi")?.asInt ?: 0,
                snr = o.get("lsnr")?.asFloat ?: 0f,
            ))
        }
    }

    /**
     * Queue a downlink (JoinAccept) via PULL_RESP to the gateway. tmst is the
     * uplink tmst + the RX1 delay in µs; freq/datr choose the RX window. NOTE
     * the RX-window timing is the one piece that needs on-hardware validation
     * with the real gateway — the crypto is unit-tested, the timing isn't.
     */
    fun sendDownlink(phy: ByteArray, tmst: Long, freqMHz: Double, datr: String) {
        val s = socket ?: return
        val p = peer ?: return
        val txpk = JsonObject().apply {
            addProperty("imme", false)
            addProperty("tmst", tmst)
            addProperty("freq", freqMHz)
            addProperty("rfch", 0)
            addProperty("powe", 27)
            addProperty("modu", "LORA")
            addProperty("datr", datr)
            addProperty("codr", "4/5")
            addProperty("ipol", true)
            addProperty("size", phy.size)
            addProperty("data", Base64.encodeToString(phy, Base64.NO_WRAP))
        }
        val body = JsonObject().apply { add("txpk", txpk) }
        val head = byteArrayOf(PROTO_VER.toByte(), 0, 0, PULL_RESP.toByte())
        val out = head + gson.toJson(body).toByteArray()
        runCatching { s.send(DatagramPacket(out, out.size, p, peerPort)) }
            .onFailure { Log.w(TAG, "downlink send failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "Helm"
        private const val PROTO_VER = 2
        private const val PUSH_DATA = 0x00
        private const val PUSH_ACK = 0x01
        private const val PULL_DATA = 0x02
        private const val PULL_RESP = 0x03
        private const val PULL_ACK = 0x04
        private const val TX_ACK = 0x05
    }
}
