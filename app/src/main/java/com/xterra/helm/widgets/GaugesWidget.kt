package com.xterra.helm.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xterra.helm.HelmApp
import com.xterra.helm.can.TrendSample
import com.xterra.helm.can.VehicleEnergy
import com.xterra.helm.can.VehicleState
import com.xterra.helm.ui.theme.HelmColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val RPM_MAX = 7000f
private const val RPM_REDLINE = 5500f

/**
 * The graphable OBD metrics; the trend slot plots whichever one is selected.
 * TILT is special — its tile shows current max roll/pitch and, when tapped,
 * renders the truck-silhouette tilt graphic instead of a line trend.
 */
private enum class GMetric { MPG, AVG, ECT, IAT, THR, TILT }

/** RPM arc + speed numeral, tap-selectable 60 s trend, readout tiles. */
@Composable
fun GaugesWidget() {
    val s by HelmApp.instance.can.state.collectAsState()
    val trend by HelmApp.instance.can.trend.collectAsState()
    val batt by HelmApp.instance.battery.state.collectAsState()
    val tilt by HelmApp.instance.tilt.state.collectAsState()
    // Selected trend metric — persists while the pane is shown. MPG by default.
    var sel by remember { mutableStateOf(GMetric.MPG) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            // No aspectRatio: the dial fills the cell and RpmDial centers a
            // min-dimension arc, so it never spills past the pane when
            // fullscreened (a wide square would otherwise exceed the height).
            Box(Modifier.weight(1.1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                RpmDial(rpm = s.rpm)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.rpm}", style = MaterialTheme.typography.headlineMedium,
                        color = HelmColors.Cyan)
                    Text("RPM", style = MaterialTheme.typography.labelSmall,
                        color = HelmColors.TextDim)
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(s.speedKmh * 0.6214).toInt()}",
                    style = MaterialTheme.typography.displayLarge, color = HelmColors.Cyan)
                Text("MPH", style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
                DtcSummary(s)
            }
            // Permanent top-down level bubble, third instrument on the row.
            LevelBubble(Modifier.weight(0.9f).fillMaxHeight())
        }

        // Trend slot: the selected metric's 60 s line, or the tilt graphic.
        // Weighted (vs the RPM/speed row's weight 1) so it gets the larger
        // share of the flexible height — the tilt gauges need the room.
        Box(Modifier.fillMaxWidth().weight(1.35f)) {
            if (sel == GMetric.TILT) {
                InclineMini(Modifier.fillMaxSize())
            } else {
                MetricTrend(
                    metric = sel, trend = trend, avg = s.avgMpg, connected = s.connected,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatTile(GMetric.MPG, sel, s.instantMpg?.let { "%.1f".format(it) }) { sel = GMetric.MPG }
            StatTile(GMetric.AVG, sel, s.avgMpg?.let { "%.1f".format(it) }) { sel = GMetric.AVG }
            StatTile(GMetric.ECT, sel, s.coolantC?.let { "$it°C" },
                alert = (s.coolantC ?: 0) > 105) { sel = GMetric.ECT }
            StatTile(GMetric.IAT, sel, s.intakeC?.let { "$it°C" }) { sel = GMetric.IAT }
            // Fuel is reported in gallons (RANGE & FUEL), not as a percentage.
            StatTile(GMetric.THR, sel, s.throttlePct?.let { "${it.toInt()}%" }) { sel = GMetric.THR }
            // Max current tilt (dominant axis) → tap for the tilt graphic.
            val maxTilt = if (tilt.available) max(abs(tilt.rollDeg), abs(tilt.pitchDeg)) else null
            StatTile(GMetric.TILT, sel, maxTilt?.let { "%.0f°".format(it) },
                alert = (maxTilt ?: 0f) >= 25f) { sel = GMetric.TILT }
        }

        // ECM/ECU-reported values grouped together (range/fuel + starter batt)
        // above the house battery, which comes off a different link (Renogy BLE).
        Spacer(Modifier.height(12.dp))
        EnergySection(s, batt)
        Spacer(Modifier.height(12.dp))
        StarterSection(starterV = s.batteryV, ecuOnline = s.connected,
            charge = VehicleEnergy.charge(s.rpm, s.batteryV, s.connected))
        Spacer(Modifier.height(12.dp))
        HouseSection(batt)
    }
}

/** A tappable readout tile that selects the trend metric; highlights when active. */
@Composable
private fun StatTile(
    metric: GMetric, selected: GMetric, value: String?, alert: Boolean = false, onTap: () -> Unit,
) {
    val isSel = metric == selected
    Column(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (isSel) HelmColors.Cyan.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { onTap() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value ?: "—", style = MaterialTheme.typography.headlineMedium,
            color = when { alert -> HelmColors.Alert; isSel -> HelmColors.Cyan; else -> HelmColors.Text })
        Text(metric.name, style = MaterialTheme.typography.labelSmall,
            color = if (isSel) HelmColors.Cyan else HelmColors.TextDim)
    }
}

