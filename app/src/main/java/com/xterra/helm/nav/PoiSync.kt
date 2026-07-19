package com.xterra.helm.nav

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.xterra.helm.system.HelmSettings
import java.io.File

/**
 * Backs the waypoint GeoJSON up to the Unraid server over SSH (SFTP, the
 * SCP-family file transfer). Creates the remote root path if needed and
 * writes pois.geojson there. Blocking — call from Dispatchers.IO.
 */
object PoiSync {

    fun upload(s: HelmSettings, local: File): Result<String> = runCatching {
        require(s.scpUser.isNotBlank()) { "no SCP user set" }
        require(local.exists()) { "no waypoints file yet" }

        val session = JSch().getSession(s.scpUser, s.scpHost, s.scpPort).apply {
            setPassword(s.scpPass)
            // Head-unit → home Unraid over WG; no interactive host-key prompt.
            setConfig("StrictHostKeyChecking", "no")
            connect(10_000)
        }
        try {
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(10_000)
            try {
                mkdirs(sftp, s.scpPath)
                sftp.put(local.absolutePath, "${s.scpPath.trimEnd('/')}/pois.geojson")
            } finally {
                sftp.disconnect()
            }
        } finally {
            session.disconnect()
        }
        "backed up to ${s.scpHost}:${s.scpPath}/pois.geojson"
    }

    /** mkdir -p for the SFTP root path. */
    private fun mkdirs(sftp: ChannelSftp, path: String) {
        var cur = ""
        for (part in path.trim('/').split('/')) {
            cur += "/$part"
            runCatching { sftp.stat(cur) }.onFailure {
                runCatching { sftp.mkdir(cur) }
            }
        }
    }
}
