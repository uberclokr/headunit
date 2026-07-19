package com.xterra.helm.can

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException

/**
 * Minimal, robust ELM327 driver over usb-serial-for-android.
 * Polls a fixed PID rotation. Fast PIDs (RPM/speed) every cycle,
 * slow PIDs (temps/fuel/voltage/DTC count) every Nth cycle.
 *
 * Key-off behavior: the dongle feeds from OBD pin 16 — the START battery —
 * so when the ECU stops answering we put the chip in ATLP low-power sleep
 * and only re-probe once a minute, instead of hammering PIDs forever.
 */
class Elm327Manager(
    private val context: Context,
    private val onLink: (Boolean) -> Unit = {},
    private val onSample: (Sample) -> Unit,
) {
    data class Sample(
        val rpm: Int? = null, val speedKmh: Int? = null, val coolantC: Int? = null,
        val intakeC: Int? = null, val throttlePct: Float? = null, val mafGs: Float? = null,
        val fuelPct: Float? = null, val batteryV: Float? = null,
        val dtcCount: Int? = null, val milOn: Boolean? = null,
        val dtcCodes: List<String>? = null,
        val reverse: Boolean? = null,
    )

    private var port: UsbSerialPort? = null

    /**
     * Debug console: commands land here (adb broadcast → VehicleService) and
     * run between poll cycles, results to logcat. `;`-separated commands run
     * back-to-back so header changes (ATSH…) can't corrupt live PID polls.
     * `MON:<ms>` does an ATMA monitor burst for bus captures.
     */
    private val injected = Channel<String>(capacity = 8)
    fun inject(cmds: String) { injected.trySend(cmds) }

    private fun runInjected() {
        while (true) {
            val batch = injected.tryReceive().getOrNull() ?: return
            for (cmd in batch.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                if (cmd.startsWith("MON:")) {
                    val ms = cmd.removePrefix("MON:").toLongOrNull() ?: 400L
                    Log.i(TAG, "INJ ATMA burst ${ms}ms:\n${monitorBurst(ms)}")
                } else {
                    val r = atCommand(cmd, 3000)
                    Log.i(TAG, "INJ $cmd -> ${r?.trim()?.replace("\r", "|") ?: "<silent>"}")
                }
            }
        }
    }

    /** ATMA for [ms], then any-key stop. Returns raw captured frames. */
    private fun monitorBurst(ms: Long): String {
        val p = port ?: return "<no port>"
        val junk = ByteArray(256)
        while (p.read(junk, 10) > 0) { }
        p.write("ATMA\r".toByteArray(), 500)
        val buf = ByteArray(4096); val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            val n = p.read(buf, 50)
            if (n > 0) sb.append(String(buf, 0, n))
        }
        p.write("\r".toByteArray(), 200)   // any char stops monitoring
        Thread.sleep(100)
        while (p.read(junk, 10) > 0) { }
        return sb.toString()
    }

    suspend fun connectAndPoll() {
        while (currentCoroutineContext().isActive) {
            try {
                if (port == null && !open()) { delay(3000); continue }
                if (!initElm()) { standby(); continue }
                onLink(true)
                pollLoop()          // returns when the ECU goes silent
                onLink(false)
            } catch (e: IOException) {
                Log.i(TAG, "ELM link lost: ${e.message}")
                onLink(false); close(); delay(2000)
            } catch (e: Exception) {
                // Anything else would silently kill this coroutine — log it,
                // treat it like a link loss, keep the retry loop alive.
                Log.e(TAG, "ELM loop error", e)
                onLink(false); close(); delay(3000)
            }
        }
    }

    /** 8 Hz PID rotation. Returns once the bus goes quiet (key off). */
    private suspend fun pollLoop() {
        var cycle = 0
        var silent = 0
        while (currentCoroutineContext().isActive) {
            val rpm = pid("010C")?.let { (it[0] * 256 + it[1]) / 4 }
            val spd = pid("010D")?.let { it[0] }
            var s = Sample(rpm = rpm, speedKmh = spd)
            if (cycle % 10 == 0) {
                s = s.copy(
                    coolantC = pid("0105")?.let { it[0] - 40 },
                    intakeC  = pid("010F")?.let { it[0] - 40 },
                    fuelPct  = pid("012F")?.let { it[0] * 100f / 255f },
                    batteryV = atCommand("ATRV")?.removeSuffix("V")?.toFloatOrNull(),
                )
                // 0101 byte A: bit7 = MIL lamp, bits 0-6 = stored DTC count
                pid("0101")?.let { st ->
                    val cnt = st[0] and 0x7F
                    s = s.copy(
                        dtcCount = cnt, milOn = st[0] >= 0x80,
                        dtcCodes = if (cnt > 0) readDtcCodes(cnt) else emptyList(),
                    )
                }
            }
            s = s.copy(
                throttlePct = pid("0111")?.let { it[0] * 100f / 255f },
                mafGs = pid("0110")?.let { (it[0] * 256 + it[1]) / 100f },
                reverse = readReverse(),
            )
            silent = if (rpm == null && spd == null) silent + 1 else 0
            if (silent >= 5) return   // ~1.5 s of dead bus → ignition off
            onSample(s)
            runInjected()
            cycle++
            delay(120) // ~8 Hz rotation; ELM clones choke faster than this
        }
    }

    /**
     * Reverse gear off the CAN bus — no wiring, OBD-only. Signature found by
     * bus capture on this D40 (2026-07-16): broadcast ID 0x60D, data byte 0
     * carries reverse in bit3 (0x08). Match the BIT, not the whole byte: the
     * capture that showed 0x28 was almost certainly bit3 + bit5 together, and
     * bit5 co-occurring (brake pedal is the prime suspect — your foot is on
     * it whenever you shift into R) is exactly why an exact ==0x08 match
     * missed reverse on real drives. Every byte0 transition is logged so the
     * next drive validates the bit map. We set the ELM receive filter to
     * 0x60D, snapshot ~120 ms of frames, then restore all-receive for the
     * next PID rotation.
     *
     * Returns null if the frame wasn't seen this cycle — the repository holds
     * the last known state rather than dropping out of reverse on one miss.
     */
    private var lastRevByte = -1
    private var revMisses = 0
    private fun readReverse(): Boolean? {
        port ?: return null
        // ATMA output OBEYS the headers setting: with ATH0 (our PID-poll
        // state) monitored frames print as bare data bytes with NO "60D"
        // prefix, which is why in-app captures parsed empty for two days
        // while the (ATH1) debug-console session saw IDs fine. Headers on
        // for the burst, off again for the PID rotation.
        atCommand("ATH1", 300)
        atCommand("ATCRA60D", 300)
        val cap = monitorBurst(200)
        atCommand("ATAR", 300)               // restore: PIDs need all responses
        atCommand("ATH0", 300)
        val b0 = Regex("60D([0-9A-F]{2})").findAll(cap).lastOrNull()
            ?.groupValues?.get(1)?.toIntOrNull(16)
        if (b0 == null) {
            // Surface a persistent capture failure instead of dying silently.
            if (++revMisses % 100 == 0)
                Log.w(TAG, "60D: no frames in last $revMisses captures; raw tail: " +
                    cap.takeLast(60).replace("\r", "|"))
            return null
        }
        revMisses = 0
        if (b0 != lastRevByte) {
            Log.i(TAG, "60D byte0 0x%02X -> reverse=%b".format(b0, b0 and 0x08 != 0))
            lastRevByte = b0
        }
        return (b0 and 0x08) != 0
    }

    /**
     * Key off: ATLP drops the chip to its low-power state so it stops
     * loading the start battery; any RX byte wakes it, so a bare CR
     * (whose first char the wake logic eats) precedes the next probe.
     */
    private suspend fun standby() {
        // One deep-sleep + wake, then RETURN so the main loop re-runs the full
        // initElm(). This is deliberate: after an ATLP wake the chip has
        // dropped its protocol, so a raw 0100 probe fails until ATSP6 is
        // re-asserted — probing inside standby stranded CAN offline for whole
        // drives. Re-init on every wake is the proven-reliable path; at 5 s
        // cadence reconnect is still fast, and ATLP keeps the drain low.
        Log.i(TAG, "ECU offline — ELM standby ${STANDBY_MS / 1000}s, then re-init")
        atCommand("ATLP", 300)
        delay(STANDBY_MS)
        port?.write("\r".toByteArray(), 200)   // any char wakes the chip
        delay(250); drainInput()
    }

    private fun open(): Boolean {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        // Whitelist real OBD bridge chips — the default prober would also
        // claim the u-blox GPS (CDC-ACM) on this hub and feed ATZ nothing.
        val all = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        val driver = all.firstOrNull { (it.device.vendorId to it.device.productId) in OBD_BRIDGES }
        if (driver == null) {
            Log.i(TAG, "no OBD bridge among ${all.size} serial devices: " +
                all.joinToString { "%04x:%04x".format(it.device.vendorId, it.device.productId) })
            return false
        }
        if (!usb.hasPermission(driver.device)) {
            Log.i(TAG, "requesting USB permission for ${driver.device.deviceName}")
            // Fire-and-forget: the 3 s retry loop reopens once the user grants.
            usb.requestPermission(driver.device, PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE))
            return false
        }
        val conn = usb.openDevice(driver.device) ?: return false
        port = driver.ports[0].apply {
            open(conn)
            setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        }
        // Clones sit at 38400; OBDLink/vLinker (FTDI) ship at 115200. Probe both.
        // Switching baud leaves framing garbage in the chip's RX buffer, so
        // drain and send a bare CR to settle it before ATZ, then allow one
        // retry — the first ATZ after a dirty line often returns '?'.
        for (baud in intArrayOf(115200, 38400)) {
            port?.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            drainInput()
            atCommand("\r", 300)          // flush any partial command line
            drainInput()
            repeat(2) {
                val r = atCommand("ATZ", 2500)
                Log.i(TAG, "ATZ@$baud -> ${r?.trim()?.replace("\r", "|") ?: "<silent>"}")
                if (r?.contains("ELM") == true) {
                    Log.i(TAG, "ELM327 up at $baud baud")
                    return true
                }
            }
        }
        close(); return false
    }

    /** Init + ECU probe; false when the bus is dead (ignition off). */
    private fun initElm(): Boolean {
        atCommand("ATE0"); atCommand("ATL0"); atCommand("ATS0")
        // Pin the protocol instead of ATSP0 auto-search. This truck is
        // ISO 15765-4 CAN 11-bit/500k (protocol 6), confirmed by the 11-bit
        // IDs in the bus captures. Pinned, 0100 answers in ~1 s when the ECU
        // is up and fails fast when it isn't — no multi-second SEARCHING.
        atCommand("ATSP6")
        val probe = atCommand("0100", 2500)
        Log.i(TAG, "0100 -> ${probe?.trim()?.replace("\r", "|") ?: "<silent>"}")
        // Space-tolerant: an ATLP wake resets ATS0, so a stray response can
        // read "41 00" instead of "4100". Never gate connection on spacing.
        return probe?.replace(" ", "")?.contains("4100") == true
    }

    /**
     * Mode 03: read stored DTCs and decode to J2012 strings (e.g. P0301).
     * The CAN response is "43 [count] <b1 b2>..."; some ECUs omit the count
     * byte, so we use the known [count] (from 0101) to disambiguate and to
     * bound the loop. Each 2-byte pair: bits 15-14 = P/C/B/U, then 3 digits.
     */
    private fun readDtcCodes(count: Int): List<String> {
        val resp = atCommand("03", 1500) ?: return emptyList()
        var data = resp.replace(">", "").replace(" ", "").replace("\r", "")
            .uppercase().substringAfter("43", "")
        if (data.isEmpty()) return emptyList()
        // Drop a leading count byte only if that makes the payload fit [count].
        if (data.length / 4 != count && (data.length - 2) / 4 >= count)
            data = data.substring(2)
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 4 <= data.length && codes.size < count) {
            val b1 = data.substring(i, i + 2).toIntOrNull(16) ?: break
            val b2 = data.substring(i + 2, i + 4).toIntOrNull(16) ?: break
            if (b1 != 0 || b2 != 0) codes += decodeDtc(b1, b2)
            i += 4
        }
        return codes
    }

    private fun decodeDtc(b1: Int, b2: Int): String {
        val letter = charArrayOf('P', 'C', 'B', 'U')[(b1 shr 6) and 0x3]
        return "%c%d%X%X%X".format(letter, (b1 shr 4) and 0x3, b1 and 0xF,
            (b2 shr 4) and 0xF, b2 and 0xF)
    }

    /** Send mode-01 PID, return data bytes after the 41 XX header. */
    private fun pid(cmd: String): IntArray? {
        val resp = atCommand(cmd) ?: return null
        val hex = resp.replace(">", "").replace(" ", "").trim()
        val idx = hex.indexOf("41" + cmd.substring(2))
        if (idx < 0) return null
        val data = hex.substring(idx + 4)
        return data.chunked(2).mapNotNull { it.toIntOrNull(16) }.toIntArray()
            .takeIf { it.isNotEmpty() }
    }

    /** Read and discard whatever the chip has buffered until it goes quiet. */
    private fun drainInput() {
        val p = port ?: return
        val buf = ByteArray(256)
        val deadline = System.currentTimeMillis() + 250
        while (System.currentTimeMillis() < deadline) {
            if (p.read(buf, 60) <= 0) break
        }
    }

    private fun atCommand(cmd: String, timeoutMs: Long = 700): String? {
        val p = port ?: return null
        // Discard bytes left over from a command that outran its timeout —
        // they would otherwise be parsed as this command's response.
        val junk = ByteArray(256)
        while (p.read(junk, 10) > 0) { /* drained */ }
        p.write((cmd + "\r").toByteArray(), 500)
        val buf = ByteArray(256); val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = p.read(buf, 100)
            if (n > 0) { sb.append(String(buf, 0, n)); if (sb.contains('>')) break }
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    fun close() { try { port?.close() } catch (_: Exception) {}; port = null }

    companion object {
        private const val TAG = "Helm"
        // Key-off re-probe cadence. 5 s connects fast after ignition while the
        // ELM deep-sleeps between probes (~5 mA), vs the old blind 60 s wait.
        private const val STANDBY_MS = 5_000L
        const val ACTION_USB_PERMISSION = "com.xterra.helm.USB_PERMISSION"

        /** vid to pid of bridge chips ELM327/OBDLink dongles actually use. */
        private val OBD_BRIDGES = setOf(
            0x1a86 to 0x7523, // CH340
            0x10c4 to 0xea60, // CP2102
            0x0403 to 0x6001, // FTDI FT232R
            0x0403 to 0x6015, // FTDI FT231X (OBDLink SX, vLinker FS)
            0x067b to 0x2303, // PL2303
        )
    }
}
