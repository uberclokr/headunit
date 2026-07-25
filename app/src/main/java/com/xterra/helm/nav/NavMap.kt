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
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

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
    // Lock onto the vehicle once, when the first fix lands after startup.
    var lockedOnce by remember { mutableStateOf(false) }
    // Follow-behind (heading-up, tilted chase cam) vs north-up overview.
    var follow by remember { mutableStateOf(true) }
    // Waypoints (GeoJSON) + the currently tapped one (id to name).
    val pois by HelmApp.instance.poi.pois.collectAsState()
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    // LoRaWAN tracker nodes — shown as green dots when the LNS is enabled.
    val loraStates by HelmApp.instance.lora.states.collectAsState()
    val loraCfg by HelmApp.instance.lora.config.collectAsState()
    // Offline region-download progress text (region management lives in Settings).
    var cacheMsg by remember { mutableStateOf<String?>(null) }
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
    // Enlarge the ambient tile cache to 1 GB so USGS/terrain tiles for
    // everywhere you've driven stay available when Starlink drops — automatic
    // LRU caching, no explicit download needed for areas already viewed.
    remember {
        MapLibre.getInstance(ctx)
        OfflineManager.getInstance(ctx).setMaximumAmbientCacheSize(
            1_073_741_824L, object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {}
                override fun onError(message: String) {}
            })
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

        // Top-left: waypoint counter + offline-cache cluster (the long CACHE
        // chip lives in this corner, away from the map center).
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
            // Download the current view for offline use (frame the area first).
            // Region/graph management lives in the Settings pane.
            LayerChip(cacheMsg ?: "⤓ CACHE THIS VIEW", active = cacheMsg != null) {
                if (cacheMsg == null) mapRef.value?.let { m ->
                    downloadRegion(ctx, m, base) { cacheMsg = it }
                }
            }
        }

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

        // Selected waypoint card: rename inline, quick delete.
        selected?.let { (id, name) ->
            var editName by remember(id) { mutableStateOf(name) }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(HelmColors.Panel.copy(alpha = 0.92f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicTextField(
                    value = editName, onValueChange = { editName = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                        .copy(color = HelmColors.Text),
                    cursorBrush = SolidColor(HelmColors.Amber),
                    modifier = Modifier.widthIn(min = 90.dp, max = 220.dp)
                        .background(HelmColors.Glass, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
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

        // Camera controls, bottom-right: short chips stack up the edge, the
        // wide zoom-preset row sits last, tight in the corner.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LayerChip(if (follow) "⬆ FOLLOW" else "▲ NORTH", active = follow) {
                follow = !follow
            }
            // Re-lock onto the vehicle (tracking drops out when the user pans).
            LayerChip("◎ ME", active = false) {
                mapRef.value?.let { runCatching { applyCameraMode(it, follow) } }
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
            PropertyFactory.circleColor("#FFB454"),
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
    }
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

private const val POI_SRC = "helm-pois"
private const val POI_LAYER = "helm-poi-dots"
private const val LORA_SRC = "helm-lora"
private const val LORA_LAYER = "helm-lora-dots"

/**
 * Download the current viewport for offline use. Frame the area, tap the
 * chip: the visible bounds are cached from the current zoom up ~3 levels
 * (capped at 15) so it's usable when Starlink drops. Uses a file:// copy of
 * the active style since the offline downloader needs a style URL, not inline
 * JSON. Progress arrives on a background thread → [onProgress] (Compose state
 * writes are thread-safe).
 */
private fun downloadRegion(
    ctx: Context, map: MapLibreMap, base: MapStyles.Base, onProgress: (String?) -> Unit,
) {
    // Build a flat bounds from the camera center — visibleRegion.latLngBounds
    // is unreliable under 55° tilt (extends to the horizon → the offline
    // enumerator finds 0 tiles). Half-span shrinks with zoom; lon widened for
    // the landscape aspect.
    val center = map.cameraPosition.target ?: return
    val z = map.cameraPosition.zoom
    val d = 180.0 / Math.pow(2.0, z)
    val bounds = LatLngBounds.Builder()
        .include(LatLng(center.latitude + d, center.longitude + d * 1.7))
        .include(LatLng(center.latitude - d, center.longitude - d * 1.7))
        .build()
    val minZ = z.coerceIn(1.0, 15.0)
    val maxZ = (z + 3.0).coerceAtMost(15.0)
    // Serve the raster-only style over loopback HTTP — the offline downloader
    // won't load a file:// style (stalls at req=1/done=0).
    val styleUrl = StyleServer.serve(MapStyles.offlineStyle(base))
    val def = OfflineTilePyramidRegionDefinition(
        styleUrl, bounds, minZ, maxZ, ctx.resources.displayMetrics.density)
    // Human-readable name so the REGIONS manager can tell downloads apart.
    val regionName = "%s %.3f,%.3f z%d-%d · %s".format(
        base.label, center.latitude, center.longitude, minZ.toInt(), maxZ.toInt(),
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date()))
    onProgress("caching 0%")
    OfflineManager.getInstance(ctx).createOfflineRegion(
        def, """{"name":"$regionName"}""".toByteArray(),
        object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region: OfflineRegion) {
                region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(st: OfflineRegionStatus) {
                        if (st.isComplete) android.util.Log.i("Helm",
                            "OFFLINE done: ${st.completedResourceCount} tiles, " +
                            "${st.completedResourceSize / 1024} KB")
                        onProgress(
                            if (st.isComplete)
                                "✓ ${st.completedResourceCount} tiles · ${st.completedResourceSize / 1_048_576} MB"
                            else "caching ${st.completedResourceCount} tiles…")
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
