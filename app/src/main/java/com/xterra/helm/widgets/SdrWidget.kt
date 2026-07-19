package com.xterra.helm.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xterra.helm.HelmApp
import com.xterra.helm.sdr.SdrMode
import com.xterra.helm.ui.theme.HelmColors

/**
 * SDR pane. Mode selector picks the sub-operation (only FM for now); FM shows
 * a live spectrum/waterfall with coarse + fine tuning, an auto-tune that peaks
 * the carrier (fixes crystal-drift offset), band scan, and an explicit
 * start/stop so the driver releases the dongle when unused.
 */
@Composable
fun SdrWidget() {
    val repo = HelmApp.instance.sdr
    val state by repo.state.collectAsState()
    val row by repo.spectrum.collectAsState()

    val history = remember { ArrayDeque<FloatArray>() }
    LaunchedEffect(row) {
        history.addFirst(row)
        while (history.size > 90) history.removeLast()
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        // Mode selector + power toggle
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SdrMode.entries.forEach { m ->
                Chip(m.label, active = state.mode == m) { repo.setMode(m) }
            }
            Spacer(Modifier.weight(1f))
            if (state.running) {
                Chip("■ STOP", active = false, danger = true) { repo.stop() }
            } else {
                Chip("▶ START", active = true) { repo.start() }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (!state.running) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("SDR STOPPED — press START",
                    style = MaterialTheme.typography.bodyMedium, color = HelmColors.TextDim)
            }
            return
        }

        // Frequency + signal
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%.2f".format(state.freqHz / 1e6),
                style = MaterialTheme.typography.displayLarge,
                color = if (state.connected) HelmColors.Amber else HelmColors.TextDim)
            Text(" MHz ${state.mode.label}", style = MaterialTheme.typography.bodyMedium,
                color = HelmColors.TextDim)
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    state.autoTuning -> "AUTO-TUNING…"
                    state.connected  -> "RSSI %.0f dB".format(state.rssiDb)
                    else             -> "NO SDR — check dongle / rtl_tcp"
                },
                style = MaterialTheme.typography.labelSmall, color = HelmColors.Cyan)
        }
        Spacer(Modifier.height(8.dp))

        // Spectrum + waterfall
        Canvas(Modifier.fillMaxWidth().weight(1f)
            .clip(RoundedCornerShape(10.dp)).background(HelmColors.Glass)) {
            val n = row.size
            if (n == 0) return@Canvas
            val wStep = size.width / n
            val traceH = size.height * 0.35f
            var prev = Offset(0f, traceH)
            for (i in 0 until n) {
                val v = ((row[i] + 60f) / 60f).coerceIn(0f, 1f)
                val pt = Offset(i * wStep, traceH * (1f - v))
                drawLine(HelmColors.Cyan, prev, pt, strokeWidth = 1.5f)
                prev = pt
            }
            // center-tune reference line
            drawLine(HelmColors.AmberDim,
                Offset(size.width / 2, 0f), Offset(size.width / 2, traceH),
                strokeWidth = 1f)
            val rows = history.toList()
            val rh = (size.height - traceH) / 90f
            rows.forEachIndexed { r, arr ->
                val y = traceH + r * rh
                for (i in 0 until n step 2) {
                    val v = ((arr[i] + 60f) / 60f).coerceIn(0f, 1f)
                    drawRect(Color(v, v * 0.62f, 0.18f * (1 - v), 1f),
                        topLeft = Offset(i * wStep, y),
                        size = androidx.compose.ui.geometry.Size(wStep * 2, rh + 1f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (state.mode == SdrMode.WX) {
            // NWR channel chips — WX7 (162.550) covers most of Oregon coast.
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.xterra.helm.sdr.SdrRepository.WX_CHANNELS.forEachIndexed { i, f ->
                    Chip("WX${i + 1} %.3f".format(f / 1e6), f == state.freqHz) { repo.tune(f) }
                }
            }
            Spacer(Modifier.height(8.dp))
            state.wxAlert?.let {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = HelmColors.Alert, modifier = Modifier.weight(1f))
                    Chip("CLEAR", false) { repo.clearWxAlert() }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (state.mode == SdrMode.FM && state.stations.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.stations.forEach { f ->
                    Chip("%.1f".format(f / 1e6), f == state.freqHz) { repo.tune(f) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Coarse tune + scan (FM only — WX uses fixed channels)
        if (state.mode == SdrMode.FM) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("−200k", false) { repo.step(-200) }
                Chip("−100k", false) { repo.step(-100) }
                Chip("+100k", false) { repo.step(100) }
                Chip("+200k", false) { repo.step(200) }
                Spacer(Modifier.weight(1f))
                Chip(if (state.scanning) "SCANNING…" else "SCAN", state.scanning) {
                    if (!state.scanning) repo.scanFmBand()
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Fine tune + auto-tune (drift correction)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("−50k", false) { repo.step(-50) }
            Chip("−10k", false) { repo.step(-10) }
            Chip("+10k", false) { repo.step(10) }
            Chip("+50k", false) { repo.step(50) }
            Spacer(Modifier.weight(1f))
            Chip(if (state.autoTuning) "TUNING…" else "◎ AUTO", state.autoTuning) {
                if (!state.autoTuning) repo.autoTune()
            }
        }
    }
}

@Composable
private fun Chip(text: String, active: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val fg = when { danger -> HelmColors.Alert; active -> HelmColors.Amber; else -> HelmColors.Text }
    val edge = when { danger -> HelmColors.Alert; active -> HelmColors.Amber; else -> HelmColors.PanelEdge }
    Box(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (active) HelmColors.AmberDim.copy(alpha = 0.4f) else HelmColors.Panel)
            .border(1.dp, edge, RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}
