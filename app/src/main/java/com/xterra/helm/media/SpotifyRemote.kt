package com.xterra.helm.media

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SpotifyState(
    val connected: Boolean = false,
    val track: String = "",
    val artist: String = "",
    val paused: Boolean = true,
    val imageUri: String? = null,
)

/**
 * Rich Spotify control via App Remote SDK (playlists, search-to-play, art),
 * layered on top of the generic MediaSession transport. Requires:
 *  - Spotify app installed and signed in on the head unit
 *  - CLIENT_ID + redirect URI registered at developer.spotify.com
 *  - spotify-app-remote AAR in app/libs
 */
class SpotifyRemote(private val context: Context) {
    private var remote: SpotifyAppRemote? = null
    private val _state = MutableStateFlow(SpotifyState())
    val state: StateFlow<SpotifyState> = _state

    fun connect() {
        val params = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(r: SpotifyAppRemote) {
                remote = r
                _state.value = _state.value.copy(connected = true)
                r.playerApi.subscribeToPlayerState().setEventCallback { ps ->
                    _state.value = SpotifyState(
                        connected = true,
                        track = ps.track?.name ?: "",
                        artist = ps.track?.artist?.name ?: "",
                        paused = ps.isPaused,
                        imageUri = ps.track?.imageUri?.raw,
                    )
                }
            }
            override fun onFailure(t: Throwable) {
                _state.value = SpotifyState(connected = false)
            }
        })
    }

    fun playPause() = remote?.playerApi?.let {
        if (_state.value.paused) it.resume() else it.pause()
    }
    fun next() { remote?.playerApi?.skipNext() }
    fun prev() { remote?.playerApi?.skipPrevious() }
    fun playUri(uri: String) { remote?.playerApi?.play(uri) } // e.g. spotify:playlist:...
    fun disconnect() { remote?.let { SpotifyAppRemote.disconnect(it) }; remote = null }

    companion object {
        const val CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID"
        const val REDIRECT_URI = "helm://spotify-callback"
    }
}
