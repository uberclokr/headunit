package com.xterra.helm.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
    val cam = HelmApp.instance.cameras.byId("rear")

    Box(Modifier.fillMaxSize()) {
        if (link.reachable && cam != null) {
            RtspView(cam.url, cam.lowLatency, mirror = mirror)
        } else {
            Text(
                "camera offline — waiting for helmnet association",
                style = MaterialTheme.typography.bodyMedium, color = HelmColors.TextDim,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "REAR" to ViofoLocator.CH_REAR,
                "FRONT" to ViofoLocator.CH_FRONT,
                "CABIN" to ViofoLocator.CH_INTERIOR,
            ).forEach { (label, c) ->
                CamChip(label, active = ch == c) { viofo.selectPaneChannel(c) }
            }
            CamChip("MIR", active = mirror) { mirror = !mirror }
        }
    }
}

@Composable
private fun CamChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (active) HelmColors.AmberDim.copy(alpha = 0.85f)
                        else HelmColors.Panel.copy(alpha = 0.7f))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            color = if (active) HelmColors.Amber else HelmColors.Text)
    }
}
