package com.xterra.helm.system

import java.net.NetworkInterface

/**
 * The head unit's own WireGuard address — what the companion phone points at.
 * Scans the tun/wg interface for its 10.x/100.x IPv4 (this vehicle's WG net
 * is 10.255.1.0/24). Falls back to any private IPv4 so the QR is never blank
 * on a bench without the tunnel up.
 */
object WgAddress {
    fun get(): String? = runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces().toList()
        fun addrs(pred: (NetworkInterface) -> Boolean) = ifaces.filter(pred)
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it.address.size == 4 }
            .map { it.hostAddress }

        addrs { it.name.startsWith("tun") || it.name.startsWith("wg") }
            .firstOrNull { it?.startsWith("10.") == true || it?.startsWith("100.") == true }
            ?: addrs { true }.firstOrNull { it?.startsWith("10.") == true }
            ?: addrs { it.name.startsWith("wlan") }.firstOrNull()
    }.getOrNull()
}
