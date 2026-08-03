package com.xterra.helm.system

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** User-editable configuration, persisted via DataStore. */
data class HelmSettings(
    // Unraid backup target (SCP/SFTP over SSH).
    val scpHost: String = "192.168.1.36",
    val scpPort: Int = 22,
    val scpUser: String = "",
    val scpPass: String = "",
    val scpPath: String = "/mnt/user/helm",
    // Nav zoom presets — tap to jump, long-press to overwrite with the
    // current zoom. Defaults: overview / cruising / trail.
    val navZooms: List<Double> = listOf(12.0, 14.0, 16.0),
    // Last pane layout (Widget enum names; "" = no right pane).
    val paneLeft: String = "MAP",
    val paneRight: String = "MEDIA",
    val paneSplit: Float = 0.55f,
    // Mirror the reverse-overlay image (true rear-view-mirror sense). The
    // Viofo records the rear un-mirrored; flip to taste on first drive.
    val revMirror: Boolean = true,
    // Network: the head unit's own AP (camera net) + the 5 GHz BSSID of
    // lavalink to pin the STA to (same-channel concurrency keeps the AP on
    // 5 GHz only while the STA is).
    val hotspotSsid: String = "helmnet",
    val hotspotPass: String = "changeme",         // set a strong AP password in Settings
    val staBssid: String = "",                     // your 5 GHz router BSSID (Settings)
    // Renogy BT-2 module on the smart battery's UP port.
    val renogyMac: String = "",                    // your Renogy BLE MAC (nRF Connect)
    // Inclinometer ZERO: the gravity vector (device frame, "x,y,z") captured
    // parked on level ground. Empty = never calibrated.
    val tiltCal: String = "",
    // Axis sense for this mount. Defaults = inverted on both, validated
    // against real terrain on the 2026-07-18 drive; toggles live in the
    // settings pane for if the unit is ever remounted.
    val tiltInvertRoll: Boolean = true,
    val tiltInvertPitch: Boolean = true,
    // Starlink plan: monthly data cap in GB (0 = no cap shown) and the billing
    // cycle reset day-of-month. Usage itself is integrated from the dish
    // locally (NetRepository); these just supply the denominator + reset date.
    val starlinkCapGb: Float = 100f,
    val starlinkAnchorDay: Int = 24,
    // VNC guard: auto-disconnect droidVNC-NG clients older than [vncTimeoutMin]
    // minutes so a forgotten remote-screen session can't drain Starlink 24/7.
    val vncGuard: Boolean = true,
    val vncTimeoutMin: Int = 5,
)

private val Context.dataStore by preferencesDataStore("helm_settings")

/**
 * Single source of truth for user settings. Backed by DataStore so edits
 * survive reboots. Exposes [state] as a hot StateFlow the UI binds to;
 * writes go through [update]. First home for config that used to be inline
 * constants — more fields (hotspot SSID, Renogy MAC, GPIO pin…) migrate here
 * over time.
 */
class SettingsRepository(context: Context) {
    private val ds = context.dataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mapped = ds.data.map { p ->
        HelmSettings(
            scpHost = p[HOST] ?: HelmSettings().scpHost,
            scpPort = p[PORT] ?: HelmSettings().scpPort,
            scpUser = p[USER] ?: "",
            scpPass = p[PASS] ?: "",
            scpPath = p[PATH] ?: HelmSettings().scpPath,
            navZooms = ZOOMS.mapIndexed { i, k ->
                p[k] ?: HelmSettings().navZooms[i]
            },
            paneLeft = p[PANE_L] ?: HelmSettings().paneLeft,
            paneRight = p[PANE_R] ?: HelmSettings().paneRight,
            paneSplit = p[PANE_SPLIT] ?: HelmSettings().paneSplit,
            revMirror = p[REV_MIRROR] ?: HelmSettings().revMirror,
            hotspotSsid = p[HS_SSID] ?: HelmSettings().hotspotSsid,
            hotspotPass = p[HS_PASS] ?: HelmSettings().hotspotPass,
            staBssid = p[STA_BSSID] ?: HelmSettings().staBssid,
            renogyMac = p[REN_MAC] ?: HelmSettings().renogyMac,
            tiltCal = p[TILT_CAL] ?: "",
            tiltInvertRoll = p[TILT_INV_ROLL] ?: HelmSettings().tiltInvertRoll,
            tiltInvertPitch = p[TILT_INV_PITCH] ?: HelmSettings().tiltInvertPitch,
            starlinkCapGb = p[SL_CAP_GB] ?: HelmSettings().starlinkCapGb,
            starlinkAnchorDay = p[SL_ANCHOR] ?: HelmSettings().starlinkAnchorDay,
            vncGuard = p[VNC_GUARD] ?: HelmSettings().vncGuard,
            vncTimeoutMin = p[VNC_TIMEOUT] ?: HelmSettings().vncTimeoutMin,
        )
    }

