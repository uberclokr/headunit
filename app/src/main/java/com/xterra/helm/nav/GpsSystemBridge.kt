package com.xterra.helm.nav

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log

/**
 * Mirrors Helm's USB-GPS fix into Android's GPS provider as a test/mock
 * location, so external apps (Google Maps, ATAK) get a fix without the
 * separate GPS-driver app. This is the same mechanism that driver app used —
 * we just do it in-process.
 *
 * Requires Helm to be selected as the mock-location app (Developer options →
 * "Select mock location app" → Helm). Without that, setTestProvider throws
 * SecurityException; we log once and carry on — Helm's own map still works
 * from [GpsRepository.state] regardless.
 */
class GpsSystemBridge(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var active = false
    private var warned = false

    fun push(fix: GpsFix) {
        if (!fix.hasFix) return
        try {
            if (!active) {
                runCatching { lm.removeTestProvider(LocationManager.GPS_PROVIDER) }
                lm.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false, false, false, false, true, true, true,
                    /* powerUsage */ 1, /* accuracy */ 1,
                )
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                active = true
            }
            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = fix.lat
                longitude = fix.lon
                altitude = fix.altM
                accuracy = (fix.hdop * 5f).coerceIn(1f, 50f)
                if (fix.speedMps > 0f) speed = fix.speedMps
                if (fix.courseDeg > 0f) bearing = fix.courseDeg
                time = fix.epochMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        } catch (e: SecurityException) {
            if (!warned) {
                Log.w("Helm", "GPS system mirror off: set Helm as mock-location app " +
                    "(Developer options) to feed external apps. ${e.message}")
                warned = true
            }
        } catch (e: Exception) {
            if (!warned) { Log.w("Helm", "GPS system mirror error: ${e.message}"); warned = true }
        }
    }
}
