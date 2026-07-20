package com.xterra.helm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Widget(val label: String) {
    MAP("NAV"),
    GAUGES("VEHICLE"),        // merged drivetrain + house-power dashboard
    MEDIA("MEDIA"),
    CAM_REAR("CAM"),
    CAM_THERMAL("THERMAL"),
    CAM_DRONE("DRONE"),
    SDR("SDR"),
    NET("NET"),
    INCLINE("TILT"),
    SNIFFER("CAN RAW"),
    LORA("LORA"),
    SETTINGS("SETTINGS"),
}

/**
 * The docking model: one or two panes, draggable divider, any widget in
 * either slot. Deliberately simple — two big touch targets beats a desktop
 * window manager at arm's length while driving.
 */
class PaneManager {
    var left by mutableStateOf(Widget.MAP)
    var right by mutableStateOf<Widget?>(Widget.MEDIA)   // null = single pane
    var split by mutableFloatStateOf(0.55f)              // left pane fraction
    var pickerFor by mutableStateOf<Int?>(null)          // 0=left 1=right, null=closed

    fun assign(slot: Int, w: Widget) {
        if (slot == 0) left = w else right = w
        pickerFor = null
    }
    fun closeRight() { right = null }
    fun swap() { right?.let { r -> right = left; left = r } }
    fun fullscreen(w: Widget) { left = w; right = null }
}
