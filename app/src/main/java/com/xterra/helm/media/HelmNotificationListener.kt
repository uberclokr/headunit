package com.xterra.helm.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.xterra.helm.HelmApp

/** Exists so MediaSessionManager will hand us active sessions; also nudges
 *  the repo when media notifications change (fast metadata updates). */
class HelmNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        HelmApp.instance.media.refresh()
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        HelmApp.instance.media.refresh()
    }
}
