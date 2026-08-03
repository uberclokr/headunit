package com.xterra.helm.power

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class BattState(
    val connected: Boolean = false,
    val socPct: Int = 0,
    val volts: Float = 0f,
    val amps: Float = 0f,            // + charging, − discharging
    val watts: Float = 0f,
    val remainingAh: Float = 0f,
    val capacityAh: Float = 0f,
    val tempC: Float? = null,
    val cellVolts: List<Float> = emptyList(),
    val hoursRemaining: Float? = null, // to empty (discharge) or full (charge)
    val charging: Boolean = false,
)

enum class RenogyKind {
    SMART_BATTERY,   // RBT100LFP12-BT etc. (built-in BLE, 5000-range registers)
    BT2_CONTROLLER,  // Rover/Wanderer/DCC via BT-1/BT-2 (0x0100-range registers)
}

/**
 * Polls the Renogy monitor every [POLL_MS], publishes [state], and mirrors
 * SOC into the Android system battery via [SystemBatteryBridge] so the OS
 * status bar, quick settings, and every app's BatteryManager reflect the
 * battery pack that actually powers this system, instead of a nonexistent internal cell.
 *
 * Register maps follow the community-documented Renogy Modbus layout
 * (renogy-bt). If your unit is the standalone 500 A shunt monitor rather
 * than a smart battery, sniff one exchange with nRF Connect and adjust
 * REG_* below — the transport layer is identical.
 */
