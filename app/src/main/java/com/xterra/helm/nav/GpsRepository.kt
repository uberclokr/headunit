package com.xterra.helm.nav

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

data class GpsFix(
    val hasFix: Boolean = false,
    val fixDim: Int = 0,          // 0 none, 2 = 2D, 3 = 3D (from GSA)
    val sats: Int = 0,            // satellites used in solution (GGA)
    val hdop: Float = 0f,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val altM: Double = 0.0,
    val speedMps: Float = 0f,
    val courseDeg: Float = 0f,
    val epochMillis: Long = 0L,
)

/**
 * Drives the USB u-blox GPS directly — no external "GPS driver" app. Android
 * binds no kernel serial node for this receiver (no /dev/ttyACM*), so its
 * NMEA stream is invisible to the location HAL; the usual driver app only
 * exists to read that stream and re-inject it as a mock provider. We read the
 * CDC-ACM endpoint ourselves via usb-serial-for-android, parse NMEA, publish
 * [state] for the map + status bar, and optionally mirror to the system so
 * external apps (Google Maps) get a fix too — see [GpsSystemBridge].
 *
 * NOTE: only one process may own the USB device. The pilablu GPS Connector
 * app must be stopped/uninstalled for Helm to claim it.
 */
class GpsRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parser = NmeaParser()
    private val bridge = GpsSystemBridge(context)

    private val _state = MutableStateFlow(GpsFix())
    val state: StateFlow<GpsFix> = _state

    var mirrorToSystem = true          // also feed Android's location provider

    private var port: UsbSerialPort? = null

    fun start() = scope.launch {
        while (isActive) {
            try {
                if (port == null && !open()) { delay(4_000); continue }
                readLoop()
            } catch (e: IOException) {
                Log.i(TAG, "GPS link lost: ${e.message}")
                close(); markDown(); delay(2_000)
            } catch (e: Exception) {
                Log.e(TAG, "GPS loop error", e)
                close(); markDown(); delay(3_000)
            }
        }
    }

    private fun readLoop() {
        val p = port ?: return
        val buf = ByteArray(1024)
        val line = StringBuilder()
        while (scope.isActive) {
            val n = p.read(buf, 1000)
            if (n <= 0) continue
            for (i in 0 until n) {
                val c = buf[i].toInt().toChar()
                if (c == '\n' || c == '\r') {
                    if (line.isNotEmpty()) {
                        parser.feed(line.toString())?.let { fix ->
                            _state.value = fix
                            if (mirrorToSystem) bridge.push(fix)
                        }
                        line.setLength(0)
                    }
                } else if (line.length < 120) line.append(c)
            }
        }
    }

    private fun open(): Boolean {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        // The default prober's CDC driver is hit-or-miss on this receiver, so
        // bind the u-blox VID/PID explicitly to the CDC-ACM driver.
        val table = ProbeTable().apply {
            addProduct(UBLOX_VID, UBLOX_PID, CdcAcmSerialDriver::class.java)
        }
        val driver = UsbSerialProber(table).findAllDrivers(usb).firstOrNull()
        if (driver == null) { Log.i(TAG, "no u-blox GPS on USB"); return false }
        if (!usb.hasPermission(driver.device)) {
            Log.i(TAG, "requesting USB permission for GPS ${driver.device.deviceName}")
            usb.requestPermission(driver.device, PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE))
            return false
        }
        val conn = usb.openDevice(driver.device)
            ?: run { Log.i(TAG, "GPS openDevice failed (held by another app?)"); return false }
        port = driver.ports[0].apply {
            open(conn)
            // USB CDC ignores the line coding, but u-blox NMEA default is 9600.
            setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        }
        Log.i(TAG, "u-blox GPS opened")
        return true
    }

    private fun markDown() { _state.value = GpsFix() }

    fun close() { try { port?.close() } catch (_: Exception) {}; port = null }

    companion object {
        private const val TAG = "Helm"
        const val ACTION_USB_PERMISSION = "com.xterra.helm.USB_PERMISSION"
        const val UBLOX_VID = 0x1546
        const val UBLOX_PID = 0x01a7
    }
}
