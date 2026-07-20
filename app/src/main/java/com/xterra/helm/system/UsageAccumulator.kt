package com.xterra.helm.system

/**
 * Turns the Starlink dish's throughput ring into a cumulative byte total.
 *
 * The local dish gRPC exposes NO usage/billing counter — verified against the
 * SpaceX device.proto: get_status/get_history/get_persistent_stats carry only
 * throughput and diagnostics, never a byte total or the plan cap. So "usage"
 * is derived: get_history returns a circular buffer of per-second downlink +
 * uplink throughput samples plus a monotonic `current` sample index. Each poll
 * we sum the samples that are new since the last one we saw — indexing the
 * ring by `current` so a wrapped buffer is read in the right order and nothing
 * is double-counted.
 *
 * This is an ESTIMATE of bytes through the dish (all vehicle traffic, since the
 * dish carries the whole LAN), not SpaceX's metered figure, and it cannot know
 * the plan cap — that lives only in the account (cloud). Pure + unit-tested;
 * persistence and the billing-cycle reset live in [UsageStore]/[NetRepository].
 */
class UsageAccumulator {
    var totalBytes: Long = 0L
        private set
    private var lastCurrent: Long = -1L

    /**
     * Feed one get_history snapshot; returns the bytes added this call.
     * `downBps`/`upBps` are the throughput ring; `current` is the dish's total
     * sample count. Samples are 1 second apart, so bps == bits for that second.
     */
    fun add(current: Long, downBps: FloatArray, upBps: FloatArray): Long {
        val ring = minOf(downBps.size, upBps.size)
        if (ring == 0 || current <= 0) return 0L
        val newCount = when {
            lastCurrent < 0L      -> 0L                              // first sighting: set baseline only
            current < lastCurrent -> minOf(current, ring.toLong())   // dish rebooted (current reset)
            else                  -> minOf(current - lastCurrent, ring.toLong())
        }
        lastCurrent = current
        var bits = 0.0
        for (j in 0 until newCount) {
            // Newest sample sits at (current-1) % ring; walk backwards for the rest.
            val idx = ((((current - 1 - j) % ring) + ring) % ring).toInt()
            bits += downBps[idx].toDouble() + upBps[idx].toDouble()
        }
        val added = (bits / 8.0).toLong()      // bits → bytes
        totalBytes += added
        return added
    }

    /** New billing cycle: zero the period total, keep tracking the ring. */
    fun resetCycle() { totalBytes = 0L }

    /** Rehydrate from persisted state on boot. */
    fun restore(totalBytes: Long, lastCurrent: Long) {
        this.totalBytes = totalBytes
        this.lastCurrent = lastCurrent
    }

    /** (totalBytes, lastCurrent) for persistence. */
    fun snapshot(): Pair<Long, Long> = totalBytes to lastCurrent
}
