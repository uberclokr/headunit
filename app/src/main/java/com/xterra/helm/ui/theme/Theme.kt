package com.xterra.helm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** User-facing theme selection; AUTO follows day/night from solar elevation. */
enum class ThemeMode { AUTO, DAY, NIGHT }

/**
 * Global theme state. [mode] is the user's dock-chip choice; [autoIsDay] is
 * fed by DashboardShell from GPS solar elevation. Both are Compose state, so
 * every HelmColors read recomposes when they flip.
 */
object HelmThemeState {
    var mode by mutableStateOf(ThemeMode.AUTO)
    var autoIsDay by mutableStateOf(false)
    val isDay: Boolean get() = when (mode) {
        ThemeMode.AUTO -> autoIsDay
        ThemeMode.DAY -> true
        ThemeMode.NIGHT -> false
    }
    fun cycle() { mode = ThemeMode.entries[(mode.ordinal + 1) % ThemeMode.entries.size] }
}

/**
 * Helm palette — "instrument glass".
 * Night: deep blue-black glass, phosphor-amber primary (night-vision safe),
 * glacier cyan live data, muted signal-red alerts.
 * Day: white high-contrast for direct sunlight — same semantic slots, darker
 * saturated inks so amber/cyan/alert stay distinguishable on white.
 */
object HelmColors {
    private val d get() = HelmThemeState.isDay
    val Glass      get() = if (d) Color(0xFFF2F4F7) else Color(0xFF0B0F14)
    val Panel      get() = if (d) Color(0xFFFFFFFF) else Color(0xFF11161E)
    val PanelEdge  get() = if (d) Color(0xFFB9C2CC) else Color(0xFF1C2530)
    val Amber      get() = if (d) Color(0xFF9A5B00) else Color(0xFFFFB454)
    val AmberDim   get() = if (d) Color(0xFFD9B26E) else Color(0xFF8A6430)
    val Cyan       get() = if (d) Color(0xFF00647E) else Color(0xFF7FD7E8)
    val Text       get() = if (d) Color(0xFF10161C) else Color(0xFFE7EDF3)
    val TextDim    get() = if (d) Color(0xFF3F4C59) else Color(0xFF8A97A5)
    val Alert      get() = if (d) Color(0xFFB3261E) else Color(0xFFE8604C)
    val Ok         get() = if (d) Color(0xFF176B36) else Color(0xFF7BC98F)
}

@Composable
fun HelmTheme(content: @Composable () -> Unit) {
    // Built per-composition (not a top-level val) so a theme flip re-derives
    // the Material scheme along with every direct HelmColors read.
    val scheme = if (HelmThemeState.isDay) lightColorScheme(
        primary = HelmColors.Amber, onPrimary = HelmColors.Panel,
        secondary = HelmColors.Cyan, background = HelmColors.Glass,
        surface = HelmColors.Panel, onBackground = HelmColors.Text,
        onSurface = HelmColors.Text, error = HelmColors.Alert,
        outline = HelmColors.PanelEdge,
    ) else darkColorScheme(
        primary = HelmColors.Amber, onPrimary = HelmColors.Glass,
        secondary = HelmColors.Cyan, background = HelmColors.Glass,
        surface = HelmColors.Panel, onBackground = HelmColors.Text,
        onSurface = HelmColors.Text, error = HelmColors.Alert,
        outline = HelmColors.PanelEdge,
    )
    MaterialTheme(colorScheme = scheme, typography = HelmType, content = content)
}
