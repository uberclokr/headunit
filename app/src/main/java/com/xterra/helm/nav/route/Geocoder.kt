package com.xterra.helm.nav.route

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One geocoder hit: a place with a display label and coordinates. */
data class Place(
    val name: String,
    val label: String,   // secondary line (address / city / state)
    val lat: Double,
    val lon: Double,
    val kind: String,          // osm_value, e.g. "cafe", "fuel", "house" — for an icon later
    val distanceM: Double? = null,   // straight-line from the fix, when biased
)

/**
 * Address / business search via Photon (photon.komoot.io) — the Komoot OSM
 * geocoder. Keyless, no analytics, same OSM data model as our offline router,
 * which keeps the vehicle's traffic quiet. This is the ONE online dependency in
 * the nav path: it only turns a typed query into coordinates; the actual route
 * is still computed offline by GraphHopper. Fails soft (empty list) when
 * Starlink is down — search simply returns nothing rather than hanging the UI.
 *
 * Results are biased toward the current fix (lat/lon) so "coffee" ranks nearby
 * places first. Runs on Dispatchers.IO (callers use it from a coroutine).
 */
object Geocoder {
    private const val TAG = "Helm"
    private const val ENDPOINT = "https://photon.komoot.io/api/"
    private const val UA = "Helm-HeadUnit/0.9 (offline-recon vehicle nav)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Search [query], biased toward ([biasLat],[biasLon]) when a fix is known.
     * Blocking network — call from Dispatchers.IO. Returns [] on any failure.
     *
     * Photon's own proximity weighting is too soft on the public instance —
     * it ranks global name-matches ("Starbucks" in Colorado) over the nearby
     * one. So when we have a fix we over-fetch with the location bias and then
     * re-rank client-side by true distance, which is what a driver wants: the
     * nearest matching place first. A distant named destination still resolves
     * (it's simply the nearest — usually only — match for that name).
     */
    fun search(query: String, biasLat: Double?, biasLon: Double?, limit: Int = 8): List<Place> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val biased = biasLat != null && biasLon != null
        val fetch = if (biased) 25 else limit   // over-fetch when we'll re-rank
        val url = buildString {
            append(ENDPOINT).append("?q=").append(java.net.URLEncoder.encode(q, "UTF-8"))
            append("&limit=").append(fetch)
            if (biased) append("&lat=").append(biasLat).append("&lon=").append(biasLon)
        }
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "geocode HTTP ${resp.code}"); return emptyList() }
                val hits = parse(resp.body?.string().orEmpty())
                if (biased) hits
                    .map { it.copy(distanceM = Navigator.haversine(biasLat!!, biasLon!!, it.lat, it.lon)) }
                    .sortedBy { it.distanceM!! }
                    .take(limit)
                else hits.take(limit)
            }
        }.getOrElse { Log.w(TAG, "geocode failed: ${it.message}"); emptyList() }
    }

    /** Photon returns a GeoJSON FeatureCollection ([lon,lat] geometry). */
    private fun parse(body: String): List<Place> {
        val feats = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>(feats.length())
        for (i in 0 until feats.length()) {
            val f = feats.optJSONObject(i) ?: continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            val name = p.optString("name").ifBlank {
                listOf(p.optString("housenumber"), p.optString("street"))
                    .filter { it.isNotBlank() }.joinToString(" ")
            }.ifBlank { "(unnamed)" }
            // Secondary line: street (when a name led), then city/state.
            val detail = buildList {
                if (p.optString("name").isNotBlank()) {
                    val addr = listOf(p.optString("housenumber"), p.optString("street"))
                        .filter { it.isNotBlank() }.joinToString(" ")
                    if (addr.isNotBlank()) add(addr)
                }
                p.optString("city").takeIf { it.isNotBlank() }?.let { add(it) }
                p.optString("state").takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(", ")
            out.add(Place(name, detail, lat, lon, p.optString("osm_value")))
        }
        return out
    }
}
