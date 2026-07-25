package com.xterra.helm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xterra.helm.HelmApp
import com.xterra.helm.nav.SolarCalc
import com.xterra.helm.ui.theme.HelmColors
import com.xterra.helm.ui.theme.HelmThemeState
import com.xterra.helm.ui.theme.ThemeMode
import com.xterra.helm.widgets.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shell layout:
 *  ┌──────────────────────────────────────────────┐
 *  │ status strip: time · link states · temps · V │
 *  ├───────────────┬─╢drag╟────────────────────────┤
 *  │   left pane   │        right pane            │
 *  ├───────────────┴──────────────────────────────┤
 *  │ dock: widget shortcuts + split controls      │
 *  └──────────────────────────────────────────────┘
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun DashboardShell(panes: PaneManager) {
    // Restore the last pane layout, then persist every change (debounced so
    // a split-handle drag doesn't hammer DataStore). awaitLoaded() avoids
    // racing the defaults that state.value holds before the disk read lands.
    LaunchedEffect(Unit) {
        val s = HelmApp.instance.settings.awaitLoaded()
        panes.left = Widget.entries.find { it.name == s.paneLeft } ?: Widget.MAP
        panes.right = Widget.entries.find { it.name == s.paneRight }
        panes.split = s.paneSplit.coerceIn(0.28f, 0.72f)
        snapshotFlow { Triple(panes.left, panes.right, panes.split) }
            .drop(1)   // skip the restore emission itself
            .debounce(700)
            .collect { (l, r, sp) ->
                HelmApp.instance.settings.setPaneLayout(l.name, r?.name ?: "", sp)
            }
    }
    // AUTO theme: day/night from solar elevation at the GPS position. Falls
    // back to local clock hours when there's no fix (e.g. parked in a garage).
    LaunchedEffect(Unit) {
        while (true) {
            val fix = HelmApp.instance.gps.state.value
            HelmThemeState.autoIsDay = if (fix.hasFix)
                SolarCalc.isDaylight(fix.lat, fix.lon, System.currentTimeMillis())
            else java.util.Calendar.getInstance()
                .get(java.util.Calendar.HOUR_OF_DAY) in 7..18
            delay(60_000)
        }
    }
    Column(Modifier.fillMaxSize().background(HelmColors.Glass)) {
        StatusStrip()
        WxAlertBanner()
        Row(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            val hasRight = panes.right != null
            Pane(
                widget = panes.left,
                onPick = { panes.pickerFor = 0 },
                modifier = Modifier
                    .weight(if (hasRight) panes.split else 1f)
                    .fillMaxHeight(),
            )
            if (hasRight) {
                SplitHandle(panes)
                Pane(
                    widget = panes.right!!,
                    onPick = { panes.pickerFor = 1 },
                    onClose = { panes.closeRight() },
                    modifier = Modifier.weight(1f - panes.split).fillMaxHeight(),
                )
            }
        }
        Dock(panes)
    }
    panes.pickerFor?.let { slot ->
        WidgetPicker(onSelect = { panes.assign(slot, it) }, onDismiss = { panes.pickerFor = null })
    }
}

/**
 * NOAA/SAME alert strip across the whole shell — an EAS header (or the
 * 1050 Hz warning tone) decoded by the SDR's WX mode shows here regardless
 * of which panes are docked. Sticks until dismissed.
 */
@Composable
private fun WxAlertBanner() {
    val sdr by HelmApp.instance.sdr.state.collectAsState()
    val alert = sdr.wxAlert ?: return
    Row(
        Modifier.fillMaxWidth().background(HelmColors.Alert.copy(alpha = 0.22f))
            .border(1.dp, HelmColors.Alert)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("⚠ WX ALERT", style = MaterialTheme.typography.titleMedium,
            color = HelmColors.Alert)
        Text(alert, style = MaterialTheme.typography.bodyMedium,
            color = HelmColors.Text, modifier = Modifier.weight(1f))
        Text("DISMISS", style = MaterialTheme.typography.labelSmall,
            color = HelmColors.Alert,
            modifier = Modifier.clickable { HelmApp.instance.sdr.clearWxAlert() })
    }
}

