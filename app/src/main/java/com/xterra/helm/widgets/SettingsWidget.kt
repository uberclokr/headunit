package com.xterra.helm.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xterra.helm.HelmApp
import com.xterra.helm.nav.PoiSync
import com.xterra.helm.system.HotspotManager
import com.xterra.helm.ui.theme.HelmColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings panel. Currently: Unraid backup target for waypoints + a manual
 * sync trigger. Extensible — this is where inline config constants migrate.
 */
@Composable
fun SettingsWidget() {
    val s by HelmApp.instance.settings.state.collectAsState()
    val scope = rememberCoroutineScope()

    var host by remember(s.scpHost) { mutableStateOf(s.scpHost) }
    var port by remember(s.scpPort) { mutableStateOf(s.scpPort.toString()) }
    var user by remember(s.scpUser) { mutableStateOf(s.scpUser) }
    var pass by remember(s.scpPass) { mutableStateOf(s.scpPass) }
    var path by remember(s.scpPath) { mutableStateOf(s.scpPath) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("UNRAID BACKUP  (SCP / SFTP)",
            style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)

        Field("Server", host, KeyboardType.Uri) { host = it }
        Field("Port", port, KeyboardType.Number) { port = it }
        Field("User", user, KeyboardType.Text) { user = it }
        Field("Password", pass, KeyboardType.Password, isPassword = true) { pass = it }
        Field("Root path", path, KeyboardType.Uri) { path = it }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("SAVE", primary = true) {
                HelmApp.instance.settings.setScp(
                    host.trim(), port.toIntOrNull() ?: 22, user.trim(), pass, path.trim())
                status = "saved"
            }
            Btn(if (busy) "SYNCING…" else "SYNC WAYPOINTS NOW") {
                if (busy) return@Btn
                // Save first so the sync uses the on-screen values.
                HelmApp.instance.settings.setScp(
                    host.trim(), port.toIntOrNull() ?: 22, user.trim(), pass, path.trim())
                busy = true; status = null
                scope.launch {
                    val cur = HelmApp.instance.settings.state.value.copy(
                        scpHost = host.trim(), scpPort = port.toIntOrNull() ?: 22,
                        scpUser = user.trim(), scpPass = pass, scpPath = path.trim())
                    val r = withContext(Dispatchers.IO) {
                        PoiSync.upload(cur, HelmApp.instance.poi.geojsonFile)
                    }
                    busy = false
                    status = r.fold({ "✓ $it" }, { "✗ ${it.message}" })
                }
            }
        }
        val wp = HelmApp.instance.poi.count()
        Text("$wp waypoint${if (wp == 1) "" else "s"} in local store",
            style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = if (it.startsWith("✗")) HelmColors.Alert else HelmColors.Ok)
        }

        Spacer(Modifier.height(10.dp))
        Text("NETWORK  (hotspot + Starlink STA)",
            style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)
        var hsSsid by remember(s.hotspotSsid) { mutableStateOf(s.hotspotSsid) }
        var hsPass by remember(s.hotspotPass) { mutableStateOf(s.hotspotPass) }
        var bssid by remember(s.staBssid) { mutableStateOf(s.staBssid) }
        Field("Hotspot SSID (camera joins this)", hsSsid, KeyboardType.Text) { hsSsid = it }
        Field("Hotspot password", hsPass, KeyboardType.Password, isPassword = true) { hsPass = it }
        Field("lavalink 5 GHz BSSID (STA pin)", bssid, KeyboardType.Text) { bssid = it }
        Btn("SAVE + RESTART AP") {
            HelmApp.instance.settings.setNet(hsSsid.trim(), hsPass, bssid.trim().lowercase())
            HotspotManager.ssid = hsSsid.trim()
            HotspotManager.pass = hsPass
            HotspotManager.staBssid = bssid.trim().lowercase()
            status = "AP restarting as ${hsSsid.trim()} — reconfigure the camera to match"
            scope.launch(Dispatchers.IO) { HotspotManager.restartAp() }
        }

        Spacer(Modifier.height(10.dp))
        Text("POWER  (Renogy BT-2)",
            style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)
        var renMac by remember(s.renogyMac) { mutableStateOf(s.renogyMac) }
        Field("BT-2 BLE MAC", renMac, KeyboardType.Text) { renMac = it }
        Btn("SAVE") {
            val m = renMac.trim().uppercase()
            HelmApp.instance.settings.setRenogyMac(m)
            HelmApp.instance.battery.mac = m
            status = "Renogy MAC saved — applies on next BLE reconnect"
        }

        Spacer(Modifier.height(10.dp))
        Text("INCLINOMETER",
            style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)
        // Axis sense for the dash mount; peaks reset on flip so latched
        // values don't carry a stale sign.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (s.tiltInvertRoll) "ROLL AXIS: INVERTED" else "ROLL AXIS: NORMAL") {
                HelmApp.instance.settings.setTiltInvert(!s.tiltInvertRoll, s.tiltInvertPitch)
                HelmApp.instance.tilt.resetPeaks()
            }
            Btn(if (s.tiltInvertPitch) "PITCH AXIS: INVERTED" else "PITCH AXIS: NORMAL") {
                HelmApp.instance.settings.setTiltInvert(s.tiltInvertRoll, !s.tiltInvertPitch)
                HelmApp.instance.tilt.resetPeaks()
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("STARLINK DATA  (plan)",
            style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)
        // Usage is measured locally off the dish; these two set the cap
        // denominator and when the billing cycle resets (dish can't report
        // them). 0 GB = show usage with no cap.
        var slCap by remember(s.starlinkCapGb) {
            mutableStateOf(if (s.starlinkCapGb > 0f) "%.0f".format(s.starlinkCapGb) else "0")
        }
        var slDay by remember(s.starlinkAnchorDay) { mutableStateOf(s.starlinkAnchorDay.toString()) }
        Field("Monthly cap (GB, 0 = no cap)", slCap, KeyboardType.Number) { slCap = it }
        Field("Cycle reset day (1–28)", slDay, KeyboardType.Number) { slDay = it }
        Btn("SAVE", primary = true) {
            val cap = slCap.trim().toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
            val day = slDay.trim().toIntOrNull()?.coerceIn(1, 28) ?: 24
            HelmApp.instance.settings.setStarlinkPlan(day, cap)
            slCap = "%.0f".format(cap); slDay = day.toString()
            status = "Starlink plan saved — ${cap.toInt()} GB, resets day $day"
        }

        Spacer(Modifier.height(10.dp))
        Text("REVERSE CAMERA",
            style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)
        Btn(if (s.revMirror) "OVERLAY MIRRORED (rear-view sense) — tap to unmirror"
            else "OVERLAY UNMIRRORED — tap to mirror") {
            HelmApp.instance.settings.setRevMirror(!s.revMirror)
        }

        Spacer(Modifier.height(10.dp))
        OfflineCacheSection()

        Spacer(Modifier.height(10.dp))
        VncSection()

        Spacer(Modifier.height(10.dp))
        CompanionSection()
    }
}

