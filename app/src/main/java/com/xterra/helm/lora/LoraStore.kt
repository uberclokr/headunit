package com.xterra.helm.lora

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * JSON persistence for the LoRa subsystem, kept self-contained (its own files
 * in filesDir) rather than scattered into SettingsRepository. Three records:
 * the network-server [LoraConfig], the bound [LoraNode]s (with their keys and,
 * for OTAA, the session filled in at join), and last-known [LoraNodeState] so
 * the map/list are populated on a cold boot before the next uplink.
 */
class LoraStore(context: Context) {
    private val gson = Gson()
    private val dir = context.filesDir
    private val cfgFile = File(dir, "lora_config.json")
    private val nodesFile = File(dir, "lora_nodes.json")
    private val statesFile = File(dir, "lora_states.json")

    fun loadConfig(): LoraConfig =
        read(cfgFile, LoraConfig::class.java) ?: LoraConfig()
    fun saveConfig(c: LoraConfig) = write(cfgFile, c)

    fun loadNodes(): List<LoraNode> =
        readList(nodesFile, object : TypeToken<List<LoraNode>>() {}) ?: emptyList()
    fun saveNodes(n: List<LoraNode>) = write(nodesFile, n)

    fun loadStates(): List<LoraNodeState> =
        readList(statesFile, object : TypeToken<List<LoraNodeState>>() {}) ?: emptyList()
    fun saveStates(s: List<LoraNodeState>) = write(statesFile, s)

    private fun <T> read(f: File, cls: Class<T>): T? =
        runCatching { if (f.isFile) gson.fromJson(f.readText(), cls) else null }.getOrNull()
    private fun <T> readList(f: File, t: TypeToken<T>): T? =
        runCatching { if (f.isFile) gson.fromJson<T>(f.readText(), t.type) else null }.getOrNull()
    private fun write(f: File, v: Any) { runCatching { f.writeText(gson.toJson(v)) } }
}
