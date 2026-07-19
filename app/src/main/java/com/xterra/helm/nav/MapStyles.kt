package com.xterra.helm.nav

/**
 * MapLibre style definitions for backcountry navigation. All sources are free
 * and keyless:
 *  - USGS The National Map (basemap.nationalmap.gov) — the digital USGS quad,
 *    which already carries forest roads, 4x4 trails, contours and relief.
 *  - AWS Open Data terrarium DEM (elevation-tiles-prod) for the 3D terrain
 *    mesh that raster layers drape over, plus hillshade.
 *
 * Base layers are XYZ raster (ArcGIS MapServer tiles use {z}/{y}/{x} order).
 * Kept as JSON strings so they can be swapped/extended without touching the
 * view code, and so a future settings screen can add custom tile URLs.
 */
object MapStyles {

    enum class Base(val label: String, private val urlTemplate: String, val maxZoom: Int) {
        TOPO("TOPO",
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}", 16),
        IMAGERY_TOPO("IMG+TOPO",
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/{z}/{y}/{x}", 16),
        SATELLITE("SAT",
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}", 16);

        val url get() = urlTemplate
    }

    private const val TERRAIN_DEM =
        "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    /**
     * Raster-only style for OFFLINE region downloads: just the USGS base, no
     * terrain-DEM / hillshade. The offline resource counter chokes on the
     * raster-dem source (reports 0 tiles), and downloading the DEM would
     * balloon the region — you want the topo tiles offline, not the 3D mesh.
     */
    fun offlineStyle(base: Base): String = """
    {
      "version": 8,
      "name": "Helm Offline",
      "sources": {
        "base": {
          "type": "raster",
          "tiles": ["${base.url}"],
          "tileSize": 256,
          "maxzoom": ${base.maxZoom}
        }
      },
      "layers": [ { "id": "base", "type": "raster", "source": "base" } ]
    }
    """.trimIndent()

    /**
     * Full style: raster base draped over a 3D terrain mesh with a subtle
     * hillshade for depth. [exaggeration] emphasizes relief for at-a-glance
     * terrain reading while driving.
     */
    fun style(base: Base, exaggeration: Double = 1.3): String = """
    {
      "version": 8,
      "name": "Helm Backcountry",
      "sources": {
        "base": {
          "type": "raster",
          "tiles": ["${base.url}"],
          "tileSize": 256,
          "maxzoom": ${base.maxZoom},
          "attribution": "USGS The National Map"
        },
        "dem": {
          "type": "raster-dem",
          "tiles": ["$TERRAIN_DEM"],
          "tileSize": 256,
          "encoding": "terrarium",
          "maxzoom": 15
        }
      },
      "layers": [
        { "id": "base", "type": "raster", "source": "base" },
        { "id": "hillshade", "type": "hillshade", "source": "dem",
          "paint": { "hillshade-exaggeration": 0.25,
                     "hillshade-shadow-color": "#0b0f14" } }
      ],
      "terrain": { "source": "dem", "exaggeration": $exaggeration }
    }
    """.trimIndent()
}
