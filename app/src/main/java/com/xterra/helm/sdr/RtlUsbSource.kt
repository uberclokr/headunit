package com.xterra.helm.sdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * In-app RTL2832U driver: UsbManager grants the fd, the vendored librtlsdr
 * (libhelmrtl.so) drives the dongle through it. No external driver app, no
 * root, no rtl_tcp hop for the local dongle.
 */
class RtlUsbSource(private val context: Context) : IqSource {
    private var dev = 0L
    private var conn: UsbDeviceConnection? = null

    override var sampleRate = 1_024_000
        private set
    override val connected get() = dev != 0L

    override suspend fun open() {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val dongle = findDongle(um) ?: error("no RTL-SDR dongle on USB")
        if (!um.hasPermission(dongle) && !requestPermission(um, dongle))
            error("USB permission denied for ${dongle.deviceName}")
        val c = um.openDevice(dongle) ?: error("openDevice(${dongle.deviceName}) failed")
        val d = RtlSdrNative.open(c.fileDescriptor)
        if (d == 0L) { c.close(); error("librtlsdr rejected ${dongle.deviceName}") }
        conn = c; dev = d
        RtlSdrNative.setSampleRate(d, sampleRate)
        RtlSdrNative.setTunerGainMode(d, 0)  // tuner AGC
        RtlSdrNative.setAgcMode(d, 1)        // RTL2832 digital AGC
        RtlSdrNative.resetBuffer(d)          // mandatory before first read
    }

    override fun setFrequency(hz: Long) {
        if (dev != 0L) RtlSdrNative.setFrequency(dev, hz)
    }

    override fun setDirectSampling(mode: Int) {
        if (dev != 0L) RtlSdrNative.setDirectSampling(dev, mode)
    }

    override fun readFully(buf: ByteArray, n: Int): Boolean {
        val d = dev.takeIf { it != 0L } ?: return false
        var off = 0
        while (off < n) {
            val r = RtlSdrNative.readSync(d, buf, off, n - off)
            if (r <= 0) return false
            off += r
        }
        return true
    }

    override fun close() {
        // librtlsdr must release the interface before the fd goes away.
        if (dev != 0L) { RtlSdrNative.close(dev); dev = 0L }
        conn?.close(); conn = null
    }

    private suspend fun requestPermission(um: UsbManager, device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val action = "com.xterra.helm.USB_PERMISSION"
            val recv = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    context.unregisterReceiver(this)
                    if (cont.isActive) cont.resume(
                        i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            context.registerReceiver(recv, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            um.requestPermission(device, PendingIntent.getBroadcast(
                context, 0, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE))
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(recv) } }
        }

    companion object {
        // Realtek default + most-common alternate PID; extend if a dongle
        // shows up in `lsusb` with something else.
        private val IDS = setOf(0x0bda to 0x2838, 0x0bda to 0x2832)

        private fun findDongle(um: UsbManager): UsbDevice? =
            um.deviceList.values.firstOrNull { (it.vendorId to it.productId) in IDS }

        fun donglePresent(context: Context): Boolean =
            findDongle(context.getSystemService(Context.USB_SERVICE) as UsbManager) != null
    }
}
