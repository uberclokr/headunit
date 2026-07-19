package com.xterra.helm.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xterra.helm.HelmApp
import com.xterra.helm.ui.theme.HelmColors

/**
 * Connectivity pane: Starlink dish health (local gRPC), the STA link to
 * lavalink, our own AP + who's on it, WG tunnel RTT, raw internet RTT.
 */
@Composable
fun NetWidget() {
    val net by HelmApp.instance.net.state.collectAsState()
    val home by HelmApp.instance.homeLink.state.collectAsState()
    val cam by HelmApp.instance.viofo.state.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("STARLINK DISH", style = MaterialTheme.typography.titleMedium,
            color = HelmColors.Amber)
        val d = net.dish
        if (d == null) {
            NetRow("STATE", "unreachable (192.168.100.1)", alert = true)
        } else {
            NetRow("UPTIME", fmtUptime(d.uptimeS))
            NetRow("POP LATENCY", d.latencyMs?.let { "%.0f ms".format(it) } ?: "—")
            NetRow("THROUGHPUT", "↓ %s   ↑ %s".format(
                d.downMbps?.let { "%.1f Mbps".format(it) } ?: "—",
                d.upMbps?.let { "%.1f Mbps".format(it) } ?: "—"))
            NetRow("OBSTRUCTION",
                d.obstructedPct?.let { "%.2f%%".format(it) } ?: "—",
                alert = d.currentlyObstructed)
            if (d.currentlyObstructed) NetRow("", "CURRENTLY OBSTRUCTED", alert = true)
            if (d.alertCount > 0) NetRow("ALERTS", "${d.alertCount} active", alert = true)
            else NetRow("ALERTS", "none")
        }

        Spacer(Modifier.height(6.dp))
        Text("WIFI", style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)
        NetRow("STA", net.staSsid?.let {
            "$it  ${net.staRssi ?: "?"} dBm  ${net.staFreqMhz?.let { f ->
                if (f > 3000) "5 GHz" else "2.4 GHz" } ?: ""}"
        } ?: "disconnected", alert = net.staSsid == null)
        NetRow("HOTSPOT", if (net.apUp)
            "up · ${net.apClients} client${if (net.apClients == 1) "" else "s"}"
            else "down", alert = !net.apUp)
        NetRow("CAMERA", if (cam.reachable) "online ${cam.ip ?: ""}" else "offline",
            alert = !cam.reachable)

        Spacer(Modifier.height(6.dp))
        Text("LINKS", style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)
        NetRow("WIREGUARD", home.latencyMs?.let { "$it ms" }
            ?: if (home.reachable) "up" else "down", alert = !home.reachable)
        NetRow("INTERNET", net.inetMs?.let { "$it ms to 1.1.1.1" } ?: "no reply",
            alert = net.inetMs == null)
    }
}

@Composable
private fun NetRow(label: String, value: String, alert: Boolean = false) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = HelmColors.TextDim, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = if (alert) HelmColors.Alert else HelmColors.Cyan)
    }
}

private fun fmtUptime(s: Long): String = when {
    s <= 0 -> "—"
    s < 3600 -> "${s / 60} min"
    s < 86_400 -> "%dh %02dm".format(s / 3600, (s % 3600) / 60)
    else -> "%dd %dh".format(s / 86_400, (s % 86_400) / 3600)
}