class BatteryRepository(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ble = RenogyBleClient(context)
    private val bridge = SystemBatteryBridge()

    private val _state = MutableStateFlow(BattState())
    val state: StateFlow<BattState> = _state

    // ── configure for your hardware ─────────────────────────────
    var mac = ""                          // your Renogy BLE MAC — find via nRF Connect
    var mirrorToSystem = true
    // kind + deviceId are auto-detected on every connect (see detect()), so
    // moving the BT-2 between the DC-DC charger and the smart battery works
    // without a code change.
    private var kind = RenogyKind.BT2_CONTROLLER
    private var deviceId = 0xFF
    private var detected = false
    // ────────────────────────────────────────────────────────────

    fun start() = scope.launch {
        while (isActive) {
            if (!ble.connected) {
                detected = false
                if (!ble.connect(mac)) {
                    android.util.Log.i("Helm", "Renogy connect failed ($mac)")
                    markDown(); delay(8_000); continue
                }
                android.util.Log.i("Helm", "Renogy connected ($mac)")
            }
            if (!detected && !detect()) { markDown(); delay(4_000); continue }
            drainProbes()
            val s = when (kind) {
                RenogyKind.SMART_BATTERY -> pollSmartBattery()
                RenogyKind.BT2_CONTROLLER -> pollController()
            }
            if (s == null) { ble.close(); markDown(); delay(4_000); continue }
            _state.value = s
            if (mirrorToSystem) bridge.push(s)
            delay(POLL_MS)
        }
    }

    /**
     * Identify the device behind the BT-2 and its Modbus address. Smart
     * batteries answer the 5000-range (true coulomb-counted SOC + signed
     * current); charge controllers / DC-DC only answer 0x0100 (charge-side).
     * Renogy devices can sit at different Modbus addresses, so scan a few.
     */
    private suspend fun detect(): Boolean {
        val model = ble.readRegisters(deviceId, 0x000C, 8)?.let { regs ->
            regs.joinToString("") {
                "${((it shr 8) and 0xFF).toChar()}${(it and 0xFF).toChar()}"
            }.filter { it.isLetterOrDigit() || it == '-' }
        }
        for (id in ID_CANDIDATES) {
            if (ble.readRegisters(id, 5042, 6) != null) {
                deviceId = id; kind = RenogyKind.SMART_BATTERY; detected = true
                android.util.Log.i("Helm", "Renogy = SMART_BATTERY id=$id model='$model'")
                return true
            }
        }
        for (id in ID_CANDIDATES) {
            if (ble.readRegisters(id, 0x0100, 4) != null) {
                deviceId = id; kind = RenogyKind.BT2_CONTROLLER; detected = true
                android.util.Log.i("Helm", "Renogy = CONTROLLER id=$id model='$model'")
                return true
            }
        }
        android.util.Log.i("Helm", "Renogy device not identified (model='$model')")
        return false
    }

    private suspend fun pollSmartBattery(): BattState? {
        // 5042 current(0.01A signed) 5043 volts(0.1) 5044-45 remain(0.001Ah)
        // 5046-47 capacity(0.001Ah)
        val main = ble.readRegisters(deviceId, 5042, 6) ?: return null
        val cellCnt = ble.readRegisters(deviceId, 5000, 1)?.getOrNull(0)?.coerceIn(0, 16) ?: 0
        val cells = if (cellCnt > 0)
            ble.readRegisters(deviceId, 5001, cellCnt)?.map { it / 10f } ?: emptyList()
        else emptyList()
        val temp = ble.readRegisters(deviceId, 5018, 1)
            ?.getOrNull(0)?.let { signed16(it) / 10f }

        val amps = signed16(main[0]) / 100f
        val volts = main[1] / 10f
        val remainAh = ((main[2] shl 16) or main[3]) / 1000f
        val capAh = ((main[4] shl 16) or main[5]) / 1000f
        val soc = if (capAh > 0f) (remainAh / capAh * 100f).toInt().coerceIn(0, 100) else 0
        val hrs = when {
            amps < -0.05f -> remainAh / -amps
            amps > 0.05f  -> (capAh - remainAh) / amps
            else -> null
        }
        return BattState(
            connected = true, socPct = soc, volts = volts, amps = amps,
            watts = volts * amps, remainingAh = remainAh, capacityAh = capAh,
            tempC = temp, cellVolts = cells, hoursRemaining = hrs,
            charging = amps > 0.05f,
        )
    }

    private suspend fun pollController(): BattState? {
        // 0x0100 SOC | 0x0101 battV(0.1) | 0x0102 chargeA(0.01) | 0x0103 temps
        // Verified 2026-07-16 on this BT-2: 0064 0085 0024 1E1A = 100% 13.3V
        // 0.36A 26°C. Map matches the Rover/DCC controller layout.
        val r = ble.readRegisters(deviceId, 0x0100, 4) ?: return null
        val volts = r[1] / 10f
        val amps = r[2] / 100f
        val battTemp = signed8(r[3] and 0xFF).toFloat()
        return BattState(
            connected = true, socPct = r[0].coerceIn(0, 100), volts = volts,
            amps = amps, watts = volts * amps, tempC = battTemp,
            charging = amps > 0.05f,
        )
    }

    private fun markDown() {
        _state.value = _state.value.copy(connected = false)
    }

    fun stop() { scope.cancel(); ble.close(); bridge.restore() }

    /** One-shot BLE discovery to logcat; used to find the Renogy MAC. */
    fun discover() = scope.launch { ble.scan(12) }

    // ── register probe (device identification / register-map debugging) ──
    private val probes = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, Int>>()

    fun probe(reg: Int, count: Int) { probes.add(reg to count) }

    /** Enqueue the standard diagnostic set: model string + both maps. */
    fun probeStandard() {
        listOf(0x000C to 8, 0x0100 to 9, 0x0104 to 3, 0x5000 to 1,
               0x5042 to 6, 0x13B2 to 6).forEach { probes.add(it) }
    }

    private suspend fun drainProbes() {
        while (true) {
            val (reg, count) = probes.poll() ?: return
            val r = ble.readRegisters(deviceId, reg, count)
            val hex = r?.joinToString(" ") { "%04X".format(it) } ?: "null"
            val ascii = r?.joinToString("") { r0 ->
                "${((r0 shr 8) and 0xFF).toChar()}${(r0 and 0xFF).toChar()}"
            }?.filter { it.isLetterOrDigit() || it == ' ' || it == '-' } ?: ""
            android.util.Log.i("Helm", "REN dev$deviceId 0x%04X x%d -> %s | \"%s\""
                .format(reg, count, hex, ascii))
        }
    }

    private fun signed16(v: Int) = if (v > 32767) v - 65536 else v
    private fun signed8(v: Int) = if (v > 127) v - 256 else v

    companion object {
        const val POLL_MS = 5_000L
        // Renogy Modbus addresses seen in the wild: 0xFF broadcast, 0x30 (48)
        // smart battery, 0x01 controller, 0xF7 some DC-DC.
        private val ID_CANDIDATES = intArrayOf(0xFF, 0x30, 0x01, 0xF7)
    }
}
