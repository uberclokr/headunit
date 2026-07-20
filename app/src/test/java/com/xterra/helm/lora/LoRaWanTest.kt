package com.xterra.helm.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoRaWanTest {
    private fun h(s: String) = LoRaWan.unhex(s)
    private val appSKey = h("2B7E151628AED2A6ABF7158809CF4F3C")
    private val devAddrLE = h("01020304")   // as it appears on the wire (LE)

    @Test fun frmPayloadCryptIsSymmetric() {
        val plain = h("06000000050000000008B7A6D002A0BFA0000000005A")
        val enc = LoRaWan.cryptPayload(appSKey, devAddrLE, 7, 0, plain)
        val back = LoRaWan.cryptPayload(appSKey, devAddrLE, 7, 0, enc)
        assertArrayEquals(plain, back)
    }

    @Test fun parseRoundTripsDataUplink() {
        // MHDR=0x40 (unconf up), DevAddr 04030201(LE), FCtrl=00, FCnt=0007,
        // FPort=02, FRMPayload=AABB, MIC=DEADBEEF.
        val phy = h("4004030201000700" + "02" + "AABB" + "DEADBEEF")
        val u = LoRaWan.parse(phy)!!
        assertEquals(LoRaWan.UNCONF_UP, u.mType)
        assertEquals("01020304", u.devAddr)      // exposed MSB-first
        assertEquals(7, u.fcnt)
        assertEquals(2, u.fPort)
        assertArrayEquals(h("AABB"), u.frmPayload)
    }

    @Test fun dataMicVerifies() {
        val nwkSKey = h("2B7E151628AED2A6ABF7158809CF4F3C")
        // Build a frame, compute a valid MIC, confirm verifyDataMic accepts it.
        // DevAddr on the wire is little-endian (04030201 → 0x01020304).
        val frameAddrLE = h("04030201")
        val body = h("4004030201000700" + "02" + "AABB")
        val mic = LoRaWan.dataMic(nwkSKey, frameAddrLE, 7, body)
        val u = LoRaWan.parse(body + mic)!!
        assertTrue(LoRaWan.verifyDataMic(u, nwkSKey))
        // A tampered payload must fail.
        val bad = LoRaWan.parse(h("4004030201000700" + "02" + "AACC") + mic)!!
        assertTrue(!LoRaWan.verifyDataMic(bad, nwkSKey))
    }

    @Test fun joinAcceptDerivesSixteenByteKeysAndBlockSizedFrame() {
        val appKey = h("00112233445566778899AABBCCDDEEFF")
        val devAddr = h("26011F88")
        val ja = LoRaWan.buildJoinAccept(
            appKey, joinNonce = 0x0001A2, netId = 0x000013, devAddr = devAddr,
            devNonce = 0x0ABC, dlSettings = 0, rxDelay = 1)
        assertEquals(16, ja.nwkSKey.size)
        assertEquals(16, ja.appSKey.size)
        assertEquals("26011F88", ja.devAddr)
        // MHDR + one encrypted 16-byte block (no CFList).
        assertEquals(17, ja.phy.size)
    }
}
