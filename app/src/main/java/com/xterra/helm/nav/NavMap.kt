package com.xterra.helm.nav

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xterra.helm.HelmApp
import com.xterra.helm.ui.theme.HelmColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnLocationCameraTransitionListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.File
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import com.xterra.helm.can.VehicleEnergy
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Backcountry nav map on MapLibre Native. USGS Topo (or imagery) draped over
 * a 3D terrain mesh, tilted for an overhead-3D view, with the location puck.
 * Base-layer toggle overlays the map. Free/keyless tiles — see [MapStyles].
 *
 * MapView is a legacy Android View with its own lifecycle, so we forward the
 * Compose lifecycle to it and destroy it on dispose (panes come and go).
 */
@Composable
fun NavMap() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var base by remember { mutableStateOf(MapStyles.Base.TOPO) }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val gps by HelmApp.instance.gps.state.collectAsState()
    // Vehicle state → the round-trip range ring (fuel + trip MPG).
    val veh by HelmApp.instance.can.state.collectAsState()
    // Lock onto the vehicle once, when the first fix lands after startup.
    var lockedOnce by remember { mutableStateOf(false) }
    // Follow-behind (heading-up, tilted chase cam) vs north-up overview.
    var follow by remember { mutableStateOf(true) }
    // Waypoints (GeoJSON) + the currently tapped one (id to name).
    val pois by HelmApp.instance.poi.pois.collectAsState()
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Reserve check: can the one-way range still reach the nearest fuel/base?
    // Recomputed when the fix, the waypoints, or fuel/MPG change.
    val nearestFuel = remember(pois, gps.lat, gps.lon, gps.hasFix) {
        if (gps.hasFix) HelmApp.instance.poi.nearest(gps.lat, gps.lon, PoiStore.REFUEL_KINDS)
        else null
    }
    val oneWayRangeMi = VehicleEnergy.driveRangeMi(veh.fuelLevelPct, veh.avgMpg)
    val nearestFuelMi = nearestFuel?.let { (it.distanceM / 1609.344).toFloat() }
    val reserveShort = VehicleEnergy.reserveShort(oneWayRangeMi, nearestFuelMi)
    // LoRaWAN tracker nodes — shown as green dots when the LNS is enabled.
    val loraStates by HelmApp.instance.lora.states.collectAsState()
    val loraCfg by HelmApp.instance.lora.config.collectAsState()
    // Offline region-download progress text (region management lives in Settings).
    var cacheMsg by remember { mutableStateOf<String?>(null) }
    var cachePanelOpen by remember { mutableStateOf(false) }
    // Address / business search (online geocode → offline route).
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.xterra.helm.nav.route.Place>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }
    // Turn-by-turn: live route/guidance + offline voice prompts.
    val nav by HelmApp.instance.nav.state.collectAsState()
    val voice = remember { NavVoice(ctx) }
    var voiceMuted by remember { mutableStateOf(false) }
    // Measured banner height → how far to drop the corner controls beneath it.
    var bannerH by remember { mutableStateOf(0.dp) }
    DisposableEffect(Unit) { onDispose { voice.shutdown() } }
    LaunchedEffect(voiceMuted) { voice.muted = voiceMuted }

    // Push the active route geometry into the map source; reset voice per trip.
    LaunchedEffect(nav.route) {
        mapRef.value?.style?.getSourceAs<GeoJsonSource>(ROUTE_SRC)
            ?.setGeoJson(routeGeoJson(nav.route))
        if (nav.route != null) voice.reset() else bannerH = 0.dp
    }
    // Round-trip range ring: re-centre on the vehicle and re-scale as fuel or
    // trip MPG change. Draws nothing until both a fix and a real MPG exist.
    LaunchedEffect(gps.lat, gps.lon, gps.hasFix, veh.fuelLevelPct, veh.avgMpg) {
        updateRangeRing(mapRef.value)
    }
    // Debounced geocode: re-query ~350 ms after typing stops, biased to the fix.
    LaunchedEffect(query) {
        if (query.trim().length < 3) { results = emptyList(); searchBusy = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        searchBusy = true
        results = withContext(Dispatchers.IO) {
            com.xterra.helm.nav.route.Geocoder.search(
                query, gps.lat.takeIf { gps.hasFix }, gps.lon.takeIf { gps.hasFix })
        }
        searchBusy = false
    }

    // Speak the upcoming maneuver as it comes into range / at the turn.
    LaunchedEffect(nav.guidance) { voice.announce(nav.guidance) }
    // Announce a reroute the moment we drop off the line.
    LaunchedEffect(nav.guidance?.onRoute) {
        if (nav.guidance?.onRoute == false && nav.route != null) voice.onReroute()
    }

    // Apply the camera mode whenever it toggles (or after first activation).
    // The first application sets the mode's default zoom; after that a mode
    // toggle keeps whatever zoom the user (or a preset) chose.
    var appliedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(follow, lockedOnce) {
        if (!lockedOnce) return@LaunchedEffect
        mapRef.value?.let {
            applyCameraMode(it, follow, initial = !appliedOnce)
            appliedOnce = true
        }
    }

    // Push waypoint changes into the map's GeoJSON source live.
    LaunchedEffect(pois) {
        mapRef.value?.style?.getSourceAs<GeoJsonSource>(POI_SRC)?.setGeoJson(pois)
    }

    // Push LoRa node positions live (cleared when the LNS is off).
    LaunchedEffect(loraStates, loraCfg.enabled) {
        mapRef.value?.style?.getSourceAs<GeoJsonSource>(LORA_SRC)
            ?.setGeoJson(loraGeoJson(if (loraCfg.enabled) loraStates else emptyList()))
    }

    // MapLibre must be initialized before any MapView is created. Keyless.
    // Ambient tile cache: everywhere you've driven, kept as an automatic LRU so
    // it survives a Starlink drop with no explicit download. Held to 512 MB —
    // /data is small (~2.5 GB free) and an explicit regional download (below)
    // competes for that space. Also lift MapLibre's default 6 000-tile cap on
    // *explicit* regions to 300 k, or a real region (Western Oregon at z13 is
    // ~13 k tiles) aborts partway as "tile count limit exceeded".
    remember {
        MapLibre.getInstance(ctx)
        OfflineManager.getInstance(ctx).apply {
            setMaximumAmbientCacheSize(536_870_912L, object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {}
                override fun onError(message: String) {}
            })
            setOfflineMapboxTileCountLimit(300_000L)
        }
        true
    }
    val mapView = remember { MapView(ctx).apply { onCreate(null) } }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Re-skin when the base layer changes (keeps camera + location).
    LaunchedEffect(base) { mapRef.value?.let { applyStyle(ctx, it, base) } }

    // Feed the puck from our USB GPS; lock the camera on the first fix.
    LaunchedEffect(gps) {
        val map = mapRef.value ?: return@LaunchedEffect
        if (!gps.hasFix) return@LaunchedEffect
        runCatching {
            val lc = map.locationComponent
            if (lc.isLocationComponentActivated && lc.isLocationComponentEnabled) {
                lc.forceLocationUpdate(gps.toLocation())
                if (!lockedOnce) lockedOnce = true   // camera-mode effect takes it
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.also {
                    it.getMapAsync { map ->
                        mapRef.value = map
                        map.uiSettings.isTiltGesturesEnabled = true
                        map.uiSettings.isRotateGesturesEnabled = true
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(44.06, -121.31)) // default: Cascades
                            .zoom(12.0).tilt(55.0).build()
                        applyStyle(ctx, map, base)
                        // Long-press drops a waypoint; tap selects one to edit/delete.
                        map.addOnMapLongClickListener { ll ->
                            val n = HelmApp.instance.poi.count() + 1
                            HelmApp.instance.poi.add(ll.latitude, ll.longitude, "WP-$n")
                            true
                        }
                        map.addOnMapClickListener { ll ->
                            val pt = map.projection.toScreenLocation(ll)
                            val hit = map.queryRenderedFeatures(pt, POI_LAYER).firstOrNull()
                            selected = hit?.let {
                                it.getStringProperty("id") to (it.getStringProperty("name") ?: "")
                            }
                            hit != null
                        }
                    }
                }
            },
        )

        // Turn-by-turn banner (top-center), shown only while navigating.
        TurnBanner(
            nav,
            onEnd = { HelmApp.instance.nav.clear() },
            voiceMuted = voiceMuted,
            onToggleVoice = { voiceMuted = !voiceMuted },
            onHeight = { bannerH = it },
        )

        // Corner layout: wide rows hug the corners, short chips ride the
        // edges, so the center of the map stays clear. While navigating, the
        // top clusters drop below the turn banner (narrow split panes overlap
        // otherwise) — measured height, so it tracks the banner's actual size.
        val topInset = if (bannerH > 0.dp) bannerH + 6.dp else 10.dp
        // Top-right: base-layer row only.
        Row(
            Modifier.align(Alignment.TopEnd)
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = topInset),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MapStyles.Base.entries.forEach { b ->
                LayerChip(b.label, active = base == b) { base = b }
            }
        }

        // Top-left: search + waypoint counter.
        Column(
            Modifier.align(Alignment.TopStart)
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = topInset),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Search a place/business, then route to it (online lookup only).
            LayerChip("🔍 SEARCH", active = searchOpen) {
                searchOpen = !searchOpen; if (!searchOpen) { query = ""; results = emptyList() }
            }
            val n = pois.features()?.size ?: 0
            LayerChip(if (n == 0) "⌖ long-press to drop WP" else "⌖ $n WP", active = false) {}
        }

        // Bottom-left: offline-cache capture — opens a detail/size chooser (frame
        // the area first by zooming out). Region/graph management: Settings.
        LayerChip(cacheMsg ?: "⤓ CACHE THIS VIEW", active = cacheMsg != null || cachePanelOpen,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            cacheMsg = null; cachePanelOpen = true
        }

        if (cachePanelOpen) CachePanel(
            map = mapRef.value, base = base,
            onStart = { b, minZ, maxZ ->
                downloadRegion(ctx, base, b, minZ, maxZ) { cacheMsg = it }
                cachePanelOpen = false
            },
            onClose = { cachePanelOpen = false },
        )

        if (searchOpen) SearchPanel(
            query = query, onQuery = { query = it },
            results = results, busy = searchBusy,
            engineReady = nav.engineReady,
            onPick = { p ->
                HelmApp.instance.nav.navigateTo(p.lat, p.lon, p.name)
                searchOpen = false; query = ""; results = emptyList()
            },
            onClose = { searchOpen = false; query = ""; results = emptyList() },
        )

        // Reserve alert: the one-way range no longer reaches the nearest fuel
        // or base. Genuine red banner — this is the "you may be walking" call.
        if (reserveShort && nearestFuel != null && oneWayRangeMi != null) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 58.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(HelmColors.Alert.copy(alpha = 0.94f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("⚠ FUEL", style = MaterialTheme.typography.labelSmall,
                    color = HelmColors.Glass)
                Text("nearest ${nearestFuel.kind} %.0f mi · range %.0f mi"
                    .format(nearestFuelMi, oneWayRangeMi),
                    style = MaterialTheme.typography.labelSmall, color = HelmColors.Glass)
            }
        }

        // Selected waypoint card: rename, re-tag (wp/fuel/base), route, delete.
        selected?.let { (id, name) ->
            var editName by remember(id) { mutableStateOf(name) }
            val curKind = pois.features()
                ?.firstOrNull { it.getStringProperty("id") == id }
                ?.getStringProperty("kind") ?: PoiStore.KIND_WP
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(HelmColors.Panel.copy(alpha = 0.92f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasicTextField(
                        value = editName, onValueChange = { editName = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                            .copy(color = HelmColors.Text),
                        cursorBrush = SolidColor(HelmColors.Amber),
                        modifier = Modifier.widthIn(min = 90.dp, max = 200.dp)
                            .background(HelmColors.Glass, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    // Category drives the reserve alert + the dot color.
                    LayerChip("WP", active = curKind == PoiStore.KIND_WP) {
                        HelmApp.instance.poi.setKind(id, PoiStore.KIND_WP)
                    }
                    LayerChip("⛽FUEL", active = curKind == PoiStore.KIND_FUEL) {
                        HelmApp.instance.poi.setKind(id, PoiStore.KIND_FUEL)
                    }
                    LayerChip("⌂BASE", active = curKind == PoiStore.KIND_BASE) {
                        HelmApp.instance.poi.setKind(id, PoiStore.KIND_BASE)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Route to this waypoint from the current fix (turn-by-turn).
                    LayerChip(if (nav.engineReady) "▶ NAVIGATE" else "▶ (no map)",
                        active = nav.route != null) {
                        if (nav.engineReady) {
                            val pt = pois.features()
                                ?.firstOrNull { it.getStringProperty("id") == id }
                                ?.geometry() as? org.maplibre.geojson.Point
                            pt?.let {
                                HelmApp.instance.nav.navigateTo(
                                    it.latitude(), it.longitude(),
                                    name.ifBlank { "Waypoint" })
                            }
                            selected = null
                        }
                    }
                    LayerChip("✓", active = false) {
                        editName.trim().takeIf { it.isNotEmpty() && it != name }
                            ?.let { HelmApp.instance.poi.rename(id, it) }
                        selected = null
                    }
                    LayerChip("DELETE", active = false) {
                        HelmApp.instance.poi.remove(id); selected = null
                    }
                    LayerChip("✕", active = false) { selected = null }
                }
            }
        }

        // Camera controls, bottom-right: short chips stack up the edge, the
        // wide zoom-preset row sits last, tight in the corner. Order top→bottom:
        // ME, then FOLLOW/NORTH, then the zoom presets.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Re-lock onto the vehicle (tracking drops out when the user pans).
            LayerChip("◎ ME", active = false) {
                mapRef.value?.let { runCatching { applyCameraMode(it, follow) } }
            }
            LayerChip(if (follow) "⬆ FOLLOW" else "▲ NORTH", active = follow) {
                follow = !follow
            }
            // Tap = jump to preset; hold = overwrite preset with current zoom
            // (the label updating is the save confirmation).
            val zooms = HelmApp.instance.settings.state.collectAsState().value.navZooms
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                zooms.forEachIndexed { i, z ->
                    LayerChip(
                        "z" + (if (z == z.toLong().toDouble()) "${z.toLong()}"
                               else "%.1f".format(z)),
                        active = false,
                        onLongClick = {
                            mapRef.value?.let {
                                HelmApp.instance.settings
                                    .setNavZoom(i, it.cameraPosition.zoom)
                            }
                        },
                    ) { mapRef.value?.let { setZoom(it, z) } }
                }
            }
        }
    }
}

