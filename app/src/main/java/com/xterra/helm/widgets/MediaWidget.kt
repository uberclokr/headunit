package com.xterra.helm.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

    Box(Modifier.fillMaxSize()) {
        np.art?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(0.35f),
                contentScale = ContentScale.Crop)
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                if (sessions.isEmpty()) {
                    Text("No active players — open Spotify / YT Music / Audible",
                        style = MaterialTheme.typography.bodyMedium, color = HelmColors.TextDim,
                        modifier = Modifier.clickable {
                            AppLauncher.launchFull(ctx, "com.spotify.music")
                        })
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


