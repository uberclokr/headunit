package com.xterra.helm.widgets

import androidx.compose.runtime.Composable
import com.xterra.helm.nav.NavMap

/**
 * Nav pane — backcountry map on MapLibre (USGS Topo + 3D terrain, free tiles).
 * See [com.xterra.helm.nav.NavMap]. Replaced the Google Maps SDK embed, which
 * needed an API key and had no terrain / USGS / offline story.
 */
@Composable
fun MapWidget() = NavMap()
