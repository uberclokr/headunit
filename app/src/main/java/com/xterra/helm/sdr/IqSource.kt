package com.xterra.helm.sdr

/**
 * A tuned source of unsigned 8-bit interleaved IQ. Two implementations:
 * [RtlUsbSource] (dongle on our own USB bus, in-app driver) and
 * [RtlTcpClient] (rtl_tcp anywhere on the LAN). SdrRepository prefers USB.
 */
interface IqSource {
    val sampleRate: Int
    val connected: Boolean

    /** Acquire the device/link and apply sample rate + AGC. Throws on failure. */
    suspend fun open()
    fun setFrequency(hz: Long)

    /** Blocking read of exactly [n] bytes into [buf]; false on stream loss. */
    fun readFully(buf: ByteArray, n: Int): Boolean
    fun close()
}
