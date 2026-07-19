package com.xterra.helm.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xterra.helm.can.SocketCanManager
import com.xterra.helm.ui.theme.HelmColors

/**
 * Raw CAN frame viewer (SocketCAN backend). Frames grouped by arbitration
 * ID with per-byte change highlighting — the standard workflow for reverse
 * engineering Xterra body-bus IDs (operate a control, watch which bytes
 * flip). Shows a setup notice until the can0 JNI backend is built.
 */
@Composable
fun SnifferWidget() {
    if (!SocketCanManager.available) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                "SocketCAN backend not present.\n\n" +
                "Build libhelmsocketcan.so (see docs/SOCKETCAN.md), bring up can0 " +
                "at 500 kbit on the IO-hat transceiver, and raw frames stream here " +
                "with per-byte diff highlighting.",
                style = MaterialTheme.typography.bodyMedium, color = HelmColors.TextDim,
            )
        }
        return
    }
    val frames = remember { mutableStateListOf<String>() }
    // Reader loop wiring goes here once JNI lands.
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(frames) { f ->
            Text(f, style = MaterialTheme.typography.bodyMedium, color = HelmColors.Cyan)
        }
    }
}
