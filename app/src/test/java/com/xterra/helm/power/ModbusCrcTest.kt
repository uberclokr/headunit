package com.xterra.helm.power

import org.junit.Assert.assertEquals
import org.junit.Test

/** crc16 is public on RenogyBleClient's companion — testable without BLE. */
class ModbusCrcTest {

    @Test fun `standard check value for 123456789`() {
        // CRC-16/MODBUS check value is 0x4B37
        assertEquals(0x4B37, RenogyBleClient.crc16("123456789".toByteArray(), 9))
    }

    @Test fun `read holding registers frame`() {
        // 01 03 00 00 00 0A → CRC lo/hi C5 CD (i.e. 0xCDC5)
        val f = byteArrayOf(0x01, 0x03, 0x00, 0x00, 0x00, 0x0A)
        assertEquals(0xCDC5, RenogyBleClient.crc16(f, 6))
    }

    @Test fun `renogy battery info frame`() {
        // devId 1, fn 3, reg 0x0100, count 7 — the BT-2 battery-info poll
        val f = byteArrayOf(0x01, 0x03, 0x01, 0x00, 0x00, 0x07)
        assertEquals(0xF405, RenogyBleClient.crc16(f, 6))
    }

    @Test fun `len limits the region crced`() {
        // trailing bytes past len must not affect the result (frame has 2 CRC slots)
        val f = byteArrayOf(0x01, 0x03, 0x00, 0x00, 0x00, 0x0A, 0x55, 0x55)
        assertEquals(0xCDC5, RenogyBleClient.crc16(f, 6))
    }

    @Test fun `empty input is seed`() {
        assertEquals(0xFFFF, RenogyBleClient.crc16(ByteArray(0), 0))
    }
}
