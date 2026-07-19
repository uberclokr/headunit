package com.xterra.helm.system

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Vehicle attitude (pitch/roll) from the head unit's accelerometer — the
 * Edge2 has NO gyro or magnetometer (verified via sensorservice), so this is
 * a gravity-vector inclinometer, the same principle as a dedicated off-road
 * gauge. At crawl speed gravity is the truth; under braking/acceleration the
 * reading is corrupted, which [TiltState.dynamic] flags (|a| far from 1 g).
 *
 * Frame handling: the unit is dash-mounted at an unknown tilt, so ZERO
 * (parked on level ground) captures the gravity vector as "level" and builds
 * a vehicle frame from it: device +X is assumed to be the vehicle's lateral
 * axis (true for a landscape screen mounted square in the dash), forward =
 * lateral × down. Pitch/roll are then projections of live gravity onto that
 * frame. Signs follow "+pitch = nose up, +roll = right side down"; the axis
 * sense for the mount is a live setting (settings pane → INCLINOMETER),
 * both inverted by default per the 2026-07-18 road validation.
 *
 * Calibration persists via SettingsRepository so ZERO survives reboots.
 */
class TiltRepository(context: Context, private val settings: SettingsRepository) {

    data class TiltState(
        val pitchDeg: Float = 0f,          // + nose up
        val rollDeg: Float = 0f,           // + right side down
        val peakPitchUp: Float = 0f, val peakPitchDown: Float = 0f,
        val peakRollRight: Float = 0f, val peakRollLeft: Float = 0f,
        // Felt (inertial) force in g, vehicle frame — what pushes the
        // occupants. Sign sense follows the axis-invert settings so the
        // gauge arrows agree with the gauge rotation.
        val latG: Float = 0f,              // toward the leaning side of a turn
        val lonG: Float = 0f,              // + rearward (throttle), − forward (braking)
        val dynamic: Boolean = false,      // accel ≠ 1 g → reading suspect
        val calibrated: Boolean = false,   // ZERO has been captured
        val available: Boolean = true,
    )

    private val _state = MutableStateFlow(TiltState())
    val state: StateFlow<TiltState> = _state

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Low-passed gravity estimate, device frame. ~0.25 s time constant at
    // 50 Hz — steady needle without hiding real terrain changes.
    private val g = floatArrayOf(0f, 0f, 9.81f)
    private var seeded = false

    // Vehicle frame (device coords), rebuilt from the calibration vector.
    private var down = floatArrayOf(0f, 0f, 1f)
    private var lat = floatArrayOf(1f, 0f, 0f)
    private var fwd = floatArrayOf(0f, 1f, 0f)

    // Second-stage smoothing on the output angles + display state.
    private var sRoll = 0f
    private var sPitch = 0f
    private var anglesSeeded = false
    private var publishCount = 0
    // Fast-smoothed felt G (quicker than tilt — forces are the short events).
    private var sLatG = 0f
    private var sLonG = 0f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            // Stage 1: gravity EMA. 0.05 @ ~50 Hz ≈ 0.4 s time constant —
            // rides out washboard/engine vibration.
            val a = 0.05f
            if (!seeded) {
                g[0] = e.values[0]; g[1] = e.values[1]; g[2] = e.values[2]
                seeded = true
                // No stored ZERO: provisionally level to wherever we woke up
                // so the needle moves sensibly; UI nags to ZERO properly.
                if (!_state.value.calibrated) buildFrame(g.clone())
            } else {
                g[0] += a * (e.values[0] - g[0])
                g[1] += a * (e.values[1] - g[1])
                g[2] += a * (e.values[2] - g[2])
            }
            val mag = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
            if (mag < 1f) return
            val rawMag = sqrt(e.values[0] * e.values[0] +
                e.values[1] * e.values[1] + e.values[2] * e.values[2])
            val dynamic = abs(rawMag - 9.81f) > 0.8f

            val gn = floatArrayOf(g[0] / mag, g[1] / mag, g[2] / mag)
            // Axis sense comes from settings (live — toggling applies on the
            // next sample). Both default inverted for this dash mount.
            val cfg = settings.state.value
            val roll = Math.toDegrees(
                asin((dot(gn, lat)).coerceIn(-1f, 1f).toDouble()).toFloat().toDouble()
            ).toFloat() * (if (cfg.tiltInvertRoll) -1f else 1f)
            val pitch = Math.toDegrees(
                asin((dot(gn, fwd)).coerceIn(-1f, 1f).toDouble()).toFloat().toDouble()
            ).toFloat() * (if (cfg.tiltInvertPitch) -1f else 1f)