/**
 * Remote-access (VNC) manager. droidVNC-NG is a separate app that mirrors the
 * screen; left connected it streamed the animated dashboard over Starlink 24/7
 * (~59 GB once). This surfaces its live clients + a hard session cap (Helm's
 * root watchdog kills any VNC connection older than the cap) plus manual
 * disconnect / stop / start.
 */
@Composable
private fun VncSection() {
    val vnc by HelmApp.instance.vnc.state.collectAsState()
    val s by HelmApp.instance.settings.state.collectAsState()
    val settings = HelmApp.instance.settings

    Text("REMOTE ACCESS  (VNC)", style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)
    Text(
        if (vnc.serverUp) "server ON · ${vnc.conns.size} client${if (vnc.conns.size == 1) "" else "s"} connected"
        else "server off",
        style = MaterialTheme.typography.bodyMedium,
        color = if (vnc.serverUp) HelmColors.Ok else HelmColors.TextDim)
    vnc.conns.forEach { c ->
        Text("• ${c.peer} · ${c.ageSec / 60}m ${c.ageSec % 60}s",
            style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
    }

    // Auto-disconnect cap — a forgotten viewer can't stream past this.
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn(if (s.vncGuard) "AUTO-CUT ON" else "AUTO-CUT OFF", primary = s.vncGuard) {
            settings.setVncGuard(!s.vncGuard, s.vncTimeoutMin)
        }
        Btn("−") { settings.setVncGuard(s.vncGuard, s.vncTimeoutMin - 1) }
        Text("${s.vncTimeoutMin} min cap",
            style = MaterialTheme.typography.bodyMedium, color = HelmColors.Cyan)
        Btn("+") { settings.setVncGuard(s.vncGuard, s.vncTimeoutMin + 1) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn("STOP NOW", primary = true) { HelmApp.instance.vnc.stopServer() }
        Btn("START") { HelmApp.instance.vnc.startServer() }
    }
    Text("STOP cuts the viewer and leaves VNC off until START — no per-client kill on this ROM.",
        style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
    vnc.lastAction?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = HelmColors.Cyan)
    }
}

/**
 * Offline storage manager for the nav subsystem — the single place to manage
 * both cache classes. Map tiles: the regions captured by the nav pane's
 * CACHE THIS VIEW (per-region delete, total) plus a clear for the automatic
 * "everywhere you've viewed" ambient cache. Navigation: the offline routing
 * graph (size, loaded state, delete). Capture stays on the map (it needs a
 * framed viewport); everything else lives here.
 */