/**
 * Starter battery (secondary, ECM-reported): the lead-acid start battery's
 * voltage as the ECM reports it over OBD, plus the alternator/charge-system
 * verdict from that bus voltage while running — the early warning for a dead
 * alternator or slipped belt. Grouped with the other ECM-derived rows.
 */
@Composable
private fun StarterSection(starterV: Float?, ecuOnline: Boolean, charge: VehicleEnergy.Charge) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("STARTER BATTERY · via OBD", style = MaterialTheme.typography.labelSmall,
            color = HelmColors.TextDim)
        Spacer(Modifier.weight(1f))
        // Charge verdict — blank when the engine's off (nothing to judge).
        if (charge == VehicleEnergy.Charge.CHARGING ||
            charge == VehicleEnergy.Charge.WEAK ||
            charge == VehicleEnergy.Charge.NOT_CHARGING) {
            Text(charge.label, style = MaterialTheme.typography.labelSmall,
                color = chargeColor(charge))
            Spacer(Modifier.width(10.dp))
        }
        // <11.8 V with the engine-off is a tired lead-acid battery;
        // dashes when the ECU is asleep (key off).
        val v = starterV.takeIf { ecuOnline }
        Text(v?.let { "%.1f V".format(it) } ?: "—",
            style = MaterialTheme.typography.titleMedium,
            color = when {
                v == null -> HelmColors.TextDim
                v < 11.8f -> HelmColors.Alert
                else -> HelmColors.Cyan
            })
    }
}

/**
 * House battery (primary): the Renogy LiFePO4 — SOC bar tinted by state (green
 * charging / amber discharging / red low) + volts/amps/watts/pack temp. Comes
 * off the Renogy BLE link, not the ECM, so it lives below the ECM rows.
 */
@Composable
private fun HouseSection(b: com.xterra.helm.power.BattState) {
    val socColor = when {
        !b.connected  -> HelmColors.TextDim
        b.socPct < 20 -> HelmColors.Alert
        b.charging    -> HelmColors.Ok
        else          -> HelmColors.Amber
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("HOUSE BATTERY · LiFePO4", style = MaterialTheme.typography.labelSmall,
                color = HelmColors.Amber)
            Spacer(Modifier.width(10.dp))
            Text("${b.socPct}%", style = MaterialTheme.typography.titleMedium, color = socColor)
            Spacer(Modifier.weight(1f))
            Text(
                if (!b.connected) "LINK DOWN"
                else if (b.charging) "CHARGING" else "DISCHARGING",
                style = MaterialTheme.typography.labelSmall,
                color = if (b.connected) HelmColors.TextDim else HelmColors.Alert,
            )
        }
        Spacer(Modifier.height(6.dp))
        // SOC bar
        Box(Modifier.fillMaxWidth().height(8.dp)
            .clip(RoundedCornerShape(4.dp)).background(HelmColors.PanelEdge)) {
            Box(Modifier.fillMaxWidth(b.socPct / 100f).fillMaxHeight()
                .clip(RoundedCornerShape(4.dp)).background(socColor))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Readout("VOLTS", "%.2f".format(b.volts))
            Readout("AMPS", "%+.1f".format(b.amps))
            Readout("WATTS", "%+.0f".format(b.watts))
            b.tempC?.let { Readout("PACK", "%.0f°C".format(it), alert = it > 45f || it < 0f) }
        }
    }
}

private fun chargeColor(c: VehicleEnergy.Charge) = when (c) {
    VehicleEnergy.Charge.CHARGING -> HelmColors.Ok
    VehicleEnergy.Charge.WEAK -> HelmColors.Amber
    VehicleEnergy.Charge.NOT_CHARGING -> HelmColors.Alert
    else -> HelmColors.TextDim
}

/**
 * Range & fuel endurance (PROPOSALS.md Tier 1): drivable range with a reserve
 * held back, the round-trip radius that actually matters before committing to
 * a spur on a search, fuel aboard, and — when idling as a generator — burn
 * rate, idle endurance, and house-pack time-to-full. Null-safe: every field
 * reads "—" until the ECU reports a fuel level and a trip MPG.
 */
