package com.xterra.helm.lora

/** How a node joins the network. */
enum class Activation { OTAA, ABP }

/**
 * A bound LoRaWAN node. OTAA nodes carry joinEui/appKey and get their session
 * keys (devAddr/nwkSKey/appSKey) filled in at join time; ABP nodes carry the
 * session keys directly. All keys are uppercase hex strings, MSB-first as the
 * SenseCAP app shows them. Runtime telemetry lives in [LoraNodeState].
 */
data class LoraNode(
    val devEui: String,             // 16 hex (8 bytes) — the stable identity
    val label: String,
    val activation: Activation,
    val joinEui: String = "",       // 16 hex (OTAA)
    val appKey: String = "",        // 32 hex (OTAA root key)
    val devAddr: String = "",       // 8 hex (4 bytes) — ABP, or assigned at join
    val nwkSKey: String = "",       // 32 hex — ABP, or derived at join
    val appSKey: String = "",       // 32 hex — ABP, or derived at join
)

data class NodePosition(val lat: Double, val lon: Double, val altM: Double? = null)

/**
 * Latest decoded telemetry for a node. Persisted (last-known) so the map and
 * list are populated on cold boot before the next uplink arrives.
 */
data class LoraNodeState(
    val devEui: String,
    val label: String,
    val activation: Activation,
    val devAddr: String = "",
    val joined: Boolean = false,        // OTAA: a session exists
    val pos: NodePosition? = null,
    val batteryPct: Int? = null,
    val tempC: Float? = null,
    val motionCount: Int? = null,
    val rssi: Int? = null,              // gateway RSSI of the last uplink
    val snr: Float? = null,
    val fcnt: Long? = null,
    val fixTimeMs: Long? = null,        // node's own UTC fix time
    val lastSeenMs: Long = 0,           // when we last heard any uplink
    val uplinks: Long = 0,
)

/** One decoded SenseCAP position/telemetry sample. */
data class SenseCapSample(
    val pos: NodePosition?,
    val batteryPct: Int?,
    val tempC: Float?,
    val motionCount: Int?,
    val fixTimeMs: Long?,
)

/** Network-server config (band + gateway). Bound nodes persist separately. */
data class LoraConfig(
    val enabled: Boolean = false,
    val udpPort: Int = 1700,            // Semtech UDP forwarder port
    val region: String = "US915",       // affects RX2/join downlink defaults
    val netId: Int = 0x000000,          // private NetID; DevAddr prefix on join
)