    val state: StateFlow<HelmSettings> =
        mapped.stateIn(scope, SharingStarted.Eagerly, HelmSettings())

    /**
     * First value actually read from disk. [state] starts as the defaults
     * before DataStore loads — restore-on-boot paths must await this instead
     * of racing state.value.
     */
    suspend fun awaitLoaded(): HelmSettings = mapped.first()

    fun setScp(host: String, port: Int, user: String, pass: String, path: String) {
        scope.launch {
            ds.edit {
                it[HOST] = host; it[PORT] = port
                it[USER] = user; it[PASS] = pass; it[PATH] = path
            }
        }
    }

    fun setRevMirror(on: Boolean) {
        scope.launch { ds.edit { it[REV_MIRROR] = on } }
    }

    fun setNet(ssid: String, pass: String, bssid: String) {
        scope.launch {
            ds.edit { it[HS_SSID] = ssid; it[HS_PASS] = pass; it[STA_BSSID] = bssid }
        }
    }

    fun setRenogyMac(mac: String) {
        scope.launch { ds.edit { it[REN_MAC] = mac } }
    }

    fun setTiltCal(cal: String) {
        scope.launch { ds.edit { it[TILT_CAL] = cal } }
    }

    fun setTiltInvert(roll: Boolean, pitch: Boolean) {
        scope.launch { ds.edit { it[TILT_INV_ROLL] = roll; it[TILT_INV_PITCH] = pitch } }
    }

    fun setStarlinkPlan(anchorDay: Int, capGb: Float) {
        scope.launch {
            ds.edit {
                it[SL_ANCHOR] = anchorDay.coerceIn(1, 28)
                it[SL_CAP_GB] = capGb.coerceAtLeast(0f)
            }
        }
    }

    fun setVncGuard(enabled: Boolean, timeoutMin: Int) {
        scope.launch {
            ds.edit {
                it[VNC_GUARD] = enabled
                it[VNC_TIMEOUT] = timeoutMin.coerceIn(1, 120)
            }
        }
    }

    fun setPaneLayout(left: String, right: String, split: Float) {
        scope.launch {
            ds.edit { it[PANE_L] = left; it[PANE_R] = right; it[PANE_SPLIT] = split }
        }
    }

    fun setNavZoom(index: Int, zoom: Double) {
        val key = ZOOMS.getOrNull(index) ?: return
        scope.launch { ds.edit { it[key] = zoom } }
    }

    companion object {
        private val HOST = stringPreferencesKey("scp_host")
        private val PORT = intPreferencesKey("scp_port")
        private val USER = stringPreferencesKey("scp_user")
        private val PASS = stringPreferencesKey("scp_pass")
        private val PATH = stringPreferencesKey("scp_path")
        private val PANE_L = stringPreferencesKey("pane_left")
        private val PANE_R = stringPreferencesKey("pane_right")
        private val PANE_SPLIT = floatPreferencesKey("pane_split")
        private val REV_MIRROR = booleanPreferencesKey("rev_mirror")
        private val HS_SSID = stringPreferencesKey("hotspot_ssid")
        private val HS_PASS = stringPreferencesKey("hotspot_pass")
        private val STA_BSSID = stringPreferencesKey("sta_bssid")
        private val REN_MAC = stringPreferencesKey("renogy_mac")
        private val TILT_CAL = stringPreferencesKey("tilt_cal")
        private val TILT_INV_ROLL = booleanPreferencesKey("tilt_inv_roll")
        private val TILT_INV_PITCH = booleanPreferencesKey("tilt_inv_pitch")
        private val SL_CAP_GB = floatPreferencesKey("starlink_cap_gb")
        private val SL_ANCHOR = intPreferencesKey("starlink_anchor_day")
        private val VNC_GUARD = booleanPreferencesKey("vnc_guard")
        private val VNC_TIMEOUT = intPreferencesKey("vnc_timeout_min")
        private val ZOOMS = listOf(
            doublePreferencesKey("nav_zoom_1"),
            doublePreferencesKey("nav_zoom_2"),
            doublePreferencesKey("nav_zoom_3"),
        )
    }
}