@Composable
private fun StatusStrip() {
    val can by HelmApp.instance.can.state.collectAsState()
    val batt by HelmApp.instance.battery.state.collectAsState()
    val home by HelmApp.instance.homeLink.state.collectAsState()
    val cam by HelmApp.instance.viofo.state.collectAsState()
    val gps by HelmApp.instance.gps.state.collectAsState()
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            clock = SimpleDateFormat("HH:mm", Locale.US).format(Date()); delay(5_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(clock, style = MaterialTheme.typography.headlineMedium, color = HelmColors.Text)
        StatusDot("CAN", can.connected)
        StatusDot("PWR", batt.connected)
        StatusDot("HOME", home.reachable)
        StatusDot("CAM", cam.reachable)
        StatusDot("GPS", gps.hasFix)
        if (gps.hasFix) StatTiny(
            if (gps.fixDim == 3) "3D" else if (gps.fixDim == 2) "2D" else "FIX",
            "${gps.sats}·${"%.1f".format(gps.hdop)}")
        home.latencyMs?.let { StatTiny("WG", "${it}ms") }
        // Two batteries on this truck — always name which one a number
        // belongs to. HOUSE = Renogy LiFePO4 pack, STR = starter via ECM.
        StatTiny("HOUSE", "${batt.socPct}%·" + "%.1fV".format(batt.volts))
        if (can.connected) can.batteryV?.let { StatTiny("STR", "%.1fV".format(it)) }
        can.coolantC?.let { StatTiny("ECT", "$it°") }
        Spacer(Modifier.weight(1f))
        if (can.reverse) Text("REVERSE", color = HelmColors.Alert,
            style = MaterialTheme.typography.titleMedium)
        StatTiny("SPD", "${(can.speedKmh * 0.6214).toInt()} mph")
    }
}

@Composable
fun StatusDot(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Online = filled green; offline = hollow red ring. Distinguished by
        // both fill and hue so it's readable at a glance / in daylight.
        val dot = Modifier.size(9.dp).clip(RoundedCornerShape(50))
        Box(
            if (ok) dot.background(HelmColors.Ok)
            else dot.border(1.5.dp, HelmColors.Alert, RoundedCornerShape(50))
        )
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (ok) HelmColors.TextDim else HelmColors.Alert)
    }
}

@Composable
fun StatTiny(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = HelmColors.Cyan)
    }
}

