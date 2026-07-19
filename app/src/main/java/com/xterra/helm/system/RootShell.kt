package com.xterra.helm.system

import android.util.Log

/**
 * Thin root-shell helper. This image's su is AOSP-style (`su 0 <cmd...>`,
 * no -c). Used for the things an app uid cannot do: GPIO export, neighbor
 * table reads, and the ip rule/route pinning that keeps camera traffic off
 * the VPN. Failures log once per distinct command so a missing/denied su
 * is loud in `logcat -s Helm` instead of silently breaking features.
 */
object RootShell {
    private val warned = mutableSetOf<String>()

    fun run(cmd: String, timeoutMs: Long = 15_000): String? = runCatching {
        val p = ProcessBuilder("su", "0", "sh", "-c", cmd)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val done = p.waitFor()
        if (done == 0) out else {
            warnOnce(cmd, "exit=$done ${out.take(120)}")
            null
        }
    }.getOrElse { e -> warnOnce(cmd, e.message ?: "exec failed"); null }

    private fun warnOnce(cmd: String, why: String) {
        val key = cmd.take(32)
        if (warned.add(key)) Log.w("Helm", "root cmd failed [$key…]: $why")
    }
}