/**
 * North-up overview (flat, TRACKING) vs follow-behind (heading-up chase cam:
 * TRACKING_GPS rotates the map to the GPS course, with tilt for the
 * over-the-hood perspective). Uses the *GPS* course, not the head unit's fixed
 * magnetometer — that's why the puck no longer just points north.
 *
 * [initial] applies the mode's default zoom; otherwise the current zoom is
 * kept so mode toggles / ◎ ME don't stomp a preset the user just picked.
 */
private fun applyCameraMode(map: MapLibreMap, follow: Boolean, initial: Boolean = false) {
    val lc = map.locationComponent
    if (!lc.isLocationComponentActivated) return
    val mode = if (follow) CameraMode.TRACKING_GPS else CameraMode.TRACKING
    val tilt = if (follow) 58.0 else 0.0
    val zoom = if (initial) (if (follow) 16.0 else 14.0) else null  // null = keep
    // Chase-cam framing: pad the top ~55% of the view so the puck rides in
    // the bottom third and the screen shows the road *ahead*.
    val pad = if (follow) doubleArrayOf(0.0, map.height * 0.55, 0.0, 0.0)
              else doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    // Zoom/tilt must ride the mode transition itself — any *WhileTracking
    // call made DURING the fly-to-puck transition is silently ignored. Padding
    // has no slot in the overload, so it waits for the completion callback.
    val thenPad = object : OnLocationCameraTransitionListener {
        override fun onLocationCameraTransitionFinished(cameraMode: Int) {
            runCatching { lc.paddingWhileTracking(pad) }
        }
        override fun onLocationCameraTransitionCanceled(cameraMode: Int) {
            runCatching { lc.paddingWhileTracking(pad) }
        }
    }
    lc.setCameraMode(mode, 900L, zoom, null, tilt, thenPad)
}

