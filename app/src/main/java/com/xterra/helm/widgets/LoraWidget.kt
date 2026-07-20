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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xterra.helm.HelmApp
import com.xterra.helm.lora.Activation
import com.xterra.helm.lora.LoraConfig
import com.xterra.helm.lora.LoraNode
import com.xterra.helm.lora.LoraNodeState
import com.xterra.helm.ui.theme.HelmColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LoRaWAN pane: configure the local network server (the roof wAP LR8G forwards
 * here), bind SenseCAP cards, and see the associated nodes. Tapping a node row
 * expands its detail. Positions also render on the nav pane when enabled.
 */
@Composable
fun LoraWidget() {
    val repo = HelmApp.instance.lora
    val cfg by repo.config.collectAsState()
    val states by repo.states.collectAsState()
    val gwEui by repo.gatewayEui.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── network server config ──
        Text("LORAWAN NETWORK SERVER", style = MaterialTheme.typography.titleMedium,
            color = HelmColors.Amber)
        var port by remember(cfg.udpPort) { mutableStateOf(cfg.udpPort.toString()) }
        var region by remember(cfg.region) { mutableStateOf(cfg.region) }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (cfg.enabled) "ENABLED" else "DISABLED", on = cfg.enabled) {
                repo.setConfig(cfg.copy(enabled = !cfg.enabled))
                status = if (!cfg.enabled) "server on :$port" else "server off"
            }
            Text(if (cfg.enabled) "listening on UDP $port" else "not listening",
                style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
        }
        Field("Semtech UDP port", port, KeyboardType.Number) { port = it }
        Field("Region (US915 / EU868)", region, KeyboardType.Text) { region = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GATEWAY  ", style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
            Text(gwEui ?: "— none seen yet —", style = MaterialTheme.typography.bodyMedium,
                color = if (gwEui != null) HelmColors.Cyan else HelmColors.TextDim)
        }
        Btn("SAVE SERVER", primary = true) {
            val p = port.trim().toIntOrNull() ?: 1700
            repo.setConfig(cfg.copy(udpPort = p, region = region.trim().uppercase()))
            status = "server config saved"
        }

        Spacer(Modifier.height(8.dp))
        BindSection { node -> repo.bindNode(node); status = "bound ${node.label}" }

        Spacer(Modifier.height(8.dp))
        Text("NODES  (${states.size})", style = MaterialTheme.typography.titleMedium,
            color = HelmColors.Amber)
        if (states.isEmpty())
            Text("none bound yet", style = MaterialTheme.typography.bodyMedium, color = HelmColors.TextDim)
        states.sortedBy { it.label }.forEach { NodeRow(it) { repo.removeNode(it.devEui) } }

        status?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = HelmColors.Ok)
        }
    }
}

@Composable
private fun BindSection(onBind: (LoraNode) -> Unit) {
    Text("BIND NODE / CARD", style = MaterialTheme.typography.titleMedium, color = HelmColors.Amber)
    var label by remember { mutableStateOf("") }
    var devEui by remember { mutableStateOf("") }
    var otaa by remember { mutableStateOf(true) }
    var joinEui by remember { mutableStateOf("") }
    var appKey by remember { mutableStateOf("") }
    var devAddr by remember { mutableStateOf("") }
    var nwkSKey by remember { mutableStateOf("") }
    var appSKey by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    Field("Label", label, KeyboardType.Text) { label = it }
    Field("DevEUI (16 hex)", devEui, KeyboardType.Text) { devEui = it }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn("OTAA", on = otaa) { otaa = true }
        Btn("ABP", on = !otaa) { otaa = false }
    }
    if (otaa) {
        Field("JoinEUI / AppEUI (16 hex)", joinEui, KeyboardType.Text) { joinEui = it }
        Field("AppKey (32 hex)", appKey, KeyboardType.Text) { appKey = it }
    } else {
        Field("DevAddr (8 hex)", devAddr, KeyboardType.Text) { devAddr = it }
        Field("NwkSKey (32 hex)", nwkSKey, KeyboardType.Text) { nwkSKey = it }
        Field("AppSKey (32 hex)", appSKey, KeyboardType.Text) { appSKey = it }
    }
    Btn("BIND", primary = true) {
        val hexOk = { s: String, n: Int -> s.trim().replace(" ", "").length == n &&
            s.trim().replace(" ", "").all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }
        err = when {
            label.isBlank() -> "label required"
            !hexOk(devEui, 16) -> "DevEUI must be 16 hex"
            otaa && !hexOk(joinEui, 16) -> "JoinEUI must be 16 hex"
            otaa && !hexOk(appKey, 32) -> "AppKey must be 32 hex"
            !otaa && !hexOk(devAddr, 8) -> "DevAddr must be 8 hex"
            !otaa && !hexOk(nwkSKey, 32) -> "NwkSKey must be 32 hex"
            !otaa && !hexOk(appSKey, 32) -> "AppSKey must be 32 hex"
            else -> null
        }
        if (err == null) {
            val clean = { s: String -> s.trim().replace(" ", "").uppercase() }
            onBind(if (otaa) LoraNode(clean(devEui), label.trim(), Activation.OTAA,
                joinEui = clean(joinEui), appKey = clean(appKey))
            else LoraNode(clean(devEui), label.trim(), Activation.ABP,
                devAddr = clean(devAddr), nwkSKey = clean(nwkSKey), appSKey = clean(appSKey)))
            label = ""; devEui = ""; joinEui = ""; appKey = ""; devAddr = ""; nwkSKey = ""; appSKey = ""
        }
    }
    err?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = HelmColors.Alert) }
}

