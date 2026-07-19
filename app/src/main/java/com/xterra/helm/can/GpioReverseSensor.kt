package com.xterra.helm.can

import kotlinx.coroutines.delay
import java.io.File

/**
 * Reverse detection via a GPIO on the Edge2 IO hat wired (through an
 * optocoupler, e.g. PC817 + 2.2k series resistor) to the reverse-lamp
 * 12 V circuit. This is instant and unambiguous; OBD-II on the 2008
 * Xterra does not expose gear position as a standard PID.
 *
 * Export the pin once at boot (root):
 *   echo <N> > /sys/class/gpio/export
 *   echo in  > /sys/class/gpio/gpio<N>/direction
 * and chmod the value file readable, or run Helm's VehicleService with
 * a root helper. Set the pin number in [GPIO_PATH].
 */
class GpioReverseSensor(private val onChange: (Boolean) -> Unit) {
    private var running = true

    suspend fun watch() {
        val f = File(GPIO_PATH)
        // Self-heal the one-time device setup: export + configure the pin via
        // root if some prior boot didn't. Harmless no-op when already exported.
        if (!f.exists()) {
            com.xterra.helm.system.RootShell.run(
                "echo $GPIO_PIN > /sys/class/gpio/export 2>/dev/null; " +
                "echo in > /sys/class/gpio/gpio$GPIO_PIN/direction; " +
                "chmod 644 $GPIO_PATH")
        }
        var last = false
        while (running) {
            val v = try { f.readText().trim() == ACTIVE_LEVEL } catch (_: Exception) { last }
            if (v != last) { last = v; onChange(v) }
            delay(150) // 150 ms poll ≈ imperceptible for a backup-cam trigger
        }
    }

    fun stop() { running = false }

    companion object {
        const val GPIO_PIN = 113                               // set for your hat pin
        const val GPIO_PATH = "/sys/class/gpio/gpio$GPIO_PIN/value"
        const val ACTIVE_LEVEL = "1" // opto pulls high when reverse lamp is on
    }
}
