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

    /**
     * Direct-sampling mode: 0 = off (normal, tuner ≥ 24 MHz), 2 = Q-branch
     * (HF/MW below the tuner's floor — AM broadcast, CB SSB). Bypasses the
     * R820T so the RTL2832 samples the antenna directly. No-op by default.
     */
    fun setDirectSampling(mode: Int) {}

    /** Blocking read of exactly [n] bytes into [buf]; false on stream loss. */
    fun readFully(buf: ByteArray, n: Int): Boolean
    fun close()
}
