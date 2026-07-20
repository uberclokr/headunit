package com.xterra.helm.system

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.usageDataStore by preferencesDataStore("helm_usage")

/** Persisted accumulator state — survives reboot so usage isn't lost. */
data class UsagePersist(
    val totalBytes: Long = 0L,
    val lastCurrent: Long = -1L,
    val cycleStartEpochDay: Long = 0L,
)

/**
 * User/plan config. The dish can't supply these, so until the cloud account
 * source lands they're set manually: [anchorDay] = billing-cycle reset day,
 * [capGb] = the plan's data cap (null = show usage with no denominator).
 */
data class UsageConfig(
    val anchorDay: Int = 1,
    val capGb: Float? = null,
)

/** DataStore for the Starlink usage accumulator and its manual cap/anchor. */
class UsageStore(context: Context) {
    private val ds = context.usageDataStore

    suspend fun load(): Pair<UsagePersist, UsageConfig> {
        val p = ds.data.first()
        return UsagePersist(
            totalBytes = p[TOTAL] ?: 0L,
            lastCurrent = p[LAST_CUR] ?: -1L,
            cycleStartEpochDay = p[CYCLE_START] ?: 0L,
        ) to UsageConfig(
            anchorDay = p[ANCHOR] ?: 1,
            capGb = p[CAP_GB],
        )
    }

    suspend fun save(state: UsagePersist) {
        ds.edit {
            it[TOTAL] = state.totalBytes
            it[LAST_CUR] = state.lastCurrent
            it[CYCLE_START] = state.cycleStartEpochDay
        }
    }

    suspend fun setConfig(anchorDay: Int, capGb: Float?) {
        ds.edit {
            it[ANCHOR] = anchorDay.coerceIn(1, 28)
            if (capGb != null && capGb > 0f) it[CAP_GB] = capGb else it.remove(CAP_GB)
        }
    }

    companion object {
        private val TOTAL = longPreferencesKey("usage_total_bytes")
        private val LAST_CUR = longPreferencesKey("usage_last_current")
        private val CYCLE_START = longPreferencesKey("usage_cycle_start_epochday")
        private val ANCHOR = intPreferencesKey("usage_anchor_day")
        private val CAP_GB = floatPreferencesKey("usage_cap_gb")
    }
}
