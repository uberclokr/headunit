package com.xterra.helm.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xterra.helm.HelmApp
import com.xterra.helm.ui.theme.HelmColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Caution bands for a stock-height Xterra: static tipover is far past 30°,
// but off-camber dirt shifts with load — treat 15° as "pay attention" and
// 25° as "genuinely serious".
private const val WARN_DEG = 15f
private const val DANGER_DEG = 25f
private const val SCALE_DEG = 45f          // gauge full-scale each side

/**
 * Off-road inclinometer: roll (rear view) and pitch (side view) truck
 * silhouettes rotating against a fixed horizon, protractor bands, live
 * numerals, session peaks, and a combined tilt bubble. Accelerometer-only
 * (no gyro on this unit) — the DYNAMIC flag marks accel-corrupted readings;
 * peaks only latch from trusted samples.
 */
@Composable
fun InclineWidget() {
    val t by HelmApp.instance.tilt.state.collectAsState()

    if (!t.available) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("NO ACCELEROMETER", style = MaterialTheme.typography.bodyMedium,
                color = HelmColors.TextDim)
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("INCLINOMETER", style = MaterialTheme.typography.titleMedium,
                color = HelmColors.Amber)
            if (!t.calibrated) Text("TAP ZERO ON LEVEL GROUND",
                style = MaterialTheme.typography.labelSmall, color = HelmColors.Alert)
            else if (t.dynamic) Text("DYNAMIC — reading suspect",
                style = MaterialTheme.typography.labelSmall, color = HelmColors.Amber)
            else Text("STATIC OK", style = MaterialTheme.typography.labelSmall,
                color = HelmColors.Ok)
            Spacer(Modifier.weight(1f))
            InclChip("⌖ ZERO") { HelmApp.instance.tilt.zero() }
            InclChip("RESET PEAKS") { HelmApp.instance.tilt.resetPeaks() }
        }

        // Adaptive: a tall pane (split view) stacks the gauges so each gets
        // the FULL pane width — ~50% more radius than side-by-side halves.
        // A wide pane (fullscreen) keeps them side by side.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth > maxHeight * 1.3f) {
                Row(
                    Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GaugeCell(t.rollDeg, rearView = true, label = "ROLL",
                        gForce = t.latG, modifier = Modifier.weight(1f))
                    GaugeCell(t.pitchDeg, rearView = false, label = "PITCH",
                        gForce = t.lonG, modifier = Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GaugeCellTall(t.rollDeg, rearView = true, label = "ROLL",
                        gForce = t.latG, modifier = Modifier.weight(1f).fillMaxWidth())
                    GaugeCellTall(t.pitchDeg, rearView = false, label = "PITCH",
                        gForce = t.lonG, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
        }

        // Footer: bubble + peaks side by side.
        Row(
            Modifier.fillMaxWidth().height(150.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            TiltBubble(t.rollDeg, t.pitchDeg, Modifier.size(144.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PeakRow("ROLL PK", "%+.0f°".format(t.peakRollLeft),
                    "%+.0f°".format(t.peakRollRight))
                PeakRow("PITCH PK", "%+.0f°".format(t.peakPitchDown),
                    "%+.0f°".format(t.peakPitchUp))
            }
        }
    }
}

/**
 * Compact roll + pitch truck gauges for embedding in another pane (the
 * vehicle dashboard's trend slot). Same artwork as the full TILT pane, sized
 * to a short wide strip; reads the tilt state directly.
 */
@Composable
fun InclineMini(modifier: Modifier = Modifier) {
    val t by HelmApp.instance.tilt.state.collectAsState()
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("TILT · ROLL / PITCH", style = MaterialTheme.typography.labelSmall,
                color = HelmColors.TextDim)
            Spacer(Modifier.weight(1f))
            if (!t.available) Text("NO ACCEL", style = MaterialTheme.typography.labelSmall,
                color = HelmColors.TextDim)
            else if (!t.calibrated) Text("ZERO ON TILT PANE",
                style = MaterialTheme.typography.labelSmall, color = HelmColors.Amber)
        }
        Spacer(Modifier.height(4.dp))
        if (!t.available) { Spacer(Modifier.weight(1f)); return }
        // Two big gauges split the full width — the bubble lives on the main
        // dash row now, so these get all the room to be large and prominent.
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniGaugeCell(t.rollDeg, rearView = true, "ROLL", t.latG,
                Modifier.weight(1f).fillMaxHeight())
            MiniGaugeCell(t.pitchDeg, rearView = false, "PITCH", t.lonG,
                Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Gauge with its numeral + label stacked beneath (no overlap with the arc). */
@Composable
private fun MiniGaugeCell(deg: Float, rearView: Boolean, label: String, g: Float, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        TiltGauge(deg, rearView, g, Modifier.weight(1f).fillMaxWidth())
        Text(if (abs(deg) < 0.05f) "0.0°" else "%+.1f°".format(deg),
            style = MaterialTheme.typography.headlineSmall, color = severity(deg))
        Text(label, style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
    }
}

/**
 * Compact top-down level bubble for the main dashboard row (permanent,
 * alongside RPM and MPH). Self-contained — reads the tilt state directly.
 */
@Composable
fun LevelBubble(modifier: Modifier = Modifier) {
    val t by HelmApp.instance.tilt.state.collectAsState()
    if (t.available) {
        TiltBubble(t.rollDeg, t.pitchDeg, modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("—", style = MaterialTheme.typography.displayLarge, color = HelmColors.TextDim)
        }
    }
}

/** Wide-pane cell: gauge with its numeral snugged directly beneath. */
@Composable
private fun GaugeCell(
    deg: Float, rearView: Boolean, label: String, gForce: Float, modifier: Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // 1.28 ≈ the semicircle + truck's natural envelope: full half-width
        // radius with no slack above or below.
        TiltGauge(deg, rearView, gForce, Modifier.fillMaxWidth().aspectRatio(1.28f))
        BigAngle(deg)
        GaugeLabel(label, gForce)
    }
}

@Composable
private fun GaugeLabel(label: String, gForce: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = HelmColors.TextDim)
        Text("%.2f g".format(abs(gForce)),
            style = MaterialTheme.typography.labelSmall, color = gColor(gForce))
    }
}

