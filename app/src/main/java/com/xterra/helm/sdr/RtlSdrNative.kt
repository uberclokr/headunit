package com.xterra.helm.sdr

/** JNI surface of the in-app librtlsdr build (libhelmrtl.so). */
internal object RtlSdrNative {
    init { System.loadLibrary("helmrtl") }

    /** Opens via a UsbDeviceConnection fd; returns opaque handle or 0. */
    external fun open(fd: Int): Long
    external fun close(dev: Long)
    external fun setFrequency(dev: Long, hz: Long): Int
    external fun setSampleRate(dev: Long, sps: Int): Int
    external fun setTunerGainMode(dev: Long, manual: Int): Int
    external fun setAgcMode(dev: Long, on: Int): Int
    external fun resetBuffer(dev: Long): Int
    external fun readSync(dev: Long, buf: ByteArray, off: Int, len: Int): Int
}