@Composable
private fun EnergySection(s: VehicleState, b: com.xterra.helm.power.BattState) {
    val range = VehicleEnergy.driveRangeMi(s.fuelLevelPct, s.avgMpg)
    val radius = VehicleEnergy.roundTripRadiusMi(s.fuelLevelPct, s.avgMpg)
    val gal = VehicleEnergy.fuelGal(s.fuelLevelPct)
    val gph = VehicleEnergy.fuelFlowGph(s.mafGs)
    val idling = VehicleEnergy.isIdling(s.rpm, s.speedKmh, s.connected)
    val idleH = if (idling) VehicleEnergy.idleHoursRemaining(s.fuelLevelPct, s.mafGs) else null
    Column {
        // No ECU-offline label here — the trend graph already carries that;
        // the "—" readouts below say it plainly enough.
        Text("RANGE & FUEL", style = MaterialTheme.typography.labelSmall,
            color = HelmColors.Amber)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Readout("RANGE", range?.let { "%.0f mi".format(it) } ?: "—",
                alert = range != null && range < 40f)
            Readout("↺ TURN", radius?.let { "%.0f mi".format(it) } ?: "—")
            Readout("FUEL", gal?.let { "%.1f gal".format(it) } ?: "—",
                alert = gal != null && gal < VehicleEnergy.TANK_GAL * 0.12f)
            Readout(if (idling) "IDLE" else "BURN", gph?.let { "%.1f gph".format(it) } ?: "—")
        }
        if (idling) {
            val houseFull = if (b.connected && b.charging) b.hoursRemaining else null
            val caption = buildString {
                idleH?.let { append("idle endurance %.0f h".format(it)) }
                houseFull?.let {
                    if (isNotEmpty()) append("  ·  ")
                    append("house full %.1f h".format(it))
                }
            }
            if (caption.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(caption, style = MaterialTheme.typography.labelSmall,
                    color = HelmColors.TextDim)
            }
        }
    }
}

/**
 * DTC summary under the MPH numeral: the actual stored trouble codes (mode
 * 03), red because a fault is a genuine alert. Shows "DTC OK" when the ECU
 * reports none, nothing when the ECU is offline.
 */
@Composable
private fun DtcSummary(s: com.xterra.helm.can.VehicleState) {
    if (!s.connected) return
    Spacer(Modifier.height(10.dp))
    if (s.dtcCount == 0 && !s.milOn) {
        Text("DTC OK", style = MaterialTheme.typography.labelSmall, color = HelmColors.Ok)
        return
    }
    Text(if (s.milOn) "⚠ MIL · ${s.dtcCount} DTC" else "${s.dtcCount} DTC",
        style = MaterialTheme.typography.labelSmall, color = HelmColors.Alert)
    Spacer(Modifier.height(3.dp))
    s.dtcCodes.take(4).forEach {
        Text(it, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 18.sp),
            color = HelmColors.Alert)
    }
    if (s.dtcCodes.size > 4) Text("+${s.dtcCodes.size - 4} more",
        style = MaterialTheme.typography.labelSmall, color = HelmColors.Alert)
}

@Composable
fun Readout(label: String, value: String, alert: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium,
            color = if (alert) HelmColors.Alert else HelmColors.Text)
        Text(label, style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
    }
}

/**
 * Instrument-style RPM dial: recessed track with a redline zone, major ticks
 * with ×1000 labels, and a phosphor-amber sweep (drawn twice, wide+faint
 * under thin+bright, for a CRT glow without RenderEffect cost).
 */
@Composable
private fun RpmDial(rpm: Int) {
    val tm = rememberTextMeasurer()
    val tickStyle = remember {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                  color = HelmColors.TextDim)
    }
    Canvas(Modifier.fillMaxSize().padding(8.dp)) {
        val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
        val d = size.minDimension
        val rect = Size(d, d)
        val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
        val center = Offset(size.width / 2, size.height / 2)
        val frac = (rpm / RPM_MAX).coerceIn(0f, 1f)
        val redFrac = RPM_REDLINE / RPM_MAX
        val inRed = rpm >= RPM_REDLINE

        // Track, with the redline zone marked on it.
        drawArc(HelmColors.PanelEdge, 135f, 270f * redFrac, false, topLeft, rect, style = stroke)
        drawArc(HelmColors.Alert.copy(alpha = 0.30f), 135f + 270f * redFrac,
            270f * (1f - redFrac), false, topLeft, rect, style = stroke)

        // Sweep: faint wide pass then bright pass = phosphor glow.
        val sweepColor = if (inRed) HelmColors.Alert else HelmColors.Amber
        drawArc(sweepColor.copy(alpha = 0.25f), 135f, 270f * frac, false, topLeft, rect,
            style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round))
        drawArc(sweepColor, 135f, 270f * frac, false, topLeft, rect, style = stroke)

        // Major ticks + ×1000 labels, inside the track.
        val rOuter = d / 2 - 14.dp.toPx()
        val rInner = d / 2 - 20.dp.toPx()
        val rLabel = d / 2 - 32.dp.toPx()
        for (k in 0..7) {
            val ang = Math.toRadians((135f + 270f * (k / 7f)).toDouble())
            val dir = Offset(cos(ang).toFloat(), sin(ang).toFloat())
            drawLine(
                if (k * 1000 >= RPM_REDLINE) HelmColors.Alert.copy(alpha = 0.7f)
                else HelmColors.TextDim,
                center + dir * rOuter, center + dir * rInner,
                strokeWidth = 2.dp.toPx(),
            )
            val lay = tm.measure("$k", tickStyle)
            drawText(lay, topLeft = center + dir * rLabel -
                Offset(lay.size.width / 2f, lay.size.height / 2f))
        }
    }
}