/**
 * Tall-pane cell: the gauge fills the whole cell and the numeral rides in
 * the lower-right corner — dead space outside the protractor's arc.
 */
@Composable
private fun GaugeCellTall(
    deg: Float, rearView: Boolean, label: String, gForce: Float, modifier: Modifier,
) {
    Box(modifier) {
        TiltGauge(deg, rearView, gForce, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            BigAngle(deg)
            GaugeLabel(label, gForce)
        }
    }
}

private fun severity(deg: Float): Color = when {
    abs(deg) >= DANGER_DEG -> HelmColors.Alert
    abs(deg) >= WARN_DEG -> HelmColors.Amber
    else -> HelmColors.Cyan
}

// Signed display: + = right-side-down (roll) / nose-up (pitch), − opposite.
@Composable
private fun BigAngle(deg: Float) {
    Text(if (abs(deg) < 0.05f) "0.0°" else "%+.1f°".format(deg),
        style = MaterialTheme.typography.displayLarge, color = severity(deg))
}

@Composable
private fun PeakRow(label: String, a: String, b: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
        Text("$a  $b", style = MaterialTheme.typography.bodyMedium, color = HelmColors.Text)
    }
}

@Composable
private fun InclChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(9.dp)).background(HelmColors.Panel)
            .border(1.dp, HelmColors.Amber, RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = HelmColors.Amber)
    }
}

/**
 * The real truck artwork: PNGs in assets (xterra_rear.png / xterra_side.png)
 * traced from the owner's photos. Loaded once; null (file absent) falls back
 * to the schematic vector glyphs so the gauge never goes blank.
 */
@Composable
private fun rememberTruckArt(name: String): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(name) {
        runCatching {
            ctx.assets.open(name).use { android.graphics.BitmapFactory.decodeStream(it) }
                ?.asImageBitmap()
        }.getOrNull()
    }
}

/**
 * Protractor gauge: fixed dashed horizon + tick arc with warn/danger bands;
 * the truck rotates by the live angle (classic inclinometer presentation —
 * vehicle tips against a fixed horizon).
 */
private fun gColor(g: Float): Color = when {
    abs(g) >= 0.5f -> HelmColors.Alert
    abs(g) >= 0.25f -> HelmColors.Amber
    else -> HelmColors.Ok
}

