package com.xterra.helm.power

import java.io.DataOutputStream

/**
 * Makes Android believe the Renogy pack IS the device battery.
 *
 * Android's BatteryService accepts debug overrides via `dumpsys battery set`.
 * Once set, the status-bar icon, quick-settings percentage, and every app
 * reading BatteryManager/ACTION_BATTERY_CHANGED see the injected values.
 * The override persists until `dumpsys battery reset` or reboot, so we
 * re-push on every poll.
 *
 * Requires root (`su`), which you have on the Edge2. Commands used:
 *   dumpsys battery unplug                 # else Android pins level to AC behavior
 *   dumpsys battery set ac 1               # when charging (shows bolt)
 *   dumpsys battery set status 2|3|5       # charging | discharging | full
 *   dumpsys battery set level <0-100>
 *
 * Side effects to know about:
 *  - Battery Saver may engage at low SOC. Either desired (dims the unit as
 *    the pack drains — arguably a feature) or disable: `settings put global
 *    low_power_trigger_level 0`.
 *  - `reset` on shutdown returns control to the (nonexistent) charger state.
 *
 * Root-free alternative for a permanent image: a tiny kernel module or DT
 * node exposing a power_supply class device whose sysfs values a root
 * helper writes — healthd then treats it as genuine hardware. The su path
 * below achieves the same result with zero image changes.
 */
class SystemBatteryBridge {
    private var lastLevel = -1
    private var lastCharging: Boolean? = null

    fun push(b: BattState) {
        if (b.socPct == lastLevel && b.charging == lastCharging) return
        lastLevel = b.socPct; lastCharging = b.charging
        val status = when {
            b.socPct >= 100 -> 5           // BATTERY_STATUS_FULL
            b.charging      -> 2           // BATTERY_STATUS_CHARGING
            else            -> 3           // BATTERY_STATUS_DISCHARGING
        }
        su(
            "dumpsys battery unplug",
            "dumpsys battery set ac ${if (b.charging) 1 else 0}",
            "dumpsys battery set status $status",
            "dumpsys battery set level ${b.socPct}",
        )
    }

    fun restore() = su("dumpsys battery reset")

    private fun su(vararg cmds: String) {
        try {
            val p = Runtime.getRuntime().exec("su")
            DataOutputStream(p.outputStream).use { os ->
                cmds.forEach { os.writeBytes(it + "\n") }
                os.writeBytes("exit\n")
            }
            p.waitFor()
        } catch (_: Exception) { /* no root: silently skip mirroring */ }
    }
}