@Composable
private fun OfflineCacheSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var regions by remember { mutableStateOf<List<com.xterra.helm.nav.OfflineRegions.Info>?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { com.xterra.helm.nav.OfflineRegions.list(ctx) { regions = it } }

    val nav by HelmApp.instance.nav.state.collectAsState()
    var graphMb by remember { mutableStateOf<Double?>(null) }
    var reloadGraph by remember { mutableStateOf(0) }
    LaunchedEffect(reloadGraph) {
        graphMb = withContext(Dispatchers.IO) { HelmApp.instance.nav.graphSizeBytes() / 1_048_576.0 }
    }
    var ambientMsg by remember { mutableStateOf<String?>(null) }

    Text("OFFLINE MAPS & NAV",
        style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)

    // ── Map tiles ────────────────────────────────────────────────────────
    Text("MAP TILES", style = MaterialTheme.typography.labelMedium, color = HelmColors.Cyan)
    when {
        regions == null -> Text("loading…",
            style = MaterialTheme.typography.bodyMedium, color = HelmColors.TextDim)
        regions!!.isEmpty() -> Text("no regions cached — frame an area on the nav map and tap CACHE THIS VIEW",
            style = MaterialTheme.typography.bodyMedium, color = HelmColors.TextDim)
        else -> {
            regions!!.forEach { info ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(info.name, style = MaterialTheme.typography.bodyMedium, color = HelmColors.Text)
                        Text("${info.tiles} tiles · %.0f MB".format(info.mb) +
                                if (info.complete) "" else " · partial",
                            style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
                    }
                    Btn("DELETE") { com.xterra.helm.nav.OfflineRegions.delete(info) { reload++ } }
                }
            }
            Text("tiles total %.0f MB".format(regions!!.sumOf { it.mb }),
                style = MaterialTheme.typography.labelSmall, color = HelmColors.Cyan)
        }
    }
    // Ambient (auto) cache — everywhere already viewed; clearable to reclaim space.
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(ambientMsg ?: "viewed-area cache (auto, up to 1 GB)",
            style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim,
            modifier = Modifier.weight(1f))
        Btn("CLEAR") {
            ambientMsg = "clearing…"
            org.maplibre.android.offline.OfflineManager.getInstance(ctx).clearAmbientCache(
                object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
                    override fun onSuccess() { ambientMsg = "viewed-area cache cleared" }
                    override fun onError(message: String) { ambientMsg = "clear failed: $message" }
                })
        }
    }

    // ── Routing graph ─────────────────────────────────────────────────────
    Text("NAVIGATION", style = MaterialTheme.typography.labelMedium, color = HelmColors.Cyan)
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            val mb = graphMb
            Text("Offline routing graph",
                style = MaterialTheme.typography.bodyMedium, color = HelmColors.Text)
            Text(
                when {
                    mb == null -> "measuring…"
                    mb < 1.0 -> "not installed"
                    else -> "%.0f MB · %s".format(
                        mb, if (nav.engineReady) "loaded" else "present, not loaded")
                },
                style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
        }
        if ((graphMb ?: 0.0) >= 1.0) {
            Btn("DELETE") { HelmApp.instance.nav.deleteGraph { reloadGraph++ } }
        }
    }
}

/**
 * Companion-app pairing: a QR + link to the APK served off this head unit's
 * own API. The download URL points at the head unit's WG address, and the
 * app ships pre-configured to talk back to that same address — install on a
 * phone already on the VPN and it just connects.
 */
@Composable
private fun CompanionSection() {
    val wg = remember { com.xterra.helm.system.WgAddress.get() }
    val url = "http://${wg ?: "10.255.1.6"}:${com.xterra.helm.system.ApiServer.PORT}/companion.apk"
    val qr = remember(url) { com.xterra.helm.system.QrCode.bitmap(url, 420) }

    Text("COMPANION APP", style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)
    Text("scan on a phone that's on the vehicle VPN — installs pre-configured for "
        + "$wg", style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        qr?.let {
            androidx.compose.foundation.Image(
                it.asImageBitmap(), "companion download QR",
                Modifier.size(150.dp).clip(RoundedCornerShape(8.dp)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(url, style = MaterialTheme.typography.bodyMedium, color = HelmColors.Cyan)
            Text("API  http://${wg ?: "10.255.1.6"}:${com.xterra.helm.system.ApiServer.PORT}/api/status",
                style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
            Text("CAM  rtsp://${wg ?: "10.255.1.6"}:${com.xterra.helm.system.RtspRelay.PORT}/live",
                style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
        }
    }
}

@Composable
private fun Field(
    label: String, value: String, kb: KeyboardType,
    isPassword: Boolean = false, onChange: (String) -> Unit,
) {
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label, color = HelmColors.TextDim) },
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = HelmColors.Glass,
            unfocusedContainerColor = HelmColors.Glass,
            focusedTextColor = HelmColors.Text, unfocusedTextColor = HelmColors.Text,
            cursorColor = HelmColors.Amber,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Btn(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (primary) HelmColors.AmberDim.copy(alpha = 0.4f) else HelmColors.Panel)
            .border(1.dp, HelmColors.Amber, RoundedCornerShape(9.dp))
            .clickable { onClick() }.padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = HelmColors.Amber)
    }
}
