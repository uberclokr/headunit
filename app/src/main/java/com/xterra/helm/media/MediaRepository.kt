package com.xterra.helm.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NowPlaying(
    val app: String = "",
    val packageName: String = "",
    val title: String = "",
    val artist: String = "",
    val art: Bitmap? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * Universal transport control. Any app that posts a MediaSession —
 * YouTube Music, Audible, Spotify, podcast apps, VLC — shows up here.
 * Requires the user to grant Notification Access to Helm once
 * (Settings → Notifications → Device & app notifications → Helm).
 */
class MediaRepository(private val context: Context) {

    private val _sessions = MutableStateFlow<List<NowPlaying>>(emptyList())
    val sessions: StateFlow<List<NowPlaying>> = _sessions

    private val _active = MutableStateFlow(NowPlaying())
    val active: StateFlow<NowPlaying> = _active

    private var controllers: List<MediaController> = emptyList()
    private val msm by lazy {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }
    private val listenerComponent by lazy {
        ComponentName(context, HelmNotificationListener::class.java)
    }

    fun start() {
        refresh()
        try {
            msm.addOnActiveSessionsChangedListener({ refresh() }, listenerComponent)
        } catch (_: SecurityException) { /* notification access not yet granted */ }
    }

    fun refresh() {
        controllers = try {
            msm.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) { emptyList() }

        val list = controllers.map { c ->
            val md = c.metadata
            NowPlaying(
                app = c.packageName.substringAfterLast('.'),
                packageName = c.packageName,
                title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "",
                artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "",
                art = md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART),
                playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                positionMs = c.playbackState?.position ?: 0,
                durationMs = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            )
        }
        _sessions.value = list
        _active.value = list.firstOrNull { it.playing } ?: list.firstOrNull() ?: NowPlaying()
    }

    private fun controllerFor(pkg: String) = controllers.firstOrNull { it.packageName == pkg }

    fun playPause(pkg: String) {
        val c = controllerFor(pkg) ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING)
            c.transportControls.pause() else c.transportControls.play()
        refresh()
    }
    fun next(pkg: String) { controllerFor(pkg)?.transportControls?.skipToNext(); refresh() }
    fun prev(pkg: String) { controllerFor(pkg)?.transportControls?.skipToPrevious(); refresh() }
    fun seek(pkg: String, posMs: Long) { controllerFor(pkg)?.transportControls?.seekTo(posMs) }
}
