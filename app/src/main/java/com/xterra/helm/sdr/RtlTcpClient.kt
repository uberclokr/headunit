package com.xterra.helm.sdr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * rtl_tcp protocol client — the fallback [IqSource] for a dongle attached to
 * some other box on the LAN. A dongle on the head unit's own USB bus is
 * driven directly by [RtlUsbSource] (in-app librtlsdr), no server needed.
 * IQ arrives as unsigned 8-bit interleaved pairs at [sampleRate].
 */
class RtlTcpClient(private val host: String = "127.0.0.1", private val port: Int = 1234) : IqSource {
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var inp: InputStream? = null

    override var sampleRate = 1_024_000
        private set

    override suspend fun open(): Unit = withContext(Dispatchers.IO) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 3000)
        socket = s; out = DataOutputStream(s.getOutputStream()); inp = s.getInputStream()
        inp!!.skip(12) // "RTL0" magic + tuner info header
        setSampleRate(sampleRate)
        setGainMode(auto = true)
    }

    private fun cmd(op: Byte, value: Int) {
        val b = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
        b.put(op); b.putInt(value)
        out?.write(b.array()); out?.flush()
    }

    override fun setFrequency(hz: Long) = cmd(0x01, hz.toInt())
    override fun setDirectSampling(mode: Int) = cmd(0x09, mode)   // rtl_tcp SET_DIRECT_SAMPLING
    fun setSampleRate(sps: Int) { sampleRate = sps; cmd(0x02, sps) }
    fun setGainMode(auto: Boolean) = cmd(0x03, if (auto) 0 else 1)
    fun setGain(tenthsDb: Int) { setGainMode(false); cmd(0x04, tenthsDb) }

    /** Blocking read of exactly [n] IQ bytes into [buf]. */
    override fun readFully(buf: ByteArray, n: Int): Boolean {
        val s = inp ?: return false
        var off = 0
        while (off < n) {
            val r = s.read(buf, off, n - off)
            if (r < 0) return false
            off += r
        }
        return true
    }

    override fun close() { try { socket?.close() } catch (_: Exception) {}; socket = null }
    override val connected get() = socket?.isConnected == true
}
