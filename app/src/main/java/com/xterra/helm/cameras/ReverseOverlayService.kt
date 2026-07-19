package com.xterra.helm.cameras

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.xterra.helm.HelmApp
import com.xterra.helm.ui.theme.HelmTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Watches VehicleState.reverse and throws the rear camera full-screen over
 * whatever is on screen (Maps, Spotify, anything) the moment reverse engages,
 * then removes it when the truck comes out of gear. Uses SYSTEM_ALERT_WINDOW
 * so it works even when Helm isn't foreground. Grant "Display over other
 * apps" to Helm once.
 */
class ReverseOverlayService : LifecycleService(), SavedStateRegistryOwner {

    private val ssrController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssrController.savedStateRegistry

    private var overlay: ComposeView? = null
    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    override fun onCreate() {
        super.onCreate()
        ssrController.performRestore(null)
        startForeground(NOTIF_ID, buildNotification())
        lifecycleScope.launch {
            HelmApp.instance.can.state.collectLatest { st ->
                if (st.reverse) show() else hide()
            }
        }
    }

    private fun show() {
        if (overlay != null) return
        val cam = HelmApp.instance.cameras.byId("rear") ?: return
        // One live source on the A329S — make sure it's the rear channel
        // before the stream opens, in case another pane switched it away.
        HelmApp.instance.viofo.selectChannel(ViofoLocator.CH_REAR)
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ReverseOverlayService)
            setViewTreeSavedStateRegistryOwner(this@ReverseOverlayService)
            setContent {
                HelmTheme {
                    val settings = HelmApp.instance.settings.state
                        .collectAsState().value
                    RtspView(cam.url, lowLatency = true, mirror = settings.revMirror)
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.CENTER }
        // Never let a WindowManager refusal (revoked overlay perm, races on
        // display state) kill the watch service — log and stay armed.
        runCatching { wm.addView(view, lp); overlay = view }
            .onFailure { android.util.Log.e("Helm", "reverse overlay addView failed", it) }
    }

    private fun hide() {
        overlay?.let { runCatching { wm.removeView(it) } }
        if (overlay != null) {
            // Give the CAM pane back whatever channel it had chosen before
            // reverse forced the rear view.
            val viofo = HelmApp.instance.viofo
            viofo.selectChannel(viofo.paneChannel.value)
        }
        overlay = null
    }

    private fun buildNotification(): Notification {
        val ch = NotificationChannel("helm_rev", "Reverse camera", NotificationManager.IMPORTANCE_MIN)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
        return Notification.Builder(this, "helm_rev")
            .setContentTitle("Helm reverse watch active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }
    override fun onDestroy() { hide(); super.onDestroy() }

    companion object { const val NOTIF_ID = 41 }
}