@Composable
private fun TiltGauge(deg: Float, rearView: Boolean, gForce: Float, modifier: Modifier) {
    val art = rememberTruckArt(if (rearView) "xterra_rear.png" else "xterra_side.png")
    val tm = rememberTextMeasurer()
    val tickStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        color = HelmColors.TextDim)
    Canvas(modifier.padding(6.dp)) {
        // Fill the cell: radius takes the full half-width, arc apex at the
        // top edge, truck envelope just fitting below the pivot.
        val r = min(size.width / 2, size.height * 0.72f) - 8.dp.toPx()
        val c = Offset(size.width / 2, r + 18.dp.toPx())

        // Fixed horizon
        drawLine(HelmColors.TextDim, Offset(c.x - r, c.y), Offset(c.x + r, c.y),
            strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))

        // Bands: OK / warn / danger, mirrored. Canvas angle: -90 = straight up.
        fun band(a1: Float, a2: Float, color: Color) {
            drawArc(color, -90f + a1, a2 - a1, false,
                topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r),
                style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Butt))
        }
        band(-SCALE_DEG, -DANGER_DEG, HelmColors.Alert.copy(alpha = 0.55f))
        band(-DANGER_DEG, -WARN_DEG, HelmColors.Amber.copy(alpha = 0.5f))
        band(-WARN_DEG, WARN_DEG, HelmColors.PanelEdge)
        band(WARN_DEG, DANGER_DEG, HelmColors.Amber.copy(alpha = 0.5f))
        band(DANGER_DEG, SCALE_DEG, HelmColors.Alert.copy(alpha = 0.55f))

        // Ticks every 5°, labels every 15°.
        for (a in -SCALE_DEG.toInt()..SCALE_DEG.toInt() step 5) {
            val major = a % 15 == 0
            val ang = Math.toRadians(a.toDouble() - 90)
            val dir = Offset(cos(ang).toFloat(), sin(ang).toFloat())
            drawLine(if (major) HelmColors.Text else HelmColors.TextDim,
                c + dir * (r - if (major) 18.dp.toPx() else 11.dp.toPx()),
                c + dir * (r - 4.dp.toPx()), strokeWidth = if (major) 3f else 1.5f)
            if (major && a != 0) {
                val lay = tm.measure("${abs(a)}", tickStyle)
                val p = c + dir * (r - 30.dp.toPx())
                drawText(lay, topLeft = p - Offset(lay.size.width / 2f, lay.size.height / 2f))
            }
        }

        // Felt-G arrow BEHIND the truck, in the vehicle frame (rotates with
        // it): length ∝ g (full radius ≈ 1 g), color by magnitude. Rear view
        // = lateral force, side view = longitudinal (braking pushes toward
        // the nose). 0.04 g deadband keeps it invisible at rest.
        if (abs(gForce) > 0.04f) {
            val len = (abs(gForce) * r * 0.9f).coerceAtMost(r * 0.95f)
            val dir = if (gForce >= 0f) 1f else -1f
            val col = gColor(gForce)
            rotate(deg, pivot = c) {
                val tip = c + Offset(dir * len, 0f)
                drawLine(col.copy(alpha = 0.55f), c, tip,
                    strokeWidth = 9.dp.toPx(), cap = StrokeCap.Round)
                for (wing in floatArrayOf(-1f, 1f)) {
                    drawLine(col.copy(alpha = 0.55f), tip,
                        tip + Offset(-dir * 14.dp.toPx(), wing * 10.dp.toPx()),
                        strokeWidth = 9.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }

        // Indicator needle, drawn BEFORE the truck so the body occludes the
        // shaft — only the tip pokes above the roofline. Rotates with the
        // vehicle so its tip sweeps the fixed scale as the angle changes.
        rotate(deg, pivot = c) {
            drawLine(severity(deg), c, c + Offset(0f, -r + 6.dp.toPx()),
                strokeWidth = 3.5f, cap = StrokeCap.Round)
        }

        // The truck, rotated with the vehicle. Real artwork when the asset
        // exists; schematic outline otherwise. Sized to clear the tick
        // labels and anchored with the axle line on the horizon (wheel hubs
        // sit at ~86% height in the rear PNG, ~78% in the side PNG).
        rotate(deg, pivot = c) {
            if (art != null) {
                // Common HEIGHT for both views (width follows each image's
                // aspect) so the truck reads the same size on both gauges.
                val hubFrac = if (rearView) 0.86f else 0.78f
                val th = r * 0.72f
                val tw = th * art.width / art.height
                drawImage(
                    art,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(art.width, art.height),
                    dstOffset = IntOffset(
                        (c.x - tw / 2).toInt(), (c.y - th * hubFrac).toInt()),
                    dstSize = IntSize(tw.toInt(), th.toInt()),
                )
            } else if (rearView) drawTruckRear(c, r * 0.82f, severity(deg))
            else drawTruckSide(c, r * 0.82f, severity(deg))
        }
    }
}

// The owner's truck: 2nd-gen (N50) Xterra in Chief Blue — hue sampled from
// reference photos of the actual vehicle. The glyphs keep the paint color at
// all times; the needle and numerals carry the severity color instead.
private val ChiefBlue = Color(0xFF2FA1EA)

/**
 * Rear outline of the N50: tapered greenhouse over a wide track, roof
 * basket, swing-arm spare carrier on the tailgate, flared arches over the
 * oversize tires.
 */
private fun DrawScope.drawTruckRear(c: Offset, w: Float, @Suppress("UNUSED_PARAMETER") sev: Color) {
    val s = Stroke(width = 3.5f, cap = StrokeCap.Round)
    val color = ChiefBlue
    val sill = c.y + w * 0.10f
    val belt = sill - w * 0.26f
    val roof = sill - w * 0.46f
    val bodyHalf = w * 0.40f
    val glassHalf = w * 0.33f
    val body = Path().apply {
        moveTo(c.x - bodyHalf, sill)
        lineTo(c.x - bodyHalf - w * 0.02f, belt)             // slight outward flare
        lineTo(c.x - glassHalf, roof + w * 0.05f)            // D-pillar taper
        quadraticBezierTo(c.x - glassHalf + w * 0.02f, roof, // rounded corner
            c.x - glassHalf + w * 0.06f, roof)
        lineTo(c.x + glassHalf - w * 0.06f, roof)            // roof line
        quadraticBezierTo(c.x + glassHalf - w * 0.02f, roof,
            c.x + glassHalf, roof + w * 0.05f)
        lineTo(c.x + bodyHalf + w * 0.02f, belt)
        lineTo(c.x + bodyHalf, sill)
        close()
    }
    drawPath(body, color, style = s)
    // Roof basket: rails + short posts.
    drawLine(color, Offset(c.x - glassHalf + w * 0.04f, roof - w * 0.055f),
        Offset(c.x + glassHalf - w * 0.04f, roof - w * 0.055f), 3.5f, StrokeCap.Round)
    for (px in listOf(-glassHalf + w * 0.07f, 0f, glassHalf - w * 0.07f)) {
        drawLine(color, Offset(c.x + px, roof - w * 0.05f),
            Offset(c.x + px, roof), 2.5f, StrokeCap.Round)
    }
    // Spare on the swing-arm carrier: bar across the gate + tire circle.
    drawLine(color, Offset(c.x - bodyHalf, belt + w * 0.05f),
        Offset(c.x + bodyHalf, belt + w * 0.05f), 2.5f, StrokeCap.Round)
    drawCircle(color, w * 0.085f, Offset(c.x + w * 0.02f, belt + w * 0.10f), style = s)
    // Wheels below the sill + open fender arches.
    val wheelR = w * 0.105f
    for (side in intArrayOf(-1, 1)) {
        val wc = Offset(c.x + side * w * 0.35f, sill + wheelR * 0.55f)
        drawCircle(color, wheelR, wc, style = s)
        drawCircle(color, wheelR * 0.4f, wc, style = Stroke(width = 2.5f))
        drawArc(color, 180f, 180f, false,
            topLeft = Offset(wc.x - wheelR * 1.35f, wc.y - wheelR * 1.35f),
            size = Size(wheelR * 2.7f, wheelR * 2.7f),
            style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}

/**
 * Side outline of the N50, nose to the left: bull bar, raked windshield,
 * the signature stepped rear roof with basket, side step, rear whip.
 */
private fun DrawScope.drawTruckSide(c: Offset, w: Float, @Suppress("UNUSED_PARAMETER") sev: Color) {
    val s = Stroke(width = 3.5f, cap = StrokeCap.Round)
    val color = ChiefBlue
    val l = w * 1.30f                                        // overall length
    val sill = c.y + w * 0.06f
    val topF = sill - l * 0.30f                              // front roof
    val topR = topF - l * 0.030f                             // raised rear roof
    val x = { f: Float -> c.x + f * l }                      // -0.5..+0.5 span
    val body = Path().apply {
        moveTo(x(-0.50f), sill)
        lineTo(x(-0.50f), sill - l * 0.115f)                 // bumper face
        lineTo(x(-0.47f), sill - l * 0.165f)                 // grille
        lineTo(x(-0.22f), sill - l * 0.195f)                 // hood
        lineTo(x(-0.095f), topF)                             // raked windshield
        lineTo(x(0.075f), topF)                              // front roof
        lineTo(x(0.10f), topR)                               // the roof step
        lineTo(x(0.43f), topR)                               // rear roof
        lineTo(x(0.47f), sill - l * 0.13f)                   // tailgate
        lineTo(x(0.50f), sill - l * 0.105f)                  // rear bumper
        lineTo(x(0.50f), sill)
        close()
    }
    drawPath(body, color, style = s)
    // Bull bar: hoop proud of the bumper.
    drawArc(color, 90f, 180f, false,
        topLeft = Offset(x(-0.545f), sill - l * 0.17f),
        size = Size(l * 0.07f, l * 0.10f), style = Stroke(width = 3f, cap = StrokeCap.Round))
    // Roof basket on the raised section.
    drawLine(color, Offset(x(0.13f), topR - l * 0.040f),
        Offset(x(0.40f), topR - l * 0.040f), 3.5f, StrokeCap.Round)
    drawLine(color, Offset(x(0.145f), topR - l * 0.036f), Offset(x(0.145f), topR),
        2.5f, StrokeCap.Round)
    drawLine(color, Offset(x(0.385f), topR - l * 0.036f), Offset(x(0.385f), topR),
        2.5f, StrokeCap.Round)
    // Rear whip antenna.
    drawLine(color, Offset(x(0.44f), topR), Offset(x(0.47f), topR - l * 0.10f),
        2f, StrokeCap.Round)
    // Side step between the arches.
    drawLine(color, Offset(x(-0.14f), sill + l * 0.012f),
        Offset(x(0.16f), sill + l * 0.012f), 3f, StrokeCap.Round)
    // Wheels + open arches.
    val wheelR = l * 0.105f
    for (fx in floatArrayOf(-0.295f, 0.315f)) {
        val wc = Offset(x(fx), sill + wheelR * 0.45f)
        drawCircle(color, wheelR, wc, style = s)
        drawCircle(color, wheelR * 0.4f, wc, style = Stroke(width = 2.5f))
        drawArc(color, 175f, 190f, false,
            topLeft = Offset(wc.x - wheelR * 1.32f, wc.y - wheelR * 1.32f),
            size = Size(wheelR * 2.64f, wheelR * 2.64f),
            style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}

/**
 * Combined-tilt bubble: rings at 15/25/40°, dot = (roll, pitch). The dot
 * moves like the bubble in a spirit level — downhill side.
 */
@Composable
private fun TiltBubble(rollDeg: Float, pitchDeg: Float, modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = min(size.width, size.height) / 2 - 4.dp.toPx()
        val pxPerDeg = r / 40f
        for ((ringDeg, col) in listOf(
            WARN_DEG to HelmColors.PanelEdge,
            DANGER_DEG to HelmColors.Amber.copy(alpha = 0.5f),
            40f to HelmColors.Alert.copy(alpha = 0.55f),
        )) {
            drawCircle(col, ringDeg * pxPerDeg, c, style = Stroke(width = 1.5f))
        }
        drawLine(HelmColors.PanelEdge, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(HelmColors.PanelEdge, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        val mag = severity(maxOf(abs(rollDeg), abs(pitchDeg)))
        val dot = Offset(
            c.x + (rollDeg * pxPerDeg).coerceIn(-r, r),
            c.y + (pitchDeg * pxPerDeg).coerceIn(-r, r),   // nose-up = dot toward tail
        )
        drawCircle(mag.copy(alpha = 0.3f), 9.dp.toPx(), dot)
        drawCircle(mag, 5.dp.toPx(), dot)
    }
}