@Composable
private fun NodeRow(s: LoraNodeState, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(HelmColors.Panel).border(1.dp, HelmColors.PanelEdge, RoundedCornerShape(10.dp))
            .clickable { open = !open }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(
                if (s.lastSeenMs > 0 && System.currentTimeMillis() - s.lastSeenMs < 3_600_000)
                    HelmColors.Ok else HelmColors.TextDim))
            Spacer(Modifier.width(8.dp))
            Text(s.label, style = MaterialTheme.typography.titleMedium, color = HelmColors.Text)
            Spacer(Modifier.weight(1f))
            Text(s.pos?.let { "fix" } ?: if (s.joined) "no fix" else "unjoined",
                style = MaterialTheme.typography.labelSmall,
                color = if (s.pos != null) HelmColors.Cyan else HelmColors.TextDim)
            s.batteryPct?.let {
                Spacer(Modifier.width(10.dp))
                Text("$it%", style = MaterialTheme.typography.bodyMedium,
                    color = if (it < 15) HelmColors.Alert else HelmColors.TextDim)
            }
        }
        if (open) {
            Detail("DevEUI", s.devEui)
            if (s.devAddr.isNotEmpty()) Detail("DevAddr", s.devAddr)
            Detail("Activation", s.activation.name + if (s.joined) " · joined" else "")
            s.pos?.let { Detail("Position", "%.6f, %.6f".format(it.lat, it.lon)) }
            s.tempC?.let { Detail("Temp", "%.1f °C".format(it)) }
            s.motionCount?.let { Detail("Motion", "$it") }
            if (s.rssi != null) Detail("Radio", "RSSI ${s.rssi} dBm · SNR ${"%.1f".format(s.snr ?: 0f)}")
            s.fcnt?.let { Detail("FCnt", "$it · ${s.uplinks} uplinks") }
            if (s.lastSeenMs > 0) Detail("Last seen", fmt(s.lastSeenMs))
            s.fixTimeMs?.let { Detail("Fix time", fmt(it)) }
            Spacer(Modifier.height(4.dp))
            Btn("UNBIND") { onRemove() }
        }
    }
}

@Composable
private fun Detail(k: String, v: String) = Row(Modifier.fillMaxWidth()) {
    Text(k, style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim,
        modifier = Modifier.width(96.dp))
    Text(v, style = MaterialTheme.typography.bodyMedium, color = HelmColors.Cyan)
}

private fun fmt(ms: Long) = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date(ms))

@Composable
private fun Field(label: String, value: String, kb: KeyboardType, onChange: (String) -> Unit) {
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label, color = HelmColors.TextDim) },
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = HelmColors.Glass, unfocusedContainerColor = HelmColors.Glass,
            focusedTextColor = HelmColors.Text, unfocusedTextColor = HelmColors.Text,
            cursorColor = HelmColors.Amber),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Btn(text: String, primary: Boolean = false, on: Boolean = false, onClick: () -> Unit) {
    val border = if (on) HelmColors.Ok else HelmColors.Amber
    Box(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(when {
                on -> HelmColors.Ok.copy(alpha = 0.25f)
                primary -> HelmColors.AmberDim.copy(alpha = 0.4f)
                else -> HelmColors.Panel
            })
            .border(1.dp, border, RoundedCornerShape(9.dp))
            .clickable { onClick() }.padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            color = if (on) HelmColors.Ok else HelmColors.Amber)
    }
}
