package com.xterra.helm.cameras

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * USB thermal camera pane.
 *
 * Most USB thermal cores (FLIR Boson w/ USB board, InfiRay P2/T2, Hikmicro,
 * Topdon) enumerate as standard UVC devices — they render through the
 * AndroidUSBCamera (libausbc) CameraView with zero extra work. Cores with
 * radiometric side-channels (raw 16-bit) need vendor SDKs; the pane below
 * uses plain UVC which covers the colorized video stream.
 *
 * Wire-up (libausbc):
 *   1. Extend CameraFragment or use AspectRatioTextureView + MultiCameraClient.
 *   2. Filter device list to your thermal VID/PID so the OBD dongle is ignored.
 * The composable hosts that fragment's view; see docs/THERMAL.md for the
 * 20-line fragment. Kept as an AndroidView hook so the pane system treats
 * it like any other camera.
 */
@Composable
fun ThermalView(modifier: Modifier = Modifier) {
    // Placeholder until the UVC fragment is wired: shows guidance in-pane.
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        Text(
            "THERMAL: connect UVC core — see docs/THERMAL.md",
            modifier = Modifier,
        )
    }
}
