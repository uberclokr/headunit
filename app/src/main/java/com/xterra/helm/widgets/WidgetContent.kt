package com.xterra.helm.widgets

import androidx.compose.runtime.Composable
import com.xterra.helm.HelmApp
import com.xterra.helm.cameras.RtspView
import com.xterra.helm.cameras.ThermalView
import com.xterra.helm.ui.Widget

@Composable
fun WidgetContent(w: Widget) {
    val cams = HelmApp.instance.cameras
    when (w) {
        Widget.MAP        -> MapWidget()
        Widget.GAUGES     -> GaugesWidget()
        Widget.MEDIA      -> MediaWidget()
        Widget.SDR        -> SdrWidget()
        Widget.NET        -> NetWidget()
        Widget.INCLINE    -> InclineWidget()
        Widget.SNIFFER    -> SnifferWidget()
        Widget.LORA       -> LoraWidget()
        Widget.SETTINGS   -> SettingsWidget()
        Widget.CAM_THERMAL-> ThermalView()
        Widget.CAM_REAR   -> CamWidget()
        Widget.CAM_DRONE  -> cams.byId("drone")?.let { RtspView(it.url, it.lowLatency) }
    }
}
