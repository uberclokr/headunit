package com.xterra.helm.system

import android.util.Log

/**
 * Keeps the head unit's own WiFi AP up at all times so the Viofo (and any
 * other recon gear) has a network to join, WHILE wlan0 stays a client on
 * Starlink ("lavalink") for internet + the WG tunnel. The RK3588 WiFi does
 * dual-band STA+AP concurrency (STA 5 GHz / AP 2.4 GHz), verified 2026-07-17.
 *
 * `cmd wifi start-softap` brings up a *temporary* AP that does NOT survive a
 * reboot and self-shuts-down when idle, so [ensure] is called on boot/start
 * and by a watchdog: it disables the idle timeout and (re)starts the AP
 * whenever the AP interface is down. Needs root (present on this image).
 */
object HotspotManager {
    // Defaults mirror HelmSettings; VehicleService applies the persisted
    // values before the first ensure(). No hyphen in the SSID — the Viofo is
    // provisioned to join "helmnet"; keep them matched.
    var ssid = "helmnet"
    var pass = "changeme"                 // WPA2, 8–63 chars — set a strong one in Settings
    const val AP_IFACE = "wlan1"          // SoftAP lands here on this image
    const val SUBNET = "192.168.133"      // dnsmasq hands the camera .x here
    // lavalink's 5 GHz BSSID. This WiFi chip (AP6275P) does STRICT same-channel
    // concurrency — the AP is forced onto the STA's exact channel, so STA and
    // AP CANNOT be on different bands. Viofo needs a 5 GHz AP, so we pin the
    // STA to 5 GHz; the AP then co-channels to 5 GHz. Update if the router's
    // BSSID changes.
    var staBssid = ""                     // your router's 5 GHz BSSID; set in Settings
    private const val SOCK = "/data/vendor/wifi/wpa/sockets"
    private const val TAG = "Helm"

    /** True when the AP interface is up with an IPv4 (AP running). */
    fun apUp(): Boolean =
        RootShell.run("ip -4 addr show dev $AP_IFACE 2>/dev/null")?.contains("inet ") == true

    /** Tear the AP down so the next ensure() brings it up with new params. */
    fun restartAp() {
        RootShell.run("cmd wifi stop-softap")
        ensure()
    }

    /** Idempotent: keep STA on 5 GHz, disable AP idle shutdown, (re)start AP. */
    fun ensure() {
        // CRITICAL for a 5 GHz AP: the driver boots into world domain "00",
        // which marks 5 GHz as passive-scan / no-initiate-radiation → the
        // SoftAP shows ENABLED but its beacon is suppressed and NOTHING sees
        // it. Forcing a real country code (US, we're in Oregon) unlocks
        // UNII-1 (ch36-48) for AP beaconing. Must precede start-softap.
        ensureCountry()
        // Android 14 bakes a 10-min idle shutdown into the shell-started AP
        // config (mIdleShutdownTimeoutMillis=600000) and ignores this global,
        // so a clientless AP dies every 10 min and this watchdog revives it.
        // Once the camera associates the idle timer never fires.
        RootShell.run("settings put global soft_ap_timeout_enabled 0")
        RootShell.run("svc wifi enable")   // STA auto-reconnects to saved lavalink
        pinSta5GHz()
        if (!apUp()) {
            Log.i(TAG, "SoftAP down — starting $ssid on 5 GHz")
            RootShell.run("cmd wifi start-softap $ssid wpa2 $pass -b 5")
        }
    }

    private var countrySet = false
    private fun ensureCountry() {
        if (countrySet) return
        val cur = RootShell.run("cmd wifi get-country-code")?.substringAfter("=")?.trim()
        if (cur != "US") RootShell.run("cmd wifi force-country-code enabled US")
        countrySet = true
    }

    /**
     * Bias lavalink to its 5 GHz radio so the co-channel AP stays 5 GHz. Only
     * reassociates if the STA actually drifted to 2.4 GHz (reassoc drops the
     * VPN briefly, so we don't do it every tick). Best-effort: the framework
     * owns network selection, hence re-asserting each watchdog pass.
     */
    private fun pinSta5GHz() {
        val id = RootShell.run("wpa_cli -i wlan0 -p $SOCK status")
            ?.let { Regex("""(?m)^id=(\d+)""").find(it)?.groupValues?.get(1) } ?: "0"
        RootShell.run("wpa_cli -i wlan0 -p $SOCK set_network $id bssid $staBssid")
        val freq = RootShell.run("wpa_cli -i wlan0 -p $SOCK status")
            ?.let { Regex("""freq=(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        if (freq in 2400..2500) {
            Log.i(TAG, "STA drifted to 2.4 GHz ($freq) — reassociating to 5 GHz")
            RootShell.run("wpa_cli -i wlan0 -p $SOCK reassociate")
        }
    }
}