            // Stage 2: EMA on the angles themselves — kills the residual
            // fraction-of-a-degree flutter the vector filter lets through.
            if (!anglesSeeded) { sRoll = roll; sPitch = pitch; anglesSeeded = true }
            sRoll += 0.12f * (roll - sRoll)
            sPitch += 0.12f * (pitch - sPitch)

            // Felt G: dynamic accel (raw minus gravity estimate) projected
            // on the vehicle axes, negated to the inertial "push" the crew
            // feels, sign-matched to the display axes. ~0.15 s EMA — snappy
            // enough for braking events, calm enough to read.
            val dyn = floatArrayOf(
                e.values[0] - g[0], e.values[1] - g[1], e.values[2] - g[2])
            val latRaw = -dot(dyn, lat) / 9.81f * (if (cfg.tiltInvertRoll) -1f else 1f)
            val lonRaw = -dot(dyn, fwd) / 9.81f * (if (cfg.tiltInvertPitch) -1f else 1f)
            sLatG += 0.25f * (latRaw - sLatG)
            sLonG += 0.25f * (lonRaw - sLonG)

            // Publish at ~12 Hz, not sensor rate: the needle animates
            // smoothly and the numerals stop churning their last digit.
            if (publishCount++ % 4 != 0) return
            val s = _state.value
            _state.value = s.copy(
                pitchDeg = sPitch, rollDeg = sRoll, dynamic = dynamic,
                latG = sLatG, lonG = sLonG,
                // Peaks only from trusted (static) readings.
                peakPitchUp = if (!dynamic) maxOf(s.peakPitchUp, sPitch) else s.peakPitchUp,
                peakPitchDown = if (!dynamic) minOf(s.peakPitchDown, sPitch) else s.peakPitchDown,
                peakRollRight = if (!dynamic) maxOf(s.peakRollRight, sRoll) else s.peakRollRight,
                peakRollLeft = if (!dynamic) minOf(s.peakRollLeft, sRoll) else s.peakRollLeft,
            )
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun start() {
        if (accel == null) { _state.value = TiltState(available = false); return }
        // Restore persisted ZERO before samples arrive.
        settings.state.value.tiltCal.split(",").mapNotNull { it.toFloatOrNull() }
            .takeIf { it.size == 3 }?.let {
                buildFrame(floatArrayOf(it[0], it[1], it[2]))
                _state.value = _state.value.copy(calibrated = true)
            }
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = sm.unregisterListener(listener)

    /** Capture "this is level" — park on flat ground first. */
    fun zero() {
        if (!seeded) return
        buildFrame(g.clone())
        anglesSeeded = false   // re-seed the angle smoother in the new frame
        settings.setTiltCal("${g[0]},${g[1]},${g[2]}")
        _state.value = _state.value.copy(
            calibrated = true,
            peakPitchUp = 0f, peakPitchDown = 0f, peakRollRight = 0f, peakRollLeft = 0f,
        )
    }

    fun resetPeaks() {
        _state.value = _state.value.copy(
            peakPitchUp = 0f, peakPitchDown = 0f, peakRollRight = 0f, peakRollLeft = 0f)
    }

    /** down = calibration gravity; lateral = device +X ⊥ down; fwd = lat × down. */
    private fun buildFrame(g0: FloatArray) {
        val m = sqrt(g0[0] * g0[0] + g0[1] * g0[1] + g0[2] * g0[2])
        if (m < 1f) return
        down = floatArrayOf(g0[0] / m, g0[1] / m, g0[2] / m)
        val x = floatArrayOf(1f, 0f, 0f)
        val proj = dot(x, down)
        val l = floatArrayOf(x[0] - proj * down[0], x[1] - proj * down[1], x[2] - proj * down[2])
        val lm = sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2])
        if (lm < 0.2f) return   // device X near-vertical: mount assumption broken
        lat = floatArrayOf(l[0] / lm, l[1] / lm, l[2] / lm)
        fwd = cross(lat, down)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

}
