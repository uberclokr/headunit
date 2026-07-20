package com.xterra.helm.lora

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The vehicle's LoRaWAN network server. Owns the [SemtechForwarder] (uplinks
 * from the roof wAP LR8G), the bound-node registry, and the decode pipeline:
 * uplink → LoRaWAN MIC/decrypt → SenseCAP decode → node position. Exposes
 * StateFlows the LoRa pane and the nav map bind to. Everything is local — no
 * cloud LNS — matching the vehicle's quiet profile.
 *
 * Two activation paths: ABP nodes decode immediately (deterministic, the
 * primary tested path); OTAA nodes join first (crypto is unit-tested, but the
 * JoinAccept RX-window timing needs on-hardware validation with the gateway).
 */
class LoraRepository(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = LoraStore(context)

    private val _config = MutableStateFlow(LoraConfig())
    val config: StateFlow<LoraConfig> = _config

    private val _nodes = MutableStateFlow<List<LoraNode>>(emptyList())
    val nodes: StateFlow<List<LoraNode>> = _nodes

    private val _states = MutableStateFlow<List<LoraNodeState>>(emptyList())
    val states: StateFlow<List<LoraNodeState>> = _states

    private val _gatewayEui = MutableStateFlow<String?>(null)
    val gatewayEui: StateFlow<String?> = _gatewayEui

    private val joinNonce = AtomicInteger(1)
    private val forwarder = SemtechForwarder(
        onUplink = { rx -> scope.launch { onUplink(rx) } },
        onGateway = { eui -> _gatewayEui.value = eui },
    )

    fun start() {
        _config.value = store.loadConfig()
        _nodes.value = store.loadNodes()
        _states.value = store.loadStates()
        if (_config.value.enabled) forwarder.start(_config.value.udpPort)
    }

    // ── configuration (edited in the LoRa pane) ──────────────────────────────

    fun setConfig(c: LoraConfig) {
        val was = _config.value
        _config.value = c
        store.saveConfig(c)
        if (c.enabled && (!was.enabled || was.udpPort != c.udpPort)) forwarder.start(c.udpPort)
        else if (!c.enabled && was.enabled) forwarder.stop()
    }

    fun bindNode(node: LoraNode) {
        // Replace any existing binding for the same DevEUI.
        _nodes.value = _nodes.value.filterNot { it.devEui.equals(node.devEui, true) } + node
        store.saveNodes(_nodes.value)
        // Seed a state row so it appears in the list before its first uplink.
        if (_states.value.none { it.devEui.equals(node.devEui, true) }) {
            _states.value = _states.value + LoraNodeState(
                devEui = node.devEui.uppercase(), label = node.label,
                activation = node.activation, devAddr = node.devAddr.uppercase(),
                joined = node.activation == Activation.ABP)
            store.saveStates(_states.value)
        }
    }

    fun removeNode(devEui: String) {
        _nodes.value = _nodes.value.filterNot { it.devEui.equals(devEui, true) }
        _states.value = _states.value.filterNot { it.devEui.equals(devEui, true) }
        store.saveNodes(_nodes.value); store.saveStates(_states.value)
    }

    // ── uplink pipeline ──────────────────────────────────────────────────────

    private fun onUplink(rx: SemtechForwarder.RxPacket) {
        val u = LoRaWan.parse(rx.phy) ?: return
        when (u.mType) {
            LoRaWan.JOIN_REQUEST -> handleJoin(u, rx)
            LoRaWan.UNCONF_UP, LoRaWan.CONF_UP -> handleData(u, rx)
        }
    }

    private fun handleData(u: LoRaWan.Uplink, rx: SemtechForwarder.RxPacket) {
        val node = _nodes.value.firstOrNull {
            it.devAddr.isNotEmpty() && it.devAddr.equals(u.devAddr, true)
        } ?: return
        val nwk = LoRaWan.unhex(node.nwkSKey)
        if (!LoRaWan.verifyDataMic(u, nwk)) {
            Log.w(TAG, "LoRa: bad MIC from ${node.label} (${u.devAddr}) — dropped")
            return
        }
        val plain = LoRaWan.decryptUplink(u, LoRaWan.unhex(node.appSKey))
        val s = SenseCapDecoder.decode(plain)
        updateState(node.devEui, u.fcnt.toLong(), rx, s)
    }

    private fun handleJoin(u: LoRaWan.Uplink, rx: SemtechForwarder.RxPacket) {
        val node = _nodes.value.firstOrNull {
            it.activation == Activation.OTAA && it.devEui.equals(u.devEui, true)
        } ?: return
        val appKey = LoRaWan.unhex(node.appKey)
        if (!LoRaWan.verifyJoinMic(u, appKey)) {
            Log.w(TAG, "LoRa: bad join MIC from ${node.label} — dropped"); return
        }
        // Deterministic DevAddr from the last 4 bytes of DevEUI (unique, stable).
        val devAddr = LoRaWan.unhex(node.devEui).copyOfRange(4, 8)
        val nonce = joinNonce.getAndIncrement()
        val ja = LoRaWan.buildJoinAccept(
            appKey, joinNonce = nonce, netId = _config.value.netId, devAddr = devAddr,
            devNonce = u.devNonce, dlSettings = 0, rxDelay = 1)
        // Persist the session so we don't depend on the device rejoining.
        val joined = node.copy(devAddr = ja.devAddr, nwkSKey = LoRaWan.hex(ja.nwkSKey),
            appSKey = LoRaWan.hex(ja.appSKey))
        _nodes.value = _nodes.value.map { if (it.devEui == node.devEui) joined else it }
        store.saveNodes(_nodes.value)
        markJoined(node.devEui, ja.devAddr)
        // Downlink on RX2 (deterministic per region) — NEEDS HARDWARE TIMING
        // VALIDATION; the crypto above is unit-tested, the window below is not.
        val (freq, datr) = rx2(_config.value.region)
        forwarder.sendDownlink(ja.phy, rx.tmst + JOIN_ACCEPT_DELAY2_US, freq, datr)
        Log.i(TAG, "LoRa: JoinAccept -> ${node.label} devAddr=${ja.devAddr} (RX2 $freq/$datr)")
    }

    private fun updateState(devEui: String, fcnt: Long, rx: SemtechForwarder.RxPacket, s: SenseCapSample?) {
        val now = System.currentTimeMillis()
        _states.value = _states.value.map {
            if (!it.devEui.equals(devEui, true)) it
            else it.copy(
                pos = s?.pos ?: it.pos,
                batteryPct = s?.batteryPct ?: it.batteryPct,
                tempC = s?.tempC ?: it.tempC,
                motionCount = s?.motionCount ?: it.motionCount,
                fixTimeMs = s?.fixTimeMs ?: it.fixTimeMs,
                rssi = rx.rssi, snr = rx.snr, fcnt = fcnt,
                lastSeenMs = now, uplinks = it.uplinks + 1, joined = true,
            )
        }
        store.saveStates(_states.value)
    }

    private fun markJoined(devEui: String, devAddr: String) {
        _states.value = _states.value.map {
            if (it.devEui.equals(devEui, true)) it.copy(joined = true, devAddr = devAddr) else it
        }
        store.saveStates(_states.value)
    }

    private fun rx2(region: String): Pair<Double, String> = when {
        region.startsWith("EU") -> 869.525 to "SF12BW125"
        else -> 923.3 to "SF12BW500"        // US915 default
    }

    companion object {
        private const val TAG = "Helm"
        private const val JOIN_ACCEPT_DELAY2_US = 6_000_000L
    }
}
