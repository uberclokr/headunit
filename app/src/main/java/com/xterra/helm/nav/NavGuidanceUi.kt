package com.xterra.helm.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xterra.helm.nav.route.Guidance
import com.xterra.helm.nav.route.Maneuver
import com.xterra.helm.nav.route.NavState
import com.xterra.helm.nav.route.Place
import com.xterra.helm.nav.route.Route
import com.xterra.helm.ui.theme.HelmColors
import kotlin.math.roundToInt

// ── Route geometry → GeoJSON ────────────────────────────────────────────────

const val ROUTE_SRC = "helm-route"
const val ROUTE_CASING = "helm-route-casing"
const val ROUTE_LINE = "helm-route-line"

/** The active route as a GeoJSON LineString (empty collection when none). */
fun routeGeoJson(route: Route?): String {
    val poly = route?.polyline
    if (poly == null || poly.size < 2)
        return """{"type":"FeatureCollection","features":[]}"""
    val coords = poly.joinToString(",") { "[${it.lon},${it.lat}]" }
    return """{"type":"FeatureCollection","features":[{"type":"Feature",""" +
        """"properties":{},"geometry":{"type":"LineString","coordinates":[$coords]}}]}"""
}

// ── Maneuver presentation (shared by banner + voice) ─────────────────────────

/** Compact glyph for the maneuver — renders in the default font. */
fun Maneuver.glyph(): String = when (this) {
    Maneuver.DEPART, Maneuver.CONTINUE -> "↑"
    Maneuver.SLIGHT_LEFT -> "↖"
    Maneuver.TURN_LEFT -> "←"
    Maneuver.SHARP_LEFT -> "↰"
    Maneuver.KEEP_LEFT -> "↖"
    Maneuver.SLIGHT_RIGHT -> "↗"
    Maneuver.TURN_RIGHT -> "→"
    Maneuver.SHARP_RIGHT -> "↱"
    Maneuver.KEEP_RIGHT -> "↗"
    Maneuver.UTURN -> "↩"
    Maneuver.ROUNDABOUT -> "↻"
    Maneuver.ARRIVE -> "⚑"
}

/** Spoken/printed verb for the maneuver (no street). */
fun Maneuver.phrase(): String = when (this) {
    Maneuver.DEPART -> "Head out"
    Maneuver.CONTINUE -> "Continue"
    Maneuver.SLIGHT_LEFT -> "Slight left"
    Maneuver.TURN_LEFT -> "Turn left"
    Maneuver.SHARP_LEFT -> "Sharp left"
    Maneuver.KEEP_LEFT -> "Keep left"
    Maneuver.SLIGHT_RIGHT -> "Slight right"
    Maneuver.TURN_RIGHT -> "Turn right"
    Maneuver.SHARP_RIGHT -> "Sharp right"
    Maneuver.KEEP_RIGHT -> "Keep right"
    Maneuver.UTURN -> "Make a U-turn"
    Maneuver.ROUNDABOUT -> "Take the roundabout"
    Maneuver.ARRIVE -> "Arrive"
}

// ── Units (imperial — matches the MPH/MPG the rest of the dash shows) ─────────

/** Driver-facing distance: feet up close, miles beyond ~0.1 mi. */
fun distStr(meters: Double): String {
    val ft = meters * 3.28084
    return when {
        ft < 50 -> "now"
        ft < 1000 -> "${(ft / 50).roundToInt() * 50} ft"
        else -> "%.1f mi".format(meters / 1609.344)
    }
}

/** Longer-form distance for the trip strip (always miles once past 1000 ft). */
fun tripDistStr(meters: Double): String {
    val mi = meters / 1609.344
    return if (mi < 0.1) "${((meters * 3.28084) / 50).roundToInt() * 50} ft"
    else "%.1f mi".format(mi)
}

fun durStr(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return if (min >= 60) "%dh %02dm".format(min / 60, min % 60) else "$min min"
}

// ── Turn banner ──────────────────────────────────────────────────────────────

/**
 * Top-of-map guidance banner. Big maneuver glyph + distance-to-turn on the
 * left, the street you're turning onto and the maneuver verb on the right, and
 * a thin trip strip (remaining distance · time · ETA-less "arrive in"). Shows a
 * "rerouting" state when off-route, and a distinct arrival card. Bound entirely
 * to [NavState]; renders nothing when not navigating.
 */
