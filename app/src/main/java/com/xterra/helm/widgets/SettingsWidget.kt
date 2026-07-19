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
        Text("REVERSE CAMERA",
            style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)
        Btn(if (s.revMirror) "OVERLAY MIRRORED (rear-view sense) — tap to unmirror"
            else "OVERLAY UNMIRRORED — tap to mirror") {
            HelmApp.instance.settings.setRevMirror(!s.revMirror)
        }

        Spacer(Modifier.height(10.dp))
        CompanionSection()
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
