package com.xterra.helm.lora

import org.junit.Assert.assertEquals
import org.junit.Test

/** RFC 4493 AES-CMAC test vectors — the definitive check for the MIC path. */
class AesCmacTest {
    private fun h(s: String) = LoRaWan.unhex(s)
    private val key = h("2B7E151628AED2A6ABF7158809CF4F3C")

    @Test fun emptyMessage() {
        assertEquals("BB1D6929E95937287FA37D129B756746",
            LoRaWan.hex(AesCmac.cmac(key, ByteArray(0))))
    }

    @Test fun oneBlock() {
        assertEquals("070A16B46B4D4144F79BDD9DD04A287C",
            LoRaWan.hex(AesCmac.cmac(key, h("6BC1BEE22E409F96E93D7E117393172A"))))
    }

    @Test fun partialBlock() {
        // 40-byte message → last block padded, XOR K2.
        val msg = h("6BC1BEE22E409F96E93D7E117393172AAE2D8A571E03AC9C9EB76FAC45AF8E51" +
            "30C81C46A35CE411")
        assertEquals("DFA66747DE9AE63030CA32611497C827", LoRaWan.hex(AesCmac.cmac(key, msg)))
    }
}