/**
 * Jump to a preset zoom without breaking tracking: while the location camera
 * owns the view, zoom must go through zoomWhileTracking (a plain camera move
 * would kick the mode to NONE); free camera gets a normal animate.
 */
private fun setZoom(map: MapLibreMap, zoom: Double) {
    val lc = map.locationComponent
    val tracking = runCatching {
        lc.isLocationComponentActivated && lc.cameraMode != CameraMode.NONE
    }.getOrDefault(false)
    if (tracking) lc.zoomWhileTracking(zoom)
    else map.animateCamera(CameraUpdateFactory.zoomTo(zoom))
}

/** Our parsed fix → an Android Location for MapLibre's puck. */
private fun GpsFix.toLocation(): Location = Location("helm-gps").apply {
    latitude = lat
    longitude = lon
    altitude = altM
    accuracy = (hdop * 5f).coerceIn(1f, 50f)
    if (speedMps > 0f) speed = speedMps
    if (courseDeg > 0f) bearing = courseDeg
    time = epochMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
}

private fun applyStyle(ctx: Context, map: MapLibreMap, base: MapStyles.Base) {
    map.setStyle(Style.Builder().fromJson(MapStyles.style(base))) { style ->
        // Round-trip range ring: dashed amber circle at the turnaround radius —
        // how far you can go and still get back on the fuel aboard. Added first
        // so the route, waypoints, and puck all render above it. Populated from
        // current state here (setStyle rebuilds layers) and kept live by the
        // range effect.
        style.addSource(GeoJsonSource(RANGE_SRC, EMPTY_FC))
        style.addLayer(LineLayer(RANGE_RING, RANGE_SRC).withProperties(
            PropertyFactory.lineColor("#FFB454"),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineOpacity(0.8f),
            PropertyFactory.lineDasharray(arrayOf(3f, 3f)),
        ))
        // Active route: a cyan line over a dark casing for contrast on both topo
        // and imagery. Added first so waypoints, LoRa dots, and the puck all
        // render above it. Fed live from NavRepository (see the route effect).
        style.addSource(GeoJsonSource(ROUTE_SRC, routeGeoJson(HelmApp.instance.nav.state.value.route)))
        style.addLayer(LineLayer(ROUTE_CASING, ROUTE_SRC).withProperties(
            PropertyFactory.lineColor("#0B0F14"),
            PropertyFactory.lineWidth(9f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(0.9f),
        ))
        style.addLayer(LineLayer(ROUTE_LINE, ROUTE_SRC).withProperties(
            PropertyFactory.lineColor("#7FD7E8"),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(0.95f),
        ))
        // Waypoint layer: amber dots with a white ring, readable over topo and
        // imagery alike. Re-added on every restyle (setStyle rebuilds layers).
        style.addSource(GeoJsonSource(POI_SRC, HelmApp.instance.poi.pois.value))
        style.addLayer(CircleLayer(POI_LAYER, POI_SRC).withProperties(
            // Colour by category so fuel/base waypoints stand out from plain
            // ones: fuel = orange-red, base = cyan, everything else amber.
            PropertyFactory.circleColor(Expression.match(
                Expression.get("kind"),
                Expression.literal(PoiStore.KIND_FUEL), Expression.rgb(255, 106, 61),
                Expression.literal(PoiStore.KIND_BASE), Expression.rgb(127, 215, 232),
                Expression.rgb(255, 180, 84))),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ))
        // LoRa tracker nodes: green dots, distinct from amber waypoints.
        style.addSource(GeoJsonSource(LORA_SRC,
            loraGeoJson(HelmApp.instance.lora.states.value
                .takeIf { HelmApp.instance.lora.config.value.enabled } ?: emptyList())))
        style.addLayer(CircleLayer(LORA_LAYER, LORA_SRC).withProperties(
            PropertyFactory.circleColor("#4ADE80"),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#0B0F14"),
            PropertyFactory.circleStrokeWidth(2f),
        ))
        if (hasLocationPermission(ctx)) enableLocation(ctx, map, style)
        updateRangeRing(map)
    }
}

private const val EARTH_M = 6_371_000.0
private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

/**
 * Recompute the round-trip range ring from live fuel + trip MPG and re-centre
 * it on the vehicle. Reads the repositories directly (one-shot, like the other
 * layer updates). Clears to an empty collection when there's no fix or no
 * trustworthy MPG yet, so the map is simply unmarked rather than wrong.
 */
private fun updateRangeRing(map: MapLibreMap?) {
    val src = map?.style?.getSourceAs<GeoJsonSource>(RANGE_SRC) ?: return
    val v = HelmApp.instance.can.state.value
    val g = HelmApp.instance.gps.state.value
    val radiusMi = VehicleEnergy.roundTripRadiusMi(v.fuelLevelPct, v.avgMpg)
    src.setGeoJson(
        if (g.hasFix && radiusMi != null)
            rangeRingGeoJson(g.lat, g.lon, radiusMi * 1609.344)
        else EMPTY_FC,
    )
}

/** A closed geodesic circle of [radiusM] around a point, as a GeoJSON polygon. */
private fun rangeRingGeoJson(lat: Double, lon: Double, radiusM: Double): String {
    if (radiusM <= 0.0) return EMPTY_FC
    val phi1 = Math.toRadians(lat)
    val lam1 = Math.toRadians(lon)
    val d = radiusM / EARTH_M                 // angular distance
    val n = 90
    val ring = StringBuilder()
    for (i in 0..n) {
        val th = Math.toRadians(360.0 * i / n)
        val phi2 = asin(sin(phi1) * cos(d) + cos(phi1) * sin(d) * cos(th))
        val lam2 = lam1 + atan2(sin(th) * sin(d) * cos(phi1), cos(d) - sin(phi1) * sin(phi2))
        if (i > 0) ring.append(',')
        ring.append('[').append(Math.toDegrees(lam2)).append(',')
            .append(Math.toDegrees(phi2)).append(']')
    }
    return """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$ring]]}}"""
}

/** GeoJSON FeatureCollection string for the LoRa nodes that have a fix. */
private fun loraGeoJson(states: List<com.xterra.helm.lora.LoraNodeState>): String {
    val feats = states.mapNotNull { s ->
        val p = s.pos ?: return@mapNotNull null
        val label = s.label.replace("\\", "\\\\").replace("\"", "\\\"")
        """{"type":"Feature","properties":{"label":"$label"},""" +
            """"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$feats]}"""
}

private const val RANGE_SRC = "helm-range"
private const val RANGE_RING = "helm-range-ring"
private const val POI_SRC = "helm-pois"
private const val POI_LAYER = "helm-poi-dots"
private const val LORA_SRC = "helm-lora"
private const val LORA_LAYER = "helm-lora-dots"

/**
 * Flat bounds for the framed area, from the camera center — visibleRegion is
 * unreliable under 55° tilt (extends to the horizon → 0 tiles enumerated).
 * Half-span shrinks with zoom (so zooming out frames a larger area to cache),
 * lon widened for the landscape aspect.
 */
private fun viewBounds(map: MapLibreMap): LatLngBounds? {
    val center = map.cameraPosition.target ?: return null
    val d = 180.0 / Math.pow(2.0, map.cameraPosition.zoom)
    return LatLngBounds.Builder()
        .include(LatLng(center.latitude + d, center.longitude + d * 1.7))
        .include(LatLng(center.latitude - d, center.longitude - d * 1.7))
        .build()
}

private fun xTile(lon: Double, z: Int): Int =
    Math.floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()

private fun yTile(lat: Double, z: Int): Int =
    Math.floor((1.0 - kotlin.math.asinh(Math.tan(Math.toRadians(lat))) / Math.PI) / 2.0 * (1 shl z)).toInt()

/** XYZ tile count covering [b] over z=[minZ]..[maxZ] inclusive (download size proxy). */
private fun estimateTiles(b: LatLngBounds, minZ: Int, maxZ: Int): Long {
    var total = 0L
    for (z in minZ..maxZ) {
        val nx = (xTile(b.longitudeEast, z) - xTile(b.longitudeWest, z) + 1).coerceAtLeast(1)
        val ny = (yTile(b.latitudeSouth, z) - yTile(b.latitudeNorth, z) + 1).coerceAtLeast(1)
        total += nx.toLong() * ny.toLong()
    }
    return total
}

/** Rough bytes for [tiles] USGS topo rasters (~18 KB avg — measured on-device). */
private fun estBytes(tiles: Long): Long = tiles * 18L * 1024L

/**
 * Tiles MapLibre actually downloads for map-zoom [minMapZ]..[maxMapZ] over [b].
 * The USGS source is 256 px; in MapLibre's 512 px tile grid that means source
 * tiles are fetched one zoom above the map zoom, so a "z6–13" region really
 * pulls source z7–14 (~4× the naive count). Model that shift so the size
 * estimate matches reality (measured: a z6–13 Western-Oregon region ≈ 50 k
 * tiles / ~870 MB, not the ~13 k a naive z6–13 count predicts).
 */
private fun estimateRegionTiles(b: LatLngBounds, minMapZ: Int, maxMapZ: Int): Long =
    estimateTiles(b, minMapZ + 1, (maxMapZ + 1).coerceAtMost(16))

/**
 * Download an explicit [bounds] across z=[minZ]..[maxZ] for offline use — the
 * zoom range is chosen by the caller (detail selector), NOT tied to the camera,
 * so a large framed area can be cached at street zoom. Uses the raster-only
 * style served over loopback HTTP (the offline downloader won't load a file://
 * style). Progress arrives on a background thread → [onProgress].
 */
private fun downloadRegion(
    ctx: Context, base: MapStyles.Base, bounds: LatLngBounds, minZ: Int, maxZ: Int,
    onProgress: (String?) -> Unit,
) {
    val styleUrl = StyleServer.serve(MapStyles.offlineStyle(base))
    val def = OfflineTilePyramidRegionDefinition(
        styleUrl, bounds, minZ.toDouble(), maxZ.toDouble(), ctx.resources.displayMetrics.density)
    val c = bounds.center
    val regionName = "%s z%d-%d · %.2f,%.2f · %s".format(
        base.label, minZ, maxZ, c.latitude, c.longitude,
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
    onProgress("starting…")
    OfflineManager.getInstance(ctx).createOfflineRegion(
        def, """{"name":"$regionName"}""".toByteArray(),
        object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region: OfflineRegion) {
                region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(st: OfflineRegionStatus) {
                        val mb = st.completedResourceSize / 1_048_576
                        if (st.isComplete) android.util.Log.i("Helm",
                            "OFFLINE done: ${st.completedResourceCount} tiles, $mb MB")
                        onProgress(
                            if (st.isComplete) "✓ ${st.completedResourceCount} tiles · $mb MB"
                            else "${st.completedResourceCount} tiles · $mb MB…")
                    }
                    override fun onError(e: OfflineRegionError) {
                        android.util.Log.w("Helm", "OFFLINE err ${e.reason}: ${e.message}")
                        onProgress("✗ ${e.reason}")
                    }
                    override fun mapboxTileCountLimitExceeded(limit: Long) =
                        onProgress("✗ over $limit-tile limit")
                })
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) = onProgress("✗ $error")
        })
}

