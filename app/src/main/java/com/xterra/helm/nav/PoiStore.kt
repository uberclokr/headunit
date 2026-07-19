package com.xterra.helm.nav

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.File

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
    fun add(lat: Double, lon: Double, name: String): String {
        val id = "wp-" + System.nanoTime().toString(36)
        val props = JsonObject().apply {
            addProperty("id", id)
            addProperty("name", name)
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
}
