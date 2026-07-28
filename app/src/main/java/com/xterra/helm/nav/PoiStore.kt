package com.xterra.helm.nav

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.xterra.helm.nav.route.Navigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.File

/** Nearest matching waypoint to a point, with great-circle distance (metres). */
data class NearestPoi(
    val name: String, val lat: Double, val lon: Double,
    val distanceM: Double, val kind: String,
)

/**
 * Waypoint store, persisted as a plain GeoJSON FeatureCollection on disk
 * (filesDir/pois.geojson) — the same format QGIS, Gaia, and ATAK read, and
 * exactly what backs up to Unraid later. Points carry {id, name, created}.
 *
 * Held as a [FeatureCollection] StateFlow so the map layer can bind directly
 * (MapLibre GeoJsonSource takes one as-is) with no conversion.
 */
class PoiStore(context: Context) {
    private val file = File(context.filesDir, "pois.geojson")

    /** The on-disk GeoJSON, for backup/sync. */
    val geojsonFile: File get() = file

    private val _pois = MutableStateFlow(load())
    val pois: StateFlow<FeatureCollection> = _pois

    fun count(): Int = _pois.value.features()?.size ?: 0

    /** Drop a waypoint; returns its id. Persists immediately. */
    fun add(lat: Double, lon: Double, name: String, kind: String = KIND_WP): String {
        val id = "wp-" + System.nanoTime().toString(36)
        val props = JsonObject().apply {
            addProperty("id", id)
            addProperty("name", name)
            addProperty("kind", kind)
            addProperty("created", System.currentTimeMillis())
        }
        val feat = Feature.fromGeometry(Point.fromLngLat(lon, lat), props)
        _pois.value = FeatureCollection.fromFeatures(
            (_pois.value.features().orEmpty()) + feat)
        save()
        return id
    }

    fun rename(id: String, name: String) = mutate(id) { f ->
        f.addStringProperty("name", name); f
    }

    /** Re-tag a waypoint (wp / fuel / base) — drives the reserve alert + map color. */
    fun setKind(id: String, kind: String) = mutate(id) { f ->
        f.addStringProperty("kind", kind); f
    }

    fun kindOf(id: String): String =
        _pois.value.features().orEmpty()
            .firstOrNull { it.getStringProperty("id") == id }
            ?.getStringProperty("kind") ?: KIND_WP

    /**
     * Nearest waypoint of any of [kinds] to (lat,lon), by great-circle
     * distance. Used to answer "can I still reach fuel/a base?" — null when no
     * matching waypoint exists.
     */
    fun nearest(lat: Double, lon: Double, kinds: Set<String>): NearestPoi? =
        _pois.value.features().orEmpty().mapNotNull { f ->
            val k = f.getStringProperty("kind") ?: KIND_WP
            if (k !in kinds) return@mapNotNull null
            val p = f.geometry() as? Point ?: return@mapNotNull null
            NearestPoi(
                f.getStringProperty("name") ?: "", p.latitude(), p.longitude(),
                Navigator.haversine(lat, lon, p.latitude(), p.longitude()), k)
        }.minByOrNull { it.distanceM }

    fun remove(id: String) {
        _pois.value = FeatureCollection.fromFeatures(
            _pois.value.features().orEmpty().filter { it.getStringProperty("id") != id })
        save()
    }

    private inline fun mutate(id: String, transform: (Feature) -> Feature) {
        val list = _pois.value.features().orEmpty().map {
            if (it.getStringProperty("id") == id) transform(it) else it
        }
        _pois.value = FeatureCollection.fromFeatures(list)
        save()
    }

    private fun load(): FeatureCollection = runCatching {
        if (file.exists()) FeatureCollection.fromJson(file.readText())
        else FeatureCollection.fromFeatures(emptyList<Feature>())
    }.getOrElse {
        Log.w("Helm", "POI load failed: ${it.message}")
        FeatureCollection.fromFeatures(emptyList<Feature>())
    }

    private fun save() = runCatching { file.writeText(_pois.value.toJson()) }
        .onFailure { Log.w("Helm", "POI save failed: ${it.message}") }

    companion object {
        const val KIND_WP = "wp"
        const val KIND_FUEL = "fuel"
        const val KIND_BASE = "base"
        /** Waypoint kinds that count as a place to refuel / resupply. */
        val REFUEL_KINDS = setOf(KIND_FUEL, KIND_BASE)
    }
}
