package com.xterra.helm.can

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One immutable snapshot of vehicle state, updated ~5–10 Hz. */
data class VehicleState(
    val connected: Boolean = false,
    val rpm: Int = 0,
    val speedKmh: Int = 0,
    val coolantC: Int? = null,
    val intakeC: Int? = null,
    val throttlePct: Float? = null,
    val mafGs: Float? = null,          // mass air flow g/s
    val fuelLevelPct: Float? = null,
    val batteryV: Float? = null,       // control-module voltage (start/charging bus)
    val instantMpg: Float? = null,     // derived from MAF + speed
    val avgMpg: Float? = null,
    val reverse: Boolean = false,
    val dtcCount: Int = 0,
    val milOn: Boolean = false,
    val dtcCodes: List<String> = emptyList(),
    val source: String = "—",
)

/**
 * Owns the CAN/OBD link and exposes [state]. Two backends:
 *  - [Elm327Manager]: USB ELM327/OBDLink dongle. Works on any Android, no root.
 *  - [SocketCanManager]: native can0 on the Edge2 IO hat (MCP2515/FlexCAN),
 *    for raw 500 kbit CAN sniffing of the Xterra body/powertrain bus.
 * Reverse gear additionally comes from [GpioReverseSensor] (reverse-lamp tap),
 * which is the reliable path on a 2008 Xterra — OBD does not expose gear.
 */
class CanRepository(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state

    /** Instant-MPG samples from the last 60 s (~8 Hz) for the trend graph. */
    private val _mpgHistory = MutableStateFlow<List<Float>>(emptyList())
    val mpgHistory: StateFlow<List<Float>> = _mpgHistory
    private val mpgRing = ArrayDeque<Pair<Long, Float>>()

    // Reverse now comes from the CAN bus (ID 0x60D) via ELM — no lamp-tap
    // wiring. GpioReverseSensor stays as a dormant fallback for when a hat
    // pin is eventually wired; CAN wins whenever the ECU is live.
    private var canReverse = false

    private val elm = Elm327Manager(
        context,
        onLink = { up ->
            if (up) {                       // each ignition cycle = a new trip
                mpg.reset()
                synchronized(mpgRing) { mpgRing.clear() }
                _mpgHistory.value = emptyList()
            }
            _state.value = _state.value.copy(
                connected = up, source = if (up) "ELM327/USB" else "—")
        },
    ) { partial -> merge(partial) }
    private val gpio = GpioReverseSensor { inReverse ->
        // Only honor the lamp tap when CAN isn't asserting reverse itself.
        if (!canReverse) _state.value = _state.value.copy(reverse = inReverse)
    }
    private val mpg = MpgIntegrator()

    fun start() {
        scope.launch { elm.connectAndPoll() }
        // GPIO reverse watcher intentionally NOT started: reverse comes from
        // the CAN bus now (0x60D), and pin 113 floats HIGH while unwired —
        // running the watcher self-triggers a black rear-cam overlay on every
        // power cycle. Re-enable only once an opto is physically wired.
        // scope.launch { gpio.watch() }
    }

    fun stop() { elm.close(); gpio.stop() }

    /** Pass-through to the ELM debug console (see VehicleService). */
    fun injectElm(cmds: String) = elm.inject(cmds)

    private fun merge(p: Elm327Manager.Sample) {
        val prev = _state.value
        p.reverse?.let { canReverse = it }        // null = frame missed; hold
        val inst = mpg.instantMpg(p.mafGs, p.speedKmh)
        val now = System.currentTimeMillis()
        synchronized(mpgRing) {
            inst?.let { mpgRing.addLast(now to it) }
            while (mpgRing.isNotEmpty() && now - mpgRing.first().first > 60_000)
                mpgRing.removeFirst()
            _mpgHistory.value = mpgRing.map { it.second }
        }
        _state.value = prev.copy(
            connected = true,
            rpm = p.rpm ?: prev.rpm,
            speedKmh = p.speedKmh ?: prev.speedKmh,
            coolantC = p.coolantC ?: prev.coolantC,
            intakeC = p.intakeC ?: prev.intakeC,
            throttlePct = p.throttlePct ?: prev.throttlePct,
            mafGs = p.mafGs ?: prev.mafGs,
            fuelLevelPct = p.fuelPct ?: prev.fuelLevelPct,
            batteryV = p.batteryV ?: prev.batteryV,
            instantMpg = inst ?: prev.instantMpg,
            avgMpg = mpg.average(),
            dtcCount = p.dtcCount ?: prev.dtcCount,
            milOn = p.milOn ?: prev.milOn,
            dtcCodes = p.dtcCodes ?: prev.dtcCodes,
            reverse = canReverse,
            source = "ELM327/USB",
        )
    }
}

/** MPG from MAF: fuel g/s = MAF / AFR(14.7); gal/s = g/s / (6.17 lb/gal * 453.6). */
private class MpgIntegrator {
    private var sumMiles = 0.0
    private var sumGal = 0.0
    private var lastT = 0L

    fun instantMpg(mafGs: Float?, speedKmh: Int?): Float? {
        if (mafGs == null || speedKmh == null) return null
        val now = System.currentTimeMillis()
        val dt = if (lastT == 0L) 0.0 else (now - lastT) / 1000.0
        lastT = now
        val mph = speedKmh * 0.621371
        val galPerHr = mafGs / 14.7 / 453.6 / 6.17 * 3600.0
        if (dt in 0.05..5.0) { sumMiles += mph * dt / 3600.0; sumGal += galPerHr * dt / 3600.0 }
        if (galPerHr < 0.01) return null
        return (mph / galPerHr).toFloat().coerceIn(0f, 99.9f)
    }

    fun average(): Float? =
        if (sumGal > 0.005) (sumMiles / sumGal).toFloat().coerceIn(0f, 99.9f) else null

    /** New trip: called on every ECU (re)connect, i.e. each ignition cycle. */
    fun reset() { sumMiles = 0.0; sumGal = 0.0; lastT = 0L }
}
