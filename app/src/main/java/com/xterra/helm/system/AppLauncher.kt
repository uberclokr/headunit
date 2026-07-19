package com.xterra.helm.system

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect

/**
 * Launch any installed app into a bounded freeform window so it can sit
 * beside Helm panes (e.g. Google Maps left, Helm gauges right).
 *
 * One-time device setup (adb or root shell):
 *   settings put global enable_freeform_support 1
 *   settings put global force_resizable_activities 1
 * then reboot. RK3588 Android 13/14 images handle freeform well.
 */
object AppLauncher {
    fun launchInBounds(context: Context, pkg: String, bounds: Rect) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val opts = ActivityOptions.makeBasic().apply {
            launchBounds = bounds
            // WINDOWING_MODE_FREEFORM = 5 (hidden API constant, stable for years)
            try {
                javaClass.getMethod("setLaunchWindowingMode", Int::class.java)
                    .invoke(this, 5)
            } catch (_: Exception) { /* fall back to bounds only */ }
        }
        context.startActivity(intent, opts.toBundle())
    }

    fun launchFull(context: Context, pkg: String) {
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }
}
