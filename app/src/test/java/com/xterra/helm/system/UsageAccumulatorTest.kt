package com.xterra.helm.system

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageAccumulatorTest {

    // 8 Mb down + 0 up for one sample = 1_000_000 bytes.
    private val eightMbit = 8_000_000f

    @Test fun firstPollSetsBaselineAndCountsNothing() {
        val a = UsageAccumulator()
        val down = FloatArray(4) { eightMbit }
        assertEquals(0L, a.add(current = 100, down, FloatArray(4)))
        assertEquals(0L, a.totalBytes)
    }

    @Test fun countsOnlyNewSamplesSinceLastPoll() {
        val a = UsageAccumulator()
        val ring = 8
        val down = FloatArray(ring) { eightMbit }   // every slot 1 MB/s
        val up = FloatArray(ring)
        a.add(current = 100, down, up)               // baseline
        // 3 new samples → 3 MB.
        assertEquals(3_000_000L, a.add(current = 103, down, up))
        assertEquals(3_000_000L, a.totalBytes)
    }

    @Test fun readsTheRingByCurrentModuloWhenWrapped() {
        val a = UsageAccumulator()
        val ring = 4
        // Distinct per-slot values so mis-indexing would give a wrong sum.
        val down = floatArrayOf(8_000_000f, 16_000_000f, 24_000_000f, 32_000_000f)
        val up = FloatArray(ring)
        a.add(current = 10, down, up)                // baseline; newest is idx (10-1)%4 = 1
        // current 10→12: two new samples at idx (11%4)=3 and (10%4)=2 → 32Mb + 24Mb.
        val added = a.add(current = 12, down, up)
        assertEquals((32_000_000L + 24_000_000L) / 8, added)   // 7_000_000 bytes
    }

    @Test fun capsRecoveryAtRingSizeOnLongGap() {
        val a = UsageAccumulator()
        val ring = 4
        val down = FloatArray(ring) { eightMbit }
        val up = FloatArray(ring)
        a.add(current = 10, down, up)
        // Missed 100 samples but the ring only holds 4 → count 4, not 100.
        assertEquals(4_000_000L, a.add(current = 110, down, up))
    }

    @Test fun handlesDishRebootWhenCurrentResets() {
        val a = UsageAccumulator()
        val ring = 8
        val down = FloatArray(ring) { eightMbit }
        val up = FloatArray(ring)
        a.add(current = 5000, down, up)
        // Dish rebooted: current dropped to 3 → count min(3, ring) = 3 MB.
        assertEquals(3_000_000L, a.add(current = 3, down, up))
    }

    @Test fun restoreThenContinue() {
        val a = UsageAccumulator()
        a.restore(totalBytes = 50_000_000L, lastCurrent = 200)
        val down = FloatArray(8) { eightMbit }
        assertEquals(2_000_000L, a.add(current = 202, down, FloatArray(8)))
        assertEquals(52_000_000L, a.totalBytes)
    }

    @Test fun resetCycleZeroesTotalButKeepsTracking() {
        val a = UsageAccumulator()
        val down = FloatArray(8) { eightMbit }
        a.add(current = 100, down, FloatArray(8))
        a.add(current = 105, down, FloatArray(8))
        a.resetCycle()
        assertEquals(0L, a.totalBytes)
        // Still tracks from current=105, so 2 new samples add 2 MB (no re-count).
        assertEquals(2_000_000L, a.add(current = 107, down, FloatArray(8)))
    }
}
