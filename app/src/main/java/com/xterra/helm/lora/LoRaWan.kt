package com.xterra.helm.lora

/**
 * LoRaWAN 1.0.x MAC layer: frame parsing, MIC (via [AesCmac]), FRMPayload
 * crypto, and the OTAA join key schedule. Enough to be the network server for
 * a handful of Class-A trackers behind a Semtech-UDP gateway — not a full ADR
 * stack. All little-endian-on-the-wire fields are exposed MSB-first (hex) to
 * match how the SenseCAP app displays EUIs/keys. Pure; unit-tested.
 */
object LoRaWan {

    const val JOIN_REQUEST = 0
    const val JOIN_ACCEPT = 1
    const val UNCONF_UP = 2
    const val CONF_UP = 4

    fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    fun unhex(s: String): ByteArray {
        val c = s.trim().replace(" ", "").replace(":", "")
        require(c.length % 2 == 0) { "odd hex length" }
        return ByteArray(c.length / 2) {
            ((c[it * 2].digitToInt(16) shl 4) or c[it * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private fun rev(b: ByteArray) = b.reversedArray()

    fun mType(phy: ByteArray) = (phy[0].toInt() and 0xE0) ushr 5

    data class Uplink(
        val mType: Int,
        val devAddr: String? = null,       // data frames, MSB-first hex
        val fcnt: Int = 0,
        val fPort: Int? = null,
        val frmPayload: ByteArray = ByteArray(0),
        val joinEui: String? = null,       // join request, MSB-first hex
        val devEui: String? = null,
        val devNonce: Int = 0,
        val mic: ByteArray = ByteArray(0),
        val raw: ByteArray = ByteArray(0),
    )

    /** Structural parse only — no MIC check, no decrypt. Null if malformed. */
    fun parse(phy: ByteArray): Uplink? {
        if (phy.size < 5) return null
        val mt = mType(phy)
        val mic = phy.copyOfRange(phy.size - 4, phy.size)
        when (mt) {
            JOIN_REQUEST -> {
                if (phy.size != 23) return null
                return Uplink(
                    mType = mt,
                    joinEui = hex(rev(phy.copyOfRange(1, 9))),
                    devEui = hex(rev(phy.copyOfRange(9, 17))),
                    devNonce = u16(phy, 17),
                    mic = mic, raw = phy,
                )
            }
            UNCONF_UP, CONF_UP -> {
                val devAddr = hex(rev(phy.copyOfRange(1, 5)))
                val fOptsLen = phy[5].toInt() and 0x0F
                val fcnt = u16(phy, 6)
                val fhdrEnd = 8 + fOptsLen
                var fPort: Int? = null
                var frm = ByteArray(0)
                if (phy.size - 4 > fhdrEnd) {
                    fPort = phy[fhdrEnd].toInt() and 0xFF
                    frm = phy.copyOfRange(fhdrEnd + 1, phy.size - 4)
                }
                return Uplink(mt, devAddr, fcnt, fPort, frm, mic = mic, raw = phy)
            }
            else -> return null
        }
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** Uplink data-frame MIC (dir=0) over B0 | msg, keyed with NwkSKey. */
    fun dataMic(nwkSKey: ByteArray, devAddrLE: ByteArray, fcnt: Int, msg: ByteArray): ByteArray {
        val b0 = ByteArray(16)
        b0[0] = 0x49
        b0[5] = 0                              // dir = uplink
        System.arraycopy(devAddrLE, 0, b0, 6, 4)
        b0[10] = (fcnt and 0xFF).toByte(); b0[11] = ((fcnt ushr 8) and 0xFF).toByte()
        b0[15] = msg.size.toByte()
        return AesCmac.cmac(nwkSKey, b0 + msg).copyOfRange(0, 4)
    }

    fun verifyDataMic(u: Uplink, nwkSKey: ByteArray): Boolean {
        val devAddrLE = rev(unhex(u.devAddr!!))
        val msg = u.raw.copyOfRange(0, u.raw.size - 4)
        return dataMic(nwkSKey, devAddrLE, u.fcnt, msg).contentEquals(u.mic)
    }

    /**
     * FRMPayload crypt — symmetric, so this both decrypts uplinks and encrypts
     * downlinks. key = AppSKey (FPort > 0) or NwkSKey (FPort == 0). dir: 0 up.
     */
    fun cryptPayload(key: ByteArray, devAddrLE: ByteArray, fcnt: Int, dir: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size)
        val a = ByteArray(16)
        a[0] = 0x01; a[5] = dir.toByte()
        System.arraycopy(devAddrLE, 0, a, 6, 4)
        a[10] = (fcnt and 0xFF).toByte(); a[11] = ((fcnt ushr 8) and 0xFF).toByte()
        var block = 1; var off = 0
        while (off < payload.size) {
            a[15] = block.toByte()
            val s = AesCmac.aesEncrypt(key, a)
            var j = 0
            while (j < 16 && off < payload.size) {
                out[off] = (payload[off].toInt() xor s[j].toInt()).toByte(); off++; j++
            }
            block++
        }
        return out
    }

    /** Decrypt an uplink's FRMPayload with the node's AppSKey. */
    fun decryptUplink(u: Uplink, appSKey: ByteArray): ByteArray {
        if (u.frmPayload.isEmpty()) return ByteArray(0)
        return cryptPayload(appSKey, rev(unhex(u.devAddr!!)), u.fcnt, 0, u.frmPayload)
    }

    // ── OTAA ────────────────────────────────────────────────────────────────

    fun verifyJoinMic(u: Uplink, appKey: ByteArray): Boolean {
        val msg = u.raw.copyOfRange(0, u.raw.size - 4)
        return AesCmac.cmac(appKey, msg).copyOfRange(0, 4).contentEquals(u.mic)
    }

    /** LoRaWAN 1.0 session key: AES-ECB(AppKey, type|JoinNonce|NetID|DevNonce|0). */
    fun sessionKey(type: Int, appKey: ByteArray, joinNonceLE: ByteArray, netIdLE: ByteArray, devNonce: Int): ByteArray {
        val b = ByteArray(16)
        b[0] = type.toByte()
        System.arraycopy(joinNonceLE, 0, b, 1, 3)
        System.arraycopy(netIdLE, 0, b, 4, 3)
        b[7] = (devNonce and 0xFF).toByte(); b[8] = ((devNonce ushr 8) and 0xFF).toByte()
        return AesCmac.aesEncrypt(appKey, b)
    }

    data class JoinAccept(
        val phy: ByteArray,          // encrypted PHYPayload to send as the downlink
        val devAddr: String,         // MSB-first hex assigned to the node
        val nwkSKey: ByteArray,
        val appSKey: ByteArray,
    )

    /**
     * Build a JoinAccept (no CFList) + derive the session keys. The accept body
     * is "encrypted" with an AES *decrypt* so the device recovers it with an
     * encrypt — a LoRaWAN quirk. devAddr is MSB-first (4 bytes).
     */
    fun buildJoinAccept(
        appKey: ByteArray, joinNonce: Int, netId: Int, devAddr: ByteArray,
        devNonce: Int, dlSettings: Int, rxDelay: Int,
    ): JoinAccept {
        val jnLE = le3(joinNonce)
        val netLE = le3(netId)
        val devAddrLE = rev(devAddr)
        val plain = byteArrayOf(0x20) + jnLE + netLE + devAddrLE +
            byteArrayOf(dlSettings.toByte(), rxDelay.toByte())
        val mic = AesCmac.cmac(appKey, plain).copyOfRange(0, 4)
        val enc = AesCmac.aesDecrypt(appKey, plain.copyOfRange(1, plain.size) + mic)
        return JoinAccept(
            phy = byteArrayOf(0x20) + enc,
            devAddr = hex(devAddr),
            nwkSKey = sessionKey(0x01, appKey, jnLE, netLE, devNonce),
            appSKey = sessionKey(0x02, appKey, jnLE, netLE, devNonce),
        )
    }

    private fun le3(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte())
}
