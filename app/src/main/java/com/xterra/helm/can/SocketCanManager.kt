package com.xterra.helm.can

/**
 * Raw SocketCAN backend for the Khadas Edge2 IO hat (via an MCP2515 SPI
 * transceiver or RK3588 FlexCAN pins + SN65HVD230).
 *
 * Requirements on the Edge2 Android image:
 *  1. Kernel built with CONFIG_CAN, CAN_RAW, CAN_MCP251X (or FlexCAN),
 *     and a device-tree overlay enabling the SPI CS + INT pins.
 *  2. Bring the interface up at boot (init rc or root shell):
 *       ip link set can0 type can bitrate 500000
 *       ip link set can0 up
 *  3. Android's NDK exposes AF_CAN; the practical route is a small JNI
 *     library (see native/socketcan.c stub in /docs) or running `candump`
 *     via a root shell and parsing stdout.
 *
 * The Xterra (D40/N50 platform) carries powertrain data on the 500 kbit
 * HS-CAN at the OBD port pins 6/14 — the same data the ELM path reads, but
 * sniffing broadcast frames here gets you steering angle, individual wheel
 * speeds, brake pressure, and gear position at full bus rate with zero
 * request latency. IDs must be reverse-engineered per vehicle; use the
 * built-in sniffer view to diff frames while operating a control.
 */
class SocketCanManager {
    // JNI bindings — implement in native/socketcan.c and load here.
    external fun nativeOpen(ifName: String): Int
    external fun nativeRead(fd: Int, out: ByteArray): Int   // returns frame len, id packed in out[0..3]
    external fun nativeClose(fd: Int)

    companion object {
        val available: Boolean by lazy {
            try { System.loadLibrary("helmsocketcan"); true } catch (_: Throwable) { false }
        }
    }
}