@SuppressLint("MissingPermission") // permission checked before this is called
private fun enableLocation(ctx: Context, map: MapLibreMap, style: Style) {
    runCatching {
        val lc = map.locationComponent
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(ctx, style)
                .useDefaultLocationEngine(false)   // we feed it from the USB GPS
                .build())
        lc.isLocationComponentEnabled = true
        // Puck heading from GPS course (head unit's magnetometer is fixed to
        // the dash, so COMPASS would always point one way). Camera mode is set
        // by applyCameraMode once the first fix lands.
        lc.renderMode = RenderMode.GPS
    }
}

private fun hasLocationPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Offline-cache chooser for the framed map area. Estimates tiles + size for a
 * few detail levels (zoom ceilings) over the current view bounds and shows
 * each against the free space on /data, so a big region can be cached at street
 * zoom without silently blowing the disk or the tile-count limit. Picking a
 * level that fits starts the download; over-budget levels are shown but locked.
 */
@Composable
private fun BoxScope.CachePanel(
    map: MapLibreMap?, base: MapStyles.Base,
    onStart: (LatLngBounds, Int, Int) -> Unit, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val bounds = remember(map) { map?.let { viewBounds(it) } }
    val freeBytes = remember { android.os.StatFs(ctx.filesDir.path).availableBytes }
    Column(
        Modifier.align(Alignment.TopStart).padding(10.dp)
            .widthIn(min = 300.dp, max = 400.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(HelmColors.Panel.copy(alpha = 0.96f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CACHE FRAMED AREA", style = MaterialTheme.typography.titleSmall,
                color = HelmColors.Amber, modifier = Modifier.weight(1f))
            Text("✕", color = HelmColors.TextDim,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable { onClose() }.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Text("free on /data ${freeBytes / 1_048_576} MB · zoom out to frame a wider area",
            style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
        if (bounds == null) {
            Text("map not ready", style = MaterialTheme.typography.bodyMedium, color = HelmColors.TextDim)
        } else {
            // z6 overview → the chosen ceiling. Any tier is downloadable; a tier
            // larger than current free space is flagged (add external storage /
            // clear caches) but NOT blocked — the disk is expandable.
            listOf(Triple("Roads & towns", 12, "highways, town labels"),
                   Triple("Streets", 13, "full street network"),
                   Triple("Full detail", 14, "every street, driveable"),
                   Triple("Max detail", 15, "building level")).forEach { (label, maxZ, note) ->
                val tiles = estimateRegionTiles(bounds, 6, maxZ)
                val bytes = estBytes(tiles)
                val overFree = bytes > freeBytes * 9 / 10
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(HelmColors.Glass, RoundedCornerShape(8.dp))
                        .clickable { onStart(bounds, 6, maxZ) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("$label · z6–$maxZ ($note)",
                            style = MaterialTheme.typography.bodyMedium, color = HelmColors.Text)
                        Text("~%,d tiles · ~%,d MB".format(tiles, bytes / 1_048_576) +
                                if (overFree) " · exceeds free space" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (overFree) HelmColors.Amber else HelmColors.TextDim)
                    }
                    Text("▶", style = MaterialTheme.typography.bodyMedium, color = HelmColors.Amber)
                }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerChip(
    text: String, active: Boolean, modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (active) HelmColors.AmberDim.copy(alpha = 0.85f)
                        else HelmColors.Panel.copy(alpha = 0.85f))
            .let {
                if (onLongClick != null)
                    it.combinedClickable(onLongClick = onLongClick) { onClick() }
                else it.clickable { onClick() }
            }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            color = if (active) HelmColors.Amber else HelmColors.Text)
    }
}
