package com.xterra.helm.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xterra.helm.HelmApp
import com.xterra.helm.cameras.RtspView
import com.xterra.helm.cameras.ThermalView
import com.xterra.helm.cameras.ViofoLocator
import com.xterra.helm.ui.theme.HelmColors

/**
 * The Viofo pane. One RTSP stream carries whichever channel is selected
 * (cmd 3028 — the A329S serves a single live source), so the chips switch
 * content, not URLs. Reverse forces REAR while the overlay is up, then the
 * pane's choice is restored. MIR mirrors the pane image (rear-view-mirror
 * sense) — the overlay has its own persisted mirror setting.
 */
@Composable
fun CamWidget() {
    val viofo = HelmApp.instance.viofo
    val link by viofo.state.collectAsState()
    val ch by viofo.paneChannel.collectAsState()
    var mirror by remember { mutableStateOf(false) }
    var showClips by remember { mutableStateOf(false) }
    var playUrl by remember { mutableStateOf<String?>(null) }
    // Thermal is nested here as just another camera view (its own USB/UVC core),
    // not a Viofo channel — so it's a mode toggle, not a channel switch.
    var thermal by remember { mutableStateOf(false) }
    val cam = HelmApp.instance.cameras.byId("rear")

    Box(Modifier.fillMaxSize()) {
        when {
            // Clip playback takes over the frame; back returns to live.
            playUrl != null -> {
                RtspView(playUrl!!, lowLatency = false)
                CamChip("✕ LIVE", active = false,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
                    playUrl = null
                }
            }
            // Thermal core: separate UVC source, occupies the frame like any cam.
            thermal -> ThermalView()
            // While the clip browser is open, DON'T run the live stream — it
            // shares the one WiFi hop with the camera, and a saturated link
            // stalls the 1.4 MB clip-index download.
            showClips -> Box(Modifier.fillMaxSize().background(HelmColors.Glass))
            link.reachable && cam != null -> RtspView(cam.url, cam.lowLatency, mirror = mirror)
            else -> Text(
                "camera offline — waiting for helmnet association",
                style = MaterialTheme.typography.bodyMedium, color = HelmColors.TextDim,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (playUrl == null) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Viofo channels — selecting one leaves thermal mode.
                listOf(
                    "REAR" to ViofoLocator.CH_REAR,
                    "FRONT" to ViofoLocator.CH_FRONT,
                    "CABIN" to ViofoLocator.CH_INTERIOR,
                ).forEach { (label, c) ->
                    CamChip(label, active = !thermal && ch == c) {
                        thermal = false; viofo.selectPaneChannel(c)
                    }
                }
                CamChip("THERMAL", active = thermal) { thermal = true }
                CamChip("MIR", active = mirror) { mirror = !mirror }
                CamChip("▦ CLIPS", active = false) {
                    showClips = true; HelmApp.instance.clips.refresh()
                }
            }
        }
        if (showClips) ClipBrowser(
            onPlay = { url -> playUrl = url; showClips = false },
            onDismiss = { showClips = false })
    }
}

@Composable
private fun CamChip(
    text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (active) HelmColors.AmberDim.copy(alpha = 0.85f)
                        else HelmColors.Panel.copy(alpha = 0.7f))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            color = if (active) HelmColors.Amber else HelmColors.Text)
    }
}

/**
 * Recent-recordings browser. One row per moment (front/interior/rear folded
 * by shared timestamp): tap a view to play it over the wire, ⤓ to save it to
 * the head unit for cloud backup. Parking clips are hidden behind a toggle —
 * there are thousands.
 */
@Composable
private fun ClipBrowser(onPlay: (String) -> Unit, onDismiss: () -> Unit) {
    val clips = HelmApp.instance.clips
    val groups by clips.groups.collectAsState()
    val loading by clips.loading.collectAsState()
    val error by clips.error.collectAsState()
    val saved by clips.saved.collectAsState()
    val dl by clips.download.collectAsState()
    var showParking by remember { mutableStateOf(false) }

    val shown = remember(groups, showParking) {
        groups.filter { showParking || !it.parking }.take(if (showParking) 40 else 12)
    }

    Column(
        Modifier.fillMaxSize().background(HelmColors.Glass.copy(alpha = 0.97f)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("RECORDINGS", style = MaterialTheme.typography.titleMedium,
                color = HelmColors.Amber)
            Spacer(Modifier.weight(1f))
            CamChip("PARKING", active = showParking) { showParking = !showParking }
            CamChip("↻", active = false) { clips.refresh() }
            CamChip("✕", active = false) { onDismiss() }
        }
        dl?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                if (it.error != null) "download failed: ${it.error}"
                else "saving ${it.name} … ${it.pct}%",
                style = MaterialTheme.typography.labelSmall,
                color = if (it.error != null) HelmColors.Alert else HelmColors.Cyan)
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading && groups.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("loading clip index…", color = HelmColors.TextDim)
            }
            error != null && groups.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(error!!, color = HelmColors.TextDim)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(shown, key = { it.prefix }) { g -> ClipRow(g, saved, onPlay, clips::save) }
            }
        }
    }
}

@Composable
private fun ClipRow(
    g: com.xterra.helm.cameras.ViofoClips.ClipGroup,
    saved: Set<String>,
    onPlay: (String) -> Unit,
    onSave: (com.xterra.helm.cameras.ViofoClips.ClipFile) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(HelmColors.Panel.copy(alpha = 0.6f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.time.removePrefix("2026/"),   // year is implicit; keep it tight
                style = MaterialTheme.typography.bodyMedium, color = HelmColors.Text)
            Spacer(Modifier.weight(1f))
            Text("%.1f GB".format(g.totalBytes / 1_073_741_824.0),
                style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.xterra.helm.cameras.ViofoClips.View.entries.forEach { v ->
                val f = g.files[v] ?: return@forEach
                val isSaved = f.name in saved
                // Tap label = play; ⤓ = save (✓ once local).
                CamChip(v.label, active = false) { onPlay(f.url) }
                CamChip(if (isSaved) "✓" else "⤓", active = isSaved) {
                    if (!isSaved) onSave(f)
                }
            }
        }
    }
}
