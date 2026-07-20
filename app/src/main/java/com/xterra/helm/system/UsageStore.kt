package com.xterra.helm.system

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.usageDataStore by preferencesDataStore("helm_usage")

/**
 * Persisted accumulator STATE — survives reboot so usage isn't lost. The plan
 * config (cap, reset day) lives in [SettingsRepository] instead, so it shows
 * up in the settings pane alongside everything else.
 */
data class UsagePersist(
    val totalBytes: Long = 0L,
    val lastCurrent: Long = -1L,
    val cycleStartEpochDay: Long = 0L,
)

/** DataStore for the Starlink usage accumulator's running state. */
class UsageStore(context: Context) {
    private val ds = context.usageDataStore

    suspend fun load(): UsagePersist {
        val p = ds.data.first()
        return UsagePersist(
            totalBytes = p[TOTAL] ?: 0L,
            lastCurrent = p[LAST_CUR] ?: -1L,
            cycleStartEpochDay = p[CYCLE_START] ?: 0L,
        )
    }

    suspend fun save(state: UsagePersist) {
        ds.edit {
            it[TOTAL] = state.totalBytes
            it[LAST_CUR] = state.lastCurrent
            it[CYCLE_START] = state.cycleStartEpochDay
        }
    }

    companion object {
        private val TOTAL = longPreferencesKey("usage_total_bytes")
        private val LAST_CUR = longPreferencesKey("usage_last_current")
        private val CYCLE_START = longPreferencesKey("usage_cycle_start_epochday")
    }
}
