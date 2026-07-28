package com.xterra.helm.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleEnergyTest {

    @Test fun fuelGal_scalesTank() {
        assertEquals(VehicleEnergy.TANK_GAL, VehicleEnergy.fuelGal(100f)!!, 1e-3f)
        assertEquals(VehicleEnergy.TANK_GAL / 2f, VehicleEnergy.fuelGal(50f)!!, 1e-3f)
        assertEquals(0f, VehicleEnergy.fuelGal(0f)!!, 1e-3f)
    }

    @Test fun fuelGal_nullOnMissingOrBogus() {
        assertNull(VehicleEnergy.fuelGal(null))
        assertNull(VehicleEnergy.fuelGal(-1f))
        assertNull(VehicleEnergy.fuelGal(120f))
    }

    @Test fun fuelFlow_matchesStoichFormula() {
        // 5 g/s MAF → 5/14.7/453.6/6.17*3600 ≈ 0.437 gal/hr
        assertEquals(0.4373f, VehicleEnergy.fuelFlowGph(5f)!!, 1e-3f)
        assertEquals(0f, VehicleEnergy.fuelFlowGph(0f)!!, 1e-6f)
        assertNull(VehicleEnergy.fuelFlowGph(null))
    }

    @Test fun driveRange_appliesReserve() {
        // Full tank, 18 mpg, 10% reserve → 21.1*0.9*18 ≈ 341.8 mi
        val r = VehicleEnergy.driveRangeMi(100f, 18f)!!
        assertEquals(341.8f, r, 0.5f)
        // No-reserve variant is the full tank
        assertEquals(21.1f * 18f, VehicleEnergy.driveRangeMi(100f, 18f, reserve = false)!!, 0.5f)
    }

    @Test fun driveRange_nullWithoutMpgOrFuel() {
        assertNull(VehicleEnergy.driveRangeMi(null, 18f))
        assertNull(VehicleEnergy.driveRangeMi(50f, null))
        assertNull(VehicleEnergy.driveRangeMi(50f, 0f))   // no trip MPG yet
    }

    @Test fun roundTrip_isHalfRange() {
        val range = VehicleEnergy.driveRangeMi(60f, 20f)!!
        assertEquals(range / 2f, VehicleEnergy.roundTripRadiusMi(60f, 20f)!!, 1e-3f)
    }

    @Test fun idleHours_fuelOverBurn() {
        // Half tank (10.55 gal) at 5 g/s (~0.437 gph) → ~24 h
        val h = VehicleEnergy.idleHoursRemaining(50f, 5f)!!
        assertEquals(24.1f, h, 0.5f)
        assertNull(VehicleEnergy.idleHoursRemaining(50f, 0f))   // engine off / no flow
    }

    @Test fun isIdling_needsRunningAndStationary() {
        assertTrue(VehicleEnergy.isIdling(750, 0, connected = true))
        assertTrue(!VehicleEnergy.isIdling(750, 20, connected = true))   // moving
        assertTrue(!VehicleEnergy.isIdling(0, 0, connected = true))      // engine off
        assertTrue(!VehicleEnergy.isIdling(750, 0, connected = false))   // no ECU
    }

    @Test fun charge_verdicts() {
        assertEquals(VehicleEnergy.Charge.OFFLINE, VehicleEnergy.charge(0, null, false))
        assertEquals(VehicleEnergy.Charge.ENGINE_OFF, VehicleEnergy.charge(0, 12.4f, true))
        assertEquals(VehicleEnergy.Charge.CHARGING, VehicleEnergy.charge(750, 14.2f, true))
        assertEquals(VehicleEnergy.Charge.WEAK, VehicleEnergy.charge(750, 12.9f, true))
        assertEquals(VehicleEnergy.Charge.NOT_CHARGING, VehicleEnergy.charge(2000, 12.2f, true))
    }
}