/**
 * Rolling 60 s trend of the selected metric. Single cyan series (the title
 * names it), quiet gridlines, endpoint dot; MPG additionally shows the dashed
 * trip-average reference. Series is pulled per-metric from the trend ring.
 */
@Composable
private fun MetricTrend(
    metric: GMetric,
    trend: List<TrendSample>,
    avg: Float?,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    val axisStyle = remember {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                  color = HelmColors.TextDim)
    }
    val history = trend.mapNotNull { sample ->
        when (metric) {
            GMetric.MPG -> sample.instantMpg
            GMetric.AVG -> sample.avgMpg
            GMetric.ECT -> sample.coolantC?.toFloat()
            GMetric.IAT -> sample.intakeC?.toFloat()
            GMetric.THR -> sample.throttlePct
            GMetric.TILT -> null
        }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text("${metric.name} · LAST 60 S", style = MaterialTheme.typography.labelSmall,
                color = HelmColors.TextDim)
            Spacer(Modifier.weight(1f))
            if (!connected) Text("ECU OFFLINE", style = MaterialTheme.typography.labelSmall,
                color = HelmColors.TextDim)
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val gutter = 26.dp.toPx()            // y-label gutter, left
            val plotW = size.width - gutter
            val plotH = size.height
            val yMax = when (metric) {
                GMetric.THR -> 100f     // percentage: fixed full-scale
                GMetric.MPG -> max(30f, ceil((history.maxOrNull() ?: 0f) / 10f) * 10f)
                else -> max(10f, ceil((history.maxOrNull() ?: 0f) / 10f) * 10f)
            }
            fun y(v: Float) = plotH * (1f - (v / yMax).coerceIn(0f, 1f))

            // Quiet grid: 0 / mid / max, hairline; labels in the gutter.
            for (v in listOf(0f, yMax / 2f, yMax)) {
                drawLine(HelmColors.PanelEdge, Offset(gutter, y(v)),
                    Offset(size.width, y(v)), strokeWidth = 1f)
                val lay = tm.measure("${v.toInt()}", axisStyle)
                drawText(lay, topLeft = Offset(gutter - lay.size.width - 6.dp.toPx(),
                    (y(v) - lay.size.height / 2f).coerceIn(0f, plotH - lay.size.height)))
            }

            // Dashed trip-average reference line (MPG only).
            if (metric == GMetric.MPG && avg != null && connected) {
                drawLine(HelmColors.TextDim, Offset(gutter, y(avg)),
                    Offset(size.width, y(avg)), strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }

            if (history.size < 2) {
                val lay = tm.measure(if (connected) "COLLECTING…" else "NO DATA", axisStyle)
                drawText(lay, topLeft = Offset(gutter + (plotW - lay.size.width) / 2f,
                    (plotH - lay.size.height) / 2f))
                return@Canvas
            }

            // The series: 2px cyan line over a soft gradient fill.
            val dx = plotW / (history.size - 1).coerceAtLeast(1)
            val line = Path().apply {
                history.forEachIndexed { i, v ->
                    val p = Offset(gutter + i * dx, y(v))
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(gutter + (history.size - 1) * dx, plotH)
                lineTo(gutter, plotH)
                close()
            }
            drawPath(fill, Brush.verticalGradient(
                0f to HelmColors.Cyan.copy(alpha = 0.20f),
                1f to HelmColors.Cyan.copy(alpha = 0.0f),
                endY = plotH))
            drawPath(line, HelmColors.Cyan, style = Stroke(width = 2.dp.toPx()))

            // Endpoint marker: current position, no duplicate label — the
            // MPG tile below already carries the number.
            val last = Offset(gutter + (history.size - 1) * dx, y(history.last()))
            drawCircle(HelmColors.Cyan.copy(alpha = 0.3f), 6.dp.toPx(), last)
            drawCircle(HelmColors.Cyan, 3.dp.toPx(), last)
        }
    }
}