@Composable
private fun Pane(
    widget: Widget,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    Column(
        modifier
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(HelmColors.Panel)
            .border(1.dp, HelmColors.PanelEdge, RoundedCornerShape(14.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(widget.label, style = MaterialTheme.typography.titleMedium,
                color = HelmColors.Amber, modifier = Modifier.clickable { onPick() })
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Apps, null, tint = HelmColors.TextDim,
                modifier = Modifier.size(20.dp).clickable { onPick() })
            if (onClose != null) {
                Spacer(Modifier.width(14.dp))
                Icon(Icons.Filled.Close, null, tint = HelmColors.TextDim,
                    modifier = Modifier.size(20.dp).clickable { onClose() })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { WidgetContent(widget) }
    }
}

@Composable
private fun SplitHandle(panes: PaneManager) {
    val screenPx = LocalConfiguration.current.screenWidthDp
    Box(
        Modifier
            .width(14.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    panes.split = (panes.split + drag.x / (screenPx * density))
                        .coerceIn(0.28f, 0.72f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(3.dp).height(52.dp)
            .clip(RoundedCornerShape(2.dp)).background(HelmColors.PanelEdge))
    }
}

@Composable
private fun Dock(panes: PaneManager) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Off the dock quick-bar (still reachable via the pane picker):
        //  - DRONE (CAM_DRONE): platform still WIP.
        //  - TILT (INCLINE): the level bubble now lives on the vehicle pane's
        //    dash row and its gauges are a tap away via the VEHICLE trend slot.
        listOf(Widget.MAP, Widget.MEDIA, Widget.GAUGES, Widget.CAM_REAR,
               Widget.SDR, Widget.NET).forEach { w ->
            DockChip(w.label, active = panes.left == w || panes.right == w) {
                if (panes.right != null) panes.right = w else panes.left = w
            }
        }
        // Volume controls, centered in the dock's empty middle — a distinct
        // grouped module, set apart from the pane chips and split controls.
        Spacer(Modifier.weight(1f))
        VolumeGroup()
        Spacer(Modifier.weight(1f))
        DockChip(
            when (HelmThemeState.mode) {
                ThemeMode.AUTO -> if (HelmThemeState.isDay) "☀ AUTO" else "☾ AUTO"
                ThemeMode.DAY -> "☀ DAY"
                ThemeMode.NIGHT -> "☾ NIGHT"
            },
            active = HelmThemeState.mode != ThemeMode.AUTO,
        ) { HelmThemeState.cycle() }
        DockChip("⇄", false) { panes.swap() }
        DockChip(if (panes.right == null) "SPLIT" else "SINGLE", panes.right != null) {
            if (panes.right == null) panes.right = Widget.MEDIA else panes.closeRight()
        }
    }
}

/**
 * One distinct pill in the dock's centre: a media-volume stepper (−/level/+
 * on STREAM_MUSIC) adjoining the transport controls (prev / play-pause / next)
 * for whatever's playing. The volume readout polls so it tracks hardware keys;
 * the transport binds to the active MediaSession and dims when nothing plays.
 */
@Composable
private fun VolumeGroup() {
    val ctx = LocalContext.current
    val am = remember {
        ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    val music = android.media.AudioManager.STREAM_MUSIC
    var vol by remember { mutableStateOf(volPct(am, music)) }
    LaunchedEffect(Unit) { while (true) { vol = volPct(am, music); delay(1500) } }
    fun bump(up: Boolean) {
        am.adjustStreamVolume(music,
            if (up) android.media.AudioManager.ADJUST_RAISE
            else android.media.AudioManager.ADJUST_LOWER, 0)
        vol = volPct(am, music)
    }
    val repo = HelmApp.instance.media
    val np by repo.active.collectAsState()
    val hasSession = np.packageName.isNotEmpty()

    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(HelmColors.Glass.copy(alpha = 0.65f))
            .border(1.dp, HelmColors.PanelEdge, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("VOL", style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
        VolBtn(Icons.Filled.Remove, enabled = true) { bump(false) }
        Text("%d%%".format(vol), style = MaterialTheme.typography.labelSmall,
            color = HelmColors.Cyan, modifier = Modifier.widthIn(min = 34.dp))
        VolBtn(Icons.Filled.Add, enabled = true) { bump(true) }

        Box(Modifier.width(1.dp).height(26.dp).background(HelmColors.PanelEdge))

        VolBtn(Icons.Filled.SkipPrevious, enabled = hasSession) { repo.prev(np.packageName) }
        VolBtn(if (np.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            enabled = hasSession) { repo.playPause(np.packageName) }
        VolBtn(Icons.Filled.SkipNext, enabled = hasSession) { repo.next(np.packageName) }
    }
}

private fun volPct(am: android.media.AudioManager, stream: Int): Int {
    val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
    return am.getStreamVolume(stream) * 100 / max
}

@Composable
private fun VolBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
            .background(HelmColors.Panel)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null,
            tint = if (enabled) HelmColors.Amber else HelmColors.TextDim.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DockChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) HelmColors.AmberDim.copy(alpha = 0.35f) else HelmColors.Panel)
            .border(1.dp, if (active) HelmColors.Amber else HelmColors.PanelEdge, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            color = if (active) HelmColors.Amber else HelmColors.TextDim)
    }
}

@Composable
private fun WidgetPicker(onSelect: (Widget) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(HelmColors.Panel)
                .border(1.dp, HelmColors.PanelEdge, RoundedCornerShape(16.dp)).padding(18.dp),
        ) {
            Text("DOCK WIDGET", style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Widget.entries) { w ->
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(HelmColors.Glass)
                        .border(1.dp, HelmColors.PanelEdge, RoundedCornerShape(10.dp))
                        .clickable { onSelect(w) }.padding(16.dp),
                        contentAlignment = Alignment.Center) {
                        Text(w.label, color = HelmColors.Text,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
