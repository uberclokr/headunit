package com.xterra.helm.cameras

import android.content.Context
import android.util.Log
import com.xterra.helm.HelmApp
import com.xterra.helm.system.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Recording browser for the Viofo A329S. The camera's WIFIAPP API lists all
 * clips via `cmd=3015` (XML: NAME/FPATH/SIZE/TIME) and serves each file over
 * plain HTTP at the FPATH mapped to a URL (B:\DCIM\... → http://ip/DCIM/...).
 * Verified against fw on 2026-07-18.
 *
 * Clips from one moment across the three lenses share a
 * `YYYY_MMDD_HHMMSS` filename prefix and a channel suffix (F/I/R = front/
 * interior/rear), so we fold them into one [ClipGroup] per timestamp. Parking
 * clips live in a subfolder and are excluded by default — there are thousands.
 *
 * Downloads stream to the head unit's external files dir (/clips) so they
 * survive and can later ride the Unraid SCP path for cloud backup.
 */
class ViofoClips(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class View(val suffix: Char, val label: String) {
        FRONT('F', "FWD"), INTERIOR('I', "CABIN"), REAR('R', "REAR");
        companion object { fun of(c: Char) = entries.firstOrNull { it.suffix == c } }
    }

    /** One physical clip file on the camera. */
    data class ClipFile(
        val name: String,
        val view: View,
        val sizeBytes: Long,
        val url: String,          // http download URL on the camera
        val parking: Boolean,
    )

    /** All lenses for one moment, keyed by the shared timestamp prefix. */
    data class ClipGroup(
        val prefix: String,       // 2026_0718_153711
        val time: String,         // "2026/07/18 15:39:56" (from the front file)
        val parking: Boolean,
        val files: Map<View, ClipFile>,
    ) {
        val totalBytes get() = files.values.sumOf { it.sizeBytes }
    }

    data class DownloadState(val name: String, val pct: Int, val error: String? = null)

    private val _groups = MutableStateFlow<List<ClipGroup>>(emptyList())
    val groups: StateFlow<List<ClipGroup>> = _groups
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    /** Names already saved locally. */
    private val _saved = MutableStateFlow<Set<String>>(emptySet())
    val saved: StateFlow<Set<String>> = _saved
    /** Active download (one at a time), null when idle. */
    private val _download = MutableStateFlow<DownloadState?>(null)
    val download: StateFlow<DownloadState?> = _download

    val clipsDir: File get() =
        File(context.getExternalFilesDir(null), "clips").apply { mkdirs() }

    init { rescanSaved() }

    /** Pull and group the camera's clip index. */
    fun refresh() = scope.launch {
        val ip = HelmApp.instance.viofo.state.value.ip
        if (ip == null) { _error.value = "camera offline"; return@launch }
        _loading.value = true; _error.value = null
        runCatching {
            // The app's own Java sockets can't pull large transfers from the
            // camera — with the VPN up they bind to tun0 and receive 0 bytes
            // (verified: ANR stuck in socketRead0, wlan1 rx=0), even though
            // LibVLC's native RTSP and tiny command GETs get through. The root
            // shell is exempt from the VPN and reliably serves the ~1.4 MB
            // index in ~12 s; wget -T caps it (RootShell's own timeout is
            // advisory). Output comes back on stdout.
            val xml = RootShell.run(
                "busybox wget -qO- -T 25 \"http://$ip/?custom=1&cmd=3015\"", 30_000)
                ?: error("root fetch returned nothing")
            Log.i("Helm", "clip index: ${xml.length} bytes")
            val groups = parse(xml, ip)     // inside runCatching: a parse throw
            _groups.value = groups          // surfaces as an error, never a hang
            Log.i("Helm", "clip index: ${groups.size} groups")
        }.onFailure {
            Log.w("Helm", "clip index failed", it)
            _error.value = "list failed: ${it.message}"
        }
        _loading.value = false
        if (_groups.value.isEmpty() && _error.value == null) _error.value = "no clips found"
    }

    // Per-field patterns use [^<]* (no cross-tag backtracking) and run within
    // one <File> block each — linear, vs the earlier single spanning regex
    // that backtracked across the whole 1.4 MB doc (6009 records → minutes).
    private val nameF = Regex("<NAME>([^<]*)</NAME>")
    private val pathF = Regex("<FPATH>([^<]*)</FPATH>")
    private val sizeF = Regex("<SIZE>([^<]*)</SIZE>")
    private val timeF = Regex("<TIME>([^<]*)</TIME>")
    private val nameRe = Regex("""(\d{4}_\d{4}_\d{6})_\d+([FIR])\.MP4""")

    private data class Raw(val file: ClipFile, val prefix: String, val time: String)

    private fun parse(xml: String, ip: String): List<ClipGroup> {
        val raws = xml.split("<File>").asSequence().drop(1).mapNotNull { block ->
            val name = nameF.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val nm = nameRe.find(name) ?: return@mapNotNull null
            val view = View.of(nm.groupValues[2][0]) ?: return@mapNotNull null
            val fpath = pathF.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val size = sizeF.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val time = timeF.find(block)?.groupValues?.get(1) ?: ""
            // B:\DCIM\Movie\Parking\X.MP4 → http://ip/DCIM/Movie/Parking/X.MP4
            val rel = fpath.removePrefix("B:").replace('\\', '/')
            Raw(ClipFile(name, view, size, "http://$ip$rel",
                parking = fpath.contains("Parking", ignoreCase = true)),
                nm.groupValues[1], time)
        }.toList()

        return raws.groupBy { it.prefix }.map { (prefix, rs) ->
            ClipGroup(prefix, rs.first().time, rs.any { it.file.parking },
                rs.associate { it.file.view to it.file })
        }.sortedByDescending { it.prefix }   // prefix sorts chronologically
    }

    /**
     * Save one clip to /clips via a background root wget (app sockets can't
     * pull large camera transfers — see [refresh]). Progress comes from
     * polling the growing file; done when it reaches the known size or wget
     * exits. Sequential — one at a time.
     */
    fun save(file: ClipFile) = scope.launch {
        if (_download.value != null) return@launch
        val dest = File(clipsDir, file.name)
        val part = "${dest.absolutePath}.part"
        _download.value = DownloadState(file.name, 0)
        // Detached wget writes .part; a marker file signals completion so we
        // can tell "finished" from "stalled" without racing the file size.
        val doneMark = "$part.done"
        RootShell.run(
            "rm -f \"$part\" \"$doneMark\"; " +
            "(busybox wget -qO \"$part\" -T 60 \"${file.url}\" && " +
            "mv \"$part\" \"${dest.absolutePath}\" && touch \"$doneMark\") " +
            ">/dev/null 2>&1 &")
        val total = file.sizeBytes.coerceAtLeast(1)
        var lastSize = -1L; var stalls = 0
        while (true) {
            delay(1000)
            val finished = RootShell.run("[ -f \"$doneMark\" ] && echo 1")?.contains("1") == true
            if (finished) break
            val cur = RootShell.run("wc -c < \"$part\" 2>/dev/null")?.trim()?.toLongOrNull() ?: 0L
            _download.value = DownloadState(file.name, (cur * 100 / total).toInt().coerceIn(0, 99))
            if (cur == lastSize) { if (++stalls >= 45) {   // ~45 s no progress
                RootShell.run("rm -f \"$part\"")
                _download.value = DownloadState(file.name, 0, error = "stalled")
                _download.value = null; return@launch
            } } else stalls = 0
            lastSize = cur
        }
        RootShell.run("chmod 0644 \"${dest.absolutePath}\"; rm -f \"$doneMark\"")
        Log.i("Helm", "clip saved: ${dest.name}")
        rescanSaved()
        _download.value = null
    }

    fun deleteLocal(name: String) {
        File(clipsDir, name).delete(); rescanSaved()
    }

    private fun rescanSaved() {
        _saved.value = clipsDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
    }
}