@Composable
fun BoxScope.TurnBanner(
    nav: NavState, onEnd: () -> Unit, voiceMuted: Boolean, onToggleVoice: () -> Unit,
    onHeight: (Dp) -> Unit = {},
) {
    val route = nav.route ?: return
    val g = nav.guidance
    val density = LocalDensity.current
    Column(
        Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
            .widthIn(min = 300.dp, max = 560.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(HelmColors.Panel.copy(alpha = 0.94f))
            // Report full height (incl. the 8.dp top gap) so the map's corner
            // control clusters can drop below the banner in a narrow split pane.
            .onGloballyPositioned {
                onHeight(with(density) { it.size.height.toDp() } + 8.dp)
            },
    ) {
        val offRoute = nav.computing || (g != null && !g.onRoute && !g.arrived)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                offRoute -> {
                    Text("⟳", fontSize = 30.sp, color = HelmColors.Amber)
                    Column(Modifier.weight(1f)) {
                        Text("Rerouting…", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            color = HelmColors.Amber)
                        g?.let {
                            Text("${(it.offRouteM).roundToInt()} m off route",
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                color = HelmColors.TextDim)
                        }
                    }
                }
                g?.nextStep != null -> {
                    val step = g.nextStep!!
                    Text(step.maneuver.glyph(), fontSize = 34.sp, color = HelmColors.Cyan)
                    Column(Modifier.weight(1f)) {
                        Text(distStr(g.distanceToNextM), fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 24.sp, color = HelmColors.Text)
                        Text(
                            buildString {
                                append(step.maneuver.phrase())
                                if (step.street.isNotBlank()) append(" · ${step.street}")
                            },
                            fontSize = 14.sp, color = HelmColors.TextDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                else -> {
                    Text("⚑", fontSize = 30.sp, color = HelmColors.Ok)
                    Text("Approaching destination", Modifier.weight(1f),
                        fontWeight = FontWeight.Bold, fontSize = 18.sp, color = HelmColors.Ok)
                }
            }
            // Voice mute + end-nav, top-right of the banner.
            Text(if (voiceMuted) "🔇" else "🔊", fontSize = 20.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable { onToggleVoice() }.padding(6.dp))
            Text("✕", fontSize = 20.sp, color = HelmColors.TextDim,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable { onEnd() }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        // Trip strip: total remaining + arrive-in time.
        g?.let {
            Row(
                Modifier.fillMaxWidth()
                    .background(HelmColors.Glass.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(tripDistStr(it.remainingM), fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp, color = HelmColors.Cyan)
                Text(durStr(it.remainingMs), fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp, color = HelmColors.Cyan)
                nav.destinationName?.let { name ->
                    Text("→ $name", fontSize = 13.sp, color = HelmColors.TextDim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ── Address / business search panel ──────────────────────────────────────────

/**
 * Search overlay: a text field plus geocoder results. Picking a result routes
 * to it (the caller calls navigateTo). Online lookup only — the routing that
 * follows is offline. Anchored top-left, overlaying the corner controls while
 * open; closes on ✕ or after a pick.
 */
@Composable
fun BoxScope.SearchPanel(
    query: String, onQuery: (String) -> Unit,
    results: List<Place>, busy: Boolean, engineReady: Boolean,
    onPick: (Place) -> Unit, onClose: () -> Unit,
) {
    Column(
        Modifier.align(Alignment.TopStart).padding(10.dp)
            .widthIn(min = 300.dp, max = 380.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(HelmColors.Panel.copy(alpha = 0.96f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("🔍", fontSize = 16.sp)
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = TextStyle(color = HelmColors.Text, fontSize = 15.sp),
                cursorBrush = SolidColor(HelmColors.Amber),
                modifier = Modifier.weight(1f)
                    .background(HelmColors.Glass, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("address or business…",
                        color = HelmColors.TextDim, fontSize = 15.sp)
                    inner()
                },
            )
            Text("✕", fontSize = 18.sp, color = HelmColors.TextDim,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable { onClose() }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        if (!engineReady) Text("offline routing graph not installed",
            fontSize = 12.sp, color = HelmColors.Amber)
        val hint = when {
            busy -> "searching…"
            query.trim().length in 1..2 -> "keep typing…"
            query.isNotBlank() && results.isEmpty() -> "no matches (or no internet)"
            else -> null
        }
        hint?.let { Text(it, fontSize = 12.sp, color = HelmColors.TextDim) }
        if (results.isNotEmpty()) Column(
            Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            results.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(p) }.padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = HelmColors.Text, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.label.isNotBlank()) Text(p.label, color = HelmColors.TextDim,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    p.distanceM?.let {
                        Text(tripDistStr(it), color = HelmColors.Cyan, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                }
            }
        }
    }
}
