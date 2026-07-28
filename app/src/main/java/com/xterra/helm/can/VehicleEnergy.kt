package com.xterra.helm.can

import kotlin.math.max

/**
 * Pure derivations over OBD + house-battery values — fuel range, idle
 * endurance, and charge-system health (PROPOSALS.md Tier 1). Deliberately free
 * of Android / StateFlow so the math is unit-tested (see VehicleEnergyTest) and
 * callable from any widget, the nav map, or the API. Inputs are nullable to
 * mirror "PID not answered yet"; every result is null when its inputs aren't
 * trustworthy, so callers render a plain "—" rather than a fabricated number.
 */
object VehicleEnergy {

    /**
     * 2nd-gen (N50) Xterra VQ40 tank ≈ 21.1 US gal. Inline config per the v0.9
     * convention (CLAUDE.md); promote to a DataStore setting when the settings
     * screen grows one — the fuel-level PID reads %, so the tank size is the
     * only calibration between "%" and "gallons/range".
     */
    const val TANK_GAL = 21.1f

    /** Range planning holds back this fraction of the tank as reserve. */
    const val RESERVE_FRAC = 0.10f

    /** RPM above which the engine is running (not just cranking/noise). */
    const val ENGINE_RUN_RPM = 400

    /** Gallons aboard for a reported fuel level (0–100 %). */
    fun fuelGal(fuelPct: Float?): Float? =
        fuelPct?.takeIf { it in 0f..100f }?.let { TANK_GAL * it / 100f }

    /**
     * Instantaneous fuel flow (US gal/hr) from MAF — the standard
     * stoichiometric estimate: fuel g/s = MAF / 14.7; ÷453.6 g/lb ÷6.17 lb/gal
     * ×3600 → gal/hr. Mirrors the relation the trip-MPG integrator uses; kept
     * here as the single source of truth for the generator/idle math.
     */
    fun fuelFlowGph(mafGs: Float?): Float? =
        mafGs?.takeIf { it >= 0f }?.let { it / 14.7f / 453.6f / 6.17f * 3600f }

    /**
     * Drivable range (mi) = usable gallons × trip-avg MPG. [reserve] holds back
     * [RESERVE_FRAC] of the tank — the honest "before you're walking" figure.
     * Null unless both a fuel level and a real MPG (>0.5) are known.
     */
    fun driveRangeMi(fuelPct: Float?, avgMpg: Float?, reserve: Boolean = true): Float? {
        val gal = fuelGal(fuelPct) ?: return null
        val mpg = avgMpg?.takeIf { it > 0.5f } ?: return null
        val usable = if (reserve) max(0f, gal - TANK_GAL * RESERVE_FRAC) else gal
        return usable * mpg
    }

    /**
     * How far you can go and still get back — half the usable range. This is
     * the number that actually matters before committing to a spur on a search:
     * turnaround fuel, not one-way fuel.
     */
    fun roundTripRadiusMi(fuelPct: Float?, avgMpg: Float?): Float? =
        driveRangeMi(fuelPct, avgMpg)?.let { it / 2f }

    /**
     * Hours the engine can run at the CURRENT burn rate on the fuel aboard —
     * i.e. idle endurance when parked as a generator. Meaningful only while the
     * engine is running and roughly idling; callers gate on [isIdling].
     */
    fun idleHoursRemaining(fuelPct: Float?, mafGs: Float?): Float? {
        val gal = fuelGal(fuelPct) ?: return null
        val gph = fuelFlowGph(mafGs)?.takeIf { it > 0.02f } ?: return null
        return gal / gph
    }

    /** Engine running and roughly stationary at idle — generator conditions. */
    fun isIdling(rpm: Int, speedKmh: Int, connected: Boolean): Boolean =
        connected && rpm in ENGINE_RUN_RPM until 1400 && speedKmh < 5

    /**
     * True when the drivable range no longer covers the distance to the nearest
     * refuel/base point — "you may not make it to fuel." Both in miles; false
     * (no alarm) whenever either input is unknown, so a missing MPG or an empty
     * waypoint set never cries wolf.
     */
    fun reserveShort(rangeMi: Float?, nearestFuelMi: Float?): Boolean =
        rangeMi != null && nearestFuelMi != null && rangeMi < nearestFuelMi

    /** Charge-system verdict from bus voltage vs engine state. */
    enum class Charge(val label: String) {
        OFFLINE("—"),                 // ECU asleep / no voltage reported
        ENGINE_OFF("ENGINE OFF"),     // resting start-battery voltage
        CHARGING("CHARGING"),         // running, alternator holding the bus up
        WEAK("WEAK CHARGE"),          // running, marginal — watch the belt/alt
        NOT_CHARGING("NOT CHARGING"), // running but bus at battery voltage — fault
    }

    /**
     * Alternator health from control-module (charging-bus) voltage and RPM. A
     * healthy system holds ~13.8–14.6 V while running; sitting at battery
     * voltage with the engine turning is a dead alternator or slipped belt —
     * the kind of failure that strands you on day two. Thresholds are
     * deliberately conservative to avoid crying wolf at a hot idle.
     */
    fun charge(rpm: Int, batteryV: Float?, ecuOnline: Boolean): Charge {
        if (!ecuOnline || batteryV == null) return Charge.OFFLINE
        if (rpm < ENGINE_RUN_RPM) return Charge.ENGINE_OFF
        return when {
            batteryV >= 13.2f -> Charge.CHARGING
            batteryV >= 12.6f -> Charge.WEAK
            else -> Charge.NOT_CHARGING
        }
    }
}
