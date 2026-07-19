package com.xterra.helm.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xterra.helm.HelmApp
import com.xterra.helm.system.AppLauncher
import com.xterra.helm.ui.theme.HelmColors

/**
 * One transport surface for every audio app on the unit. Album art fills the
 * pane; source chips along the top switch between live sessions (Spotify,
 * YT Music, Audible…). Tapping the source name opens the full app.
 */
@Composable
fun MediaWidget() {
    val repo = HelmApp.instance.media
    val sessions by repo.sessions.collectAsState()
    val active by repo.active.collectAsState()
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    val np = sessions.firstOrNull { it.packageName == selectedPkg } ?: active
    val ctx = LocalContext.current

    // No live session: offer a launcher grid of every installed audio app.
    if (sessions.isEmpty()) { MediaLauncher(); return }

    Box(Modifier.fillMaxSize()) {
        np.art?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(0.35f),
                contentScale = ContentScale.Crop)
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                sessions.forEach { s ->
                    val sel = s.packageName == np.packageName
                    Text(
                        s.app.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sel) HelmColors.Amber else HelmColors.TextDim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(HelmColors.Glass.copy(alpha = 0.7f))
                            .clickable { selectedPkg = s.packageName }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                if (np.packageName.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    // In-app browsing is gated by each app's MediaBrowserService
                    // (all three refuse non-Android-Auto callers), so browse in
                    // the real app — freeform beside Helm.
                    Text("OPEN APP ↗", style = MaterialTheme.typography.labelSmall,
                        color = HelmColors.Cyan,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(HelmColors.Glass.copy(alpha = 0.7f))
                            .clickable { AppLauncher.launchFull(ctx, np.packageName) }
                            .padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(np.title, style = MaterialTheme.typography.headlineMedium,
                color = HelmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(np.artist, style = MaterialTheme.typography.bodyMedium,
                color = HelmColors.TextDim, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                TransportButton(Icons.Filled.SkipPrevious) { repo.prev(np.packageName) }
                TransportButton(
                    if (np.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    big = true,
                ) { repo.playPause(np.packageName) }
                TransportButton(Icons.Filled.SkipNext) { repo.next(np.packageName) }
            }
        }
    }
}

/**
 * Shown when nothing is playing: a tap-to-open tile for each installed audio
 * app (dynamically detected via MediaBrowserService). Once the chosen app
 * starts playing, its MediaSession appears and MediaWidget flips back to the
 * transport view automatically.
 */
@Composable
private fun MediaLauncher() {
    val ctx = LocalContext.current
    val apps = remember { HelmApp.instance.media.installedMediaApps() }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("NOTHING PLAYING", style = MaterialTheme.typography.titleMedium,
            color = HelmColors.Amber)
        Text("tap an app to pick something",
            style = MaterialTheme.typography.labelSmall, color = HelmColors.TextDim)
        Spacer(Modifier.height(16.dp))
        if (apps.isEmpty()) {
            Text("no media apps installed", color = HelmColors.TextDim)
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(apps, key = { it.pkg }) { app ->
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(HelmColors.Panel.copy(alpha = 0.7f))
                        .clickable { AppLauncher.launchFull(ctx, app.pkg) }
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    app.icon?.let {
                        Image(it.asImageBitmap(), null, Modifier.size(52.dp))
                    } ?: Icon(Icons.Filled.MusicNote, null, Modifier.size(52.dp),
                        tint = HelmColors.TextDim)
                    Spacer(Modifier.height(8.dp))
                    Text(app.label, style = MaterialTheme.typography.labelSmall,
                        color = HelmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    big: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(if (big) 64.dp else 48.dp).clip(CircleShape)
            .background(if (big) HelmColors.Amber else HelmColors.Panel.copy(alpha = 0.85f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (big) HelmColors.Glass else HelmColors.Text,
            modifier = Modifier.size(if (big) 34.dp else 26.dp))
    }
}


