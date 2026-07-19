package com.xterra.helm.cameras

import android.graphics.SurfaceTexture
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xterra.helm.ui.theme.HelmColors
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.atomic.AtomicLong

/**
 * RTSP renderer on LibVLC. Chosen over Media3's RTSP module for the
 * vehicle cams because network-caching can be pushed to ~150 ms, which
 * matters for a backup camera. For non-critical panes bump caching up
 * for stability.
 *
 * Playback is driven from surface callbacks: the surface only exists after
 * window-attach, and attaching the vout earlier renders black forever.
 *
 * [mirror] renders through a TextureView with scaleX = -1 (true rear-view
 * mirror image). A SurfaceView can't do this — its buffer bypasses View
 * transforms — and TextureView costs one GPU copy, which the RK3588 absorbs.
 *
 * Stall watchdog: LibVLC keeps a wedged RTSP session "playing" forever when
 * the camera reboots or WiFi hiccups mid-stream. TimeChanged events feed
 * [lastFrameMs]; no progress for 5 s (or EncounteredError) → tear the media
 * down and re-open. The reconnect banner shows while that's happening.
 */
@Composable
fun RtspView(
    url: String, lowLatency: Boolean,
    modifier: Modifier = Modifier, mirror: Boolean = false,
) {
    val context = LocalContext.current
    val libVlc = remember {
        LibVLC(context, arrayListOf(
            "--rtsp-tcp",
            "--network-caching=${if (lowLatency) 150 else 600}",
            "--clock-jitter=0", "--clock-synchro=0",
            "--drop-late-frames", "--skip-frames",
        ))
    }
    val curUrl by rememberUpdatedState(url)
    val lastFrameMs = remember { AtomicLong(0) }
    var attached by remember { mutableStateOf(false) }
    var stalled by remember { mutableStateOf(false) }
    val player = remember {
        MediaPlayer(libVlc).apply {
            setEventListener { ev ->
                when (ev.type) {
                    MediaPlayer.Event.TimeChanged ->
                        lastFrameMs.set(System.currentTimeMillis())
                    MediaPlayer.Event.EncounteredError -> {
                        Log.w("Helm", "RTSP error: $curUrl")
                        lastFrameMs.set(1)   // ancient → watchdog restarts next tick
                    }
                    MediaPlayer.Event.Playing -> Log.i("Helm", "RTSP playing: $curUrl")
                }
            }
        }
    }

    fun startPlayback() {
        runCatching {
            player.stop()
            player.media?.release()
            player.media = Media(libVlc, Uri.parse(curUrl)).apply {
                setHWDecoderEnabled(true, false)
            }
            player.play()
            lastFrameMs.set(System.currentTimeMillis())
        }.onFailure { Log.w("Helm", "RTSP start failed: ${it.message}") }
    }

    // Watchdog: restart a session that stopped delivering frames.
    LaunchedEffect(url) {
        while (true) {
            delay(2_000)
            if (!attached) continue
            val silentMs = System.currentTimeMillis() - lastFrameMs.get()
            if (lastFrameMs.get() > 0 && silentMs > 5_000) {
                Log.i("Helm", "RTSP stalled ${silentMs}ms — reconnecting: $curUrl")
                stalled = true
                startPlayback()
            } else if (silentMs < 2_500) stalled = false
        }
    }

    Box(modifier.fillMaxSize()) {
        key(url, mirror) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    if (mirror) TextureView(ctx).also { tv ->
                        tv.scaleX = -1f
                        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: SurfaceTexture, w: Int, h: Int,
                            ) {
                                player.vlcVout.setVideoView(tv)
                                player.vlcVout.attachViews()
                                player.vlcVout.setWindowSize(w, h)
                                attached = true
                                startPlayback()
                            }
                            override fun onSurfaceTextureSizeChanged(
                                st: SurfaceTexture, w: Int, h: Int,
                            ) = player.vlcVout.setWindowSize(w, h)
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                attached = false
                                player.stop()
                                player.vlcVout.detachViews()
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    } else SurfaceView(ctx).also { sv ->
                        sv.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                player.vlcVout.setVideoView(sv)
                                player.vlcVout.attachViews()
                                attached = true
                                startPlayback()
                            }
                            override fun surfaceChanged(
                                h: SurfaceHolder, fmt: Int, w: Int, hh: Int,
                            ) = player.vlcVout.setWindowSize(w, hh)
                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                attached = false
                                player.stop()
                                player.vlcVout.detachViews()
                            }
                        })
                    }
                },
            )
        }
        if (stalled) Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(HelmColors.Panel.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text("stream stalled — reconnecting…",
                style = MaterialTheme.typography.labelSmall, color = HelmColors.Alert)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release(); libVlc.release() }
    }
}
