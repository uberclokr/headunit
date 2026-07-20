package com.xterra.helm.lora

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128 primitives for LoRaWAN: raw single-block ECB, and AES-CMAC
 * (RFC 4493) used for every LoRaWAN MIC. Pure and unit-tested against the
 * RFC 4493 test vectors — the MIC path is where a subtle bug silently drops
 * every uplink, so it's verified rather than trusted.
 */
object AesCmac {

    /** One-block AES-128 ECB encrypt (no padding). */
    fun aesEncrypt(key: ByteArray, block: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(block)
        }

    /** One-block AES-128 ECB decrypt — LoRaWAN encrypts JoinAccept with this. */
    fun aesDecrypt(key: ByteArray, block: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(block)
        }

    /** Full AES-CMAC over [msg]; returns the 16-byte tag (MIC uses first 4). */
    fun cmac(key: ByteArray, msg: ByteArray): ByteArray {
        val zero = ByteArray(16)
        val l = aesEncrypt(key, zero)
        val k1 = subkey(l)
        val k2 = subkey(k1)

        val n = if (msg.isEmpty()) 1 else (msg.size + 15) / 16
        val lastComplete = msg.isNotEmpty() && msg.size % 16 == 0

        val lastBlock: ByteArray
        if (lastComplete) {
            lastBlock = xor(msg.copyOfRange((n - 1) * 16, n * 16), k1)
        } else {
            val rem = msg.copyOfRange((n - 1) * 16, msg.size)
            val padded = ByteArray(16)
            System.arraycopy(rem, 0, padded, 0, rem.size)
            padded[rem.size] = 0x80.toByte()
            lastBlock = xor(padded, k2)
        }

        var x = ByteArray(16)
        for (i in 0 until n - 1) {
            x = aesEncrypt(key, xor(x, msg.copyOfRange(i * 16, i * 16 + 16)))
        }
        return aesEncrypt(key, xor(x, lastBlock))
    }

    /** RFC 4493 subkey: left-shift by 1, XOR Rb (0x87) if the MSB was set. */
    private fun subkey(inp: ByteArray): ByteArray {
        val out = ByteArray(16)
        var carry = 0
        for (i in 15 downTo 0) {
            val v = (inp[i].toInt() and 0xFF)
            out[i] = ((v shl 1) or carry).toByte()
            carry = (v ushr 7) and 1
        }
        if ((inp[0].toInt() and 0x80) != 0) out[15] = (out[15].toInt() xor 0x87).toByte()
        return out
    }

    private fun xor(a: ByteArray, b: ByteArray) =
        ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
}
