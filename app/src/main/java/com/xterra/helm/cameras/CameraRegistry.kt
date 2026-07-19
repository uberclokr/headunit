package com.xterra.helm.cameras

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class CamKind { RTSP, USB_UVC }

data class CamDef(
    val id: String,
    val label: String,
    val kind: CamKind,
    val url: String = "",       // rtsp:// for IP cams
    val lowLatency: Boolean = false,
)

/**
 * Viofo A329 exposes RTSP when in Wi-Fi/LAN mode. With the 3-channel kit on
 * your vehicle LAN, typical stream paths (verify with `ffprobe`):
 *   rtsp://<cam-ip>/live      main (front) stream
 *   rtsp://<cam-ip>/live2     rear channel
 *   rtsp://<cam-ip>/live3     interior channel
 * Set static DHCP leases on the vehicle router so these never move.
 */
class CameraRegistry(context: Context) {
    private val _cams = MutableStateFlow(
        listOf(
            // Single Viofo channel (rear) — the A329S serves one live stream
            // at a time; ViofoLocator selects the rear channel via HTTP.
            CamDef("rear",    "REAR",     CamKind.RTSP, "rtsp://192.168.1.20/live", lowLatency = true),
            CamDef("thermal", "THERMAL",  CamKind.USB_UVC),
            CamDef("drone",   "DRONE",    CamKind.RTSP, "rtsp://192.168.1.30:8554/fpv", lowLatency = true),
        )
    )
    val cams: StateFlow<List<CamDef>> = _cams

    fun byId(id: String) = _cams.value.firstOrNull { it.id == id }
    fun update(def: CamDef) {
        _cams.value = _cams.value.map { if (it.id == def.id) def else it }
    }

    /**
     * Repoint every Viofo pane at a freshly discovered camera IP. The A329S
     * serves ONE live channel at a time on /live — which channel is selected
     * with WIFIAPP cmd 3028 (see ViofoLocator.selectChannel), not by path.
     */
    fun retarget(ip: String) {
        _cams.value = _cams.value.map { cam ->
            if (cam.id == "rear") cam.copy(url = "rtsp://$ip/live") else cam
        }
    }
}
