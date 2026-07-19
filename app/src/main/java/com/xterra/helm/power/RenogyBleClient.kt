package com.xterra.helm.power

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BLE client for Renogy devices (BT-1/BT-2 dongles, smart batteries with
 * built-in BLE, and the hub). The link is Modbus-RTU tunneled over GATT:
 *
 *   write  → service FFD0, characteristic FFD1  (Modbus request frame)
 *   notify ← service FFF0, characteristic FFF1  (Modbus response frames)
 *
 * Same protocol family reverse-engineered by the renogy-bt project — the
 * device only differs in which register map it answers (see maps in
 * [BatteryRepository]). Frames: [devId, 0x03, regHi, regLo, cntHi, cntLo,
 * crcLo, crcHi], response [devId, 0x03, byteCount, data..., crc].
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested at runtime
class RenogyBleClient(private val context: Context) {

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val responses = Channel<ByteArray>(capacity = 8)
    @Volatile var connected = false
        private set

    /**
     * BLE scan for [seconds], logging every device to `logcat -s Helm`.
     * Renogy dongles advertise as `BT-TH-…` (BT-2) or `BT-…`; the name
     * is the giveaway since the serial-number sticker is NOT the MAC.
     * Returns the MAC of the first Renogy-looking device found, or null.
     */
    @SuppressLint("MissingPermission")
    suspend fun scan(seconds: Int = 10): String? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager).adapter ?: return null
        val scanner = adapter.bluetoothLeScanner ?: return null
        val seen = HashMap<String, String>()
        var renogy: String? = null
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.device.name ?: r.scanRecord?.deviceName ?: "?"
                val mac = r.device.address
                if (seen.put(mac, name) == null)
                    Log.i("Helm", "BLE: $mac  rssi=${r.rssi}  name=$name")
                if (renogy == null && name.uppercase().let {
                        it.startsWith("BT-TH") || it.startsWith("BT-2") ||
                        it.contains("RENOGY")
                    }) {
                    renogy = mac
                    Log.i("Helm", "BLE: Renogy candidate -> $mac ($name)")
                }
            }
        }
        scanner.startScan(cb)
        repeat(seconds) { if (renogy == null) delay(1_000) }
        scanner.stopScan(cb)
        Log.i("Helm", "BLE scan done: ${seen.size} devices, renogy=$renogy")
        return renogy
    }

    suspend fun connect(mac: String): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager).adapter ?: return false
        val device = try { adapter.getRemoteDevice(mac) } catch (_: Exception) { return false }
        val ready = Channel<Boolean>(1)

        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestMtu(247)
                } else {
                    connected = false
                    ready.trySend(false)
                }
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                g.discoverServices()
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                writeChar = g.getService(SVC_WRITE)?.getCharacteristic(CHR_WRITE)
                val notify = g.getService(SVC_NOTIFY)?.getCharacteristic(CHR_NOTIFY)
                if (writeChar == null || notify == null) { ready.trySend(false); return }
                g.setCharacteristicNotification(notify, true)
                notify.getDescriptor(CCCD)?.let { d ->
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                connected = status == BluetoothGatt.GATT_SUCCESS
                ready.trySend(connected)
            }
            @Deprecated("pre-33 callback kept for Edge2 images")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                c.value?.let { responses.trySend(it.copyOf()) }
            }
            override fun onCharacteristicChanged(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
            ) { responses.trySend(value.copyOf()) }
        }, BluetoothDevice.TRANSPORT_LE)

        return withTimeoutOrNull(10_000) { ready.receive() } ?: false
    }

    /** Modbus function-3 read of [count] 16-bit registers starting at [reg]. */
    suspend fun readRegisters(deviceId: Int, reg: Int, count: Int): IntArray? {
        val g = gatt ?: return null
        val w = writeChar ?: return null
        val frame = byteArrayOf(
            deviceId.toByte(), 0x03,
            (reg shr 8).toByte(), reg.toByte(),
            (count shr 8).toByte(), count.toByte(), 0, 0,
        )
        val crc = crc16(frame, 6)
        frame[6] = (crc and 0xFF).toByte(); frame[7] = (crc shr 8).toByte()

        // drain stale notifications, then write
        while (responses.tryReceive().isSuccess) { /* drain */ }
        w.value = frame
        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (!g.writeCharacteristic(w)) return null

        // responses may arrive fragmented across notifications
        val expected = 5 + count * 2
        var buf = ByteArray(0)
        val ok = withTimeoutOrNull(2_000) {
            while (buf.size < expected) buf += responses.receive()
            true
        } ?: return null

        if (!ok || buf[1].toInt() != 0x03) return null
        val n = buf[2].toInt() and 0xFF
        val out = IntArray(n / 2)
        for (i in out.indices) {
            out[i] = ((buf[3 + i * 2].toInt() and 0xFF) shl 8) or
                     (buf[4 + i * 2].toInt() and 0xFF)
        }
        return out
    }

    fun close() {
        connected = false
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    companion object {
        val SVC_WRITE: UUID  = UUID.fromString("0000ffd0-0000-1000-8000-00805f9b34fb")
        val CHR_WRITE: UUID  = UUID.fromString("0000ffd1-0000-1000-8000-00805f9b34fb")
        val SVC_NOTIFY: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val CHR_NOTIFY: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun crc16(data: ByteArray, len: Int): Int {
            var crc = 0xFFFF
            for (i in 0 until len) {
                crc = crc xor (data[i].toInt() and 0xFF)
                repeat(8) {
                    crc = if (crc and 1 != 0) (crc shr 1) xor 0xA001 else crc shr 1
                }
            }
            return crc
        }
    }
}
