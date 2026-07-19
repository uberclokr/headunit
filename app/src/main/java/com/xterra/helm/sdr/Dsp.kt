package com.xterra.helm.sdr

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/** Radix-2 in-place FFT. re/im length must be a power of two. */
object Fft {
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) { re.swap(i, j); im.swap(i, j) }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
    private fun FloatArray.swap(a: Int, b: Int) { val t = this[a]; this[a] = this[b]; this[b] = t }

    /** Power spectrum in dB, DC-centered, Hann windowed. */
    fun powerDb(iq: ByteArray, fftSize: Int, out: FloatArray) {
        val re = FloatArray(fftSize); val im = FloatArray(fftSize)
        for (i in 0 until fftSize) {
            val w = 0.5f - 0.5f * cos(2.0 * PI * i / fftSize).toFloat()
            re[i] = ((iq[2 * i].toInt() and 0xFF) - 127.5f) / 127.5f * w
            im[i] = ((iq[2 * i + 1].toInt() and 0xFF) - 127.5f) / 127.5f * w
        }
        transform(re, im)
        for (i in 0 until fftSize) {
            val src = (i + fftSize / 2) % fftSize // fft-shift
            val p = re[src] * re[src] + im[src] * im[src]
            out[i] = 10f * log10(p + 1e-12f)
        }
    }
}

/**
 * Wideband FM demodulator: complex decimation → quadrature discriminator →
 * mono audio decimation → 75 µs de-emphasis. 1.024 Msps in, 32 kHz audio out.
 * Pure Kotlin runs fine on the RK3588; move to JNI if you add RDS/stereo.
 */
class WbfmDemodulator(inputRate: Int) {
    private val decim1 = inputRate / 256_000          // → 256 kHz complex
    private val audioDecim = 256_000 / AUDIO_RATE     // → 32 kHz audio
    private var lastI = 0f; private var lastQ = 0f
    private var deemph = 0f
    private val alpha = 1f - kotlin.math.exp(-1.0f / (AUDIO_RATE * 75e-6f))

    fun process(iq: ByteArray, samples: Int): ShortArray {
        val outLen = samples / decim1 / audioDecim
        val audio = ShortArray(outLen)
        var ai = 0; var accI = 0f; var accQ = 0f; var d1 = 0
        var fmAcc = 0f; var d2 = 0
        var i = 0
        while (i < samples && ai < outLen) {
            accI += ((iq[2 * i].toInt() and 0xFF) - 127.5f)
            accQ += ((iq[2 * i + 1].toInt() and 0xFF) - 127.5f)
            if (++d1 == decim1) {
                val si = accI / decim1; val sq = accQ / decim1
                accI = 0f; accQ = 0f; d1 = 0
                // discriminator: angle of s * conj(last)
                val dr = si * lastI + sq * lastQ
                val di = sq * lastI - si * lastQ
                lastI = si; lastQ = sq
                fmAcc += atan2(di, dr)
                if (++d2 == audioDecim) {
                    var s = fmAcc / audioDecim / PI.toFloat() // normalize
                    fmAcc = 0f; d2 = 0
                    deemph += alpha * (s - deemph)            // 75 µs de-emphasis
                    s = deemph
                    audio[ai++] = (s * 20000f).coerceIn(-32767f, 32767f).toInt().toShort()
                }
            }
            i++
        }
        return if (ai == outLen) audio else audio.copyOf(ai)
    }

    /** Rough signal strength for the tuner bar. */
    fun rssiDb(iq: ByteArray, samples: Int): Float {
        var acc = 0f
        var i = 0
        while (i < samples) {
            val re = ((iq[2 * i].toInt() and 0xFF) - 127.5f) / 127.5f
            val im = ((iq[2 * i + 1].toInt() and 0xFF) - 127.5f) / 127.5f
            acc += re * re + im * im; i += 16
        }
        return 10f * log10(acc / (samples / 16f) + 1e-12f)
    }

    companion object { const val AUDIO_RATE = 32_000 }
}

/**
 * Narrowband FM (±5 kHz deviation) for NOAA weather radio: one boxcar
 * decimation straight to 32 kHz complex, discriminator, gain to fill the
 * audio range. No de-emphasis — NWR voice is flat and the crude channel
 * filter matters more than fidelity here.
 */
class NbfmDemodulator(inputRate: Int) {
    private val decim = inputRate / WbfmDemodulator.AUDIO_RATE   // 32 @ 1.024 Msps
    private var lastI = 0f; private var lastQ = 0f

    fun process(iq: ByteArray, samples: Int): ShortArray {
        val outLen = samples / decim
        val audio = ShortArray(outLen)
        var ai = 0; var accI = 0f; var accQ = 0f; var d = 0
        var i = 0
        while (i < samples && ai < outLen) {
            accI += ((iq[2 * i].toInt() and 0xFF) - 127.5f)
            accQ += ((iq[2 * i + 1].toInt() and 0xFF) - 127.5f)
            if (++d == decim) {
                val si = accI / decim; val sq = accQ / decim
                accI = 0f; accQ = 0f; d = 0
                val dr = si * lastI + sq * lastQ
                val di = sq * lastI - si * lastQ
                lastI = si; lastQ = sq
                // ±5 kHz dev on a ±16 kHz discriminator → ~0.3 FS; boost ×3.
                val s = atan2(di, dr) / PI.toFloat() * 3f
                audio[ai++] = (s * 20000f).coerceIn(-32767f, 32767f).toInt().toShort()
            }
            i++
        }
        return if (ai == outLen) audio else audio.copyOf(ai)
    }
}

/**
 * SAME/EAS decoder for NOAA weather radio audio (32 kHz mono).
 *
 * Two detectors, because AFSK decode off a crude NBFM chain is fragile:
 *  1. SAME AFSK — 520.83 baud, mark 2083.3 Hz / space 1562.5 Hz. Non-coherent
 *     FSK: complex mixers at both tones, single-pole magnitude LPFs (~1 bit),
 *     transition-nudged bit clock, LSB-first bytes aligned on the 0xAB
 *     preamble. A decoded "ZCZC-..." header is returned verbatim.
 *  2. 1050 Hz Warning Alarm Tone — Goertzel-style resonator; ≥3 s of
 *     sustained tone returns a generic alert. NWS sends 8–10 s of WAT before
 *     every voice alert, so this catches events even when AFSK decode fails.
 *
 * Validation needs an on-air alert (weekly NWS test); until then treat this
 * as best-effort.
 */
class SameDecoder(private val sampleRate: Int = WbfmDemodulator.AUDIO_RATE) {
    private val baud = 520.83f
    private val spb = sampleRate / baud              // samples per bit (~61.44)

    // FSK mixers + envelope followers
    private var mPh = 0.0; private var sPh = 0.0
    private val mStep = 2.0 * PI * 2083.3 / sampleRate
    private val sStep = 2.0 * PI * 1562.5 / sampleRate
    private var mI = 0f; private var mQ = 0f; private var sI = 0f; private var sQ = 0f
    private val lpA = 1f / spb                        // ~1-bit LPF

    // bit clock + byte framing
    private var phase = 0f
    private var lastBit = false
    private var shreg = 0
    private var nbits = 0
    private var aligned = false
    private var msg = StringBuilder()

    // 1050 Hz WAT detector
    private var wPh = 0.0
    private val wStep = 2.0 * PI * 1050.0 / sampleRate
    private var wI = 0f; private var wQ = 0f; private var pwr = 0f
    private var watSamples = 0
    private var watFired = false

    /** Feed demodulated audio; returns a SAME header / WAT alert when found. */
    fun feed(audio: ShortArray): String? {
        var result: String? = null
        for (s16 in audio) {
            val x = s16 / 32768f

            // WAT: narrow resonator at 1050 Hz vs total power.
            wI += lpA * ((x * cos(wPh).toFloat()) - wI)
            wQ += lpA * ((x * sin(wPh).toFloat()) - wQ)
            wPh += wStep
            pwr += 0.0005f * (x * x - pwr)
            val watNow = (wI * wI + wQ * wQ) > 0.25f * pwr && pwr > 1e-5f
            if (watNow) {
                if (++watSamples == sampleRate * 3 && !watFired) {
                    watFired = true
                    result = "WARNING TONE (1050 Hz) — alert in progress"
                }
            } else { watSamples = 0; watFired = false }

            // FSK envelopes
            mI += lpA * ((x * cos(mPh).toFloat()) - mI)
            mQ += lpA * ((x * sin(mPh).toFloat()) - mQ)
            sI += lpA * ((x * cos(sPh).toFloat()) - sI)
            sQ += lpA * ((x * sin(sPh).toFloat()) - sQ)
            mPh += mStep; sPh += sStep
            val bit = (mI * mI + mQ * mQ) > (sI * sI + sQ * sQ)

            // transition-nudged bit clock, sample mid-bit
            if (bit != lastBit) {
                phase += 0.15f * (0.5f - phase)   // pull sampling point to center
                lastBit = bit
            }
            phase += 1f / spb
            if (phase >= 1f) {
                phase -= 1f
                pushBit(bit)?.let { result = it }
            }
        }
        return result
    }

    private fun pushBit(bit: Boolean): String? {
        shreg = (shreg shr 1) or (if (bit) 0x8000 else 0)  // LSB-first stream
        nbits++
        if (!aligned) {
            // Two consecutive preamble bytes anywhere in the shift register
            // locks byte phase. 0xAB LSB-first appears as 0xABAB in our reg.
            if ((shreg and 0xFFFF) == 0xABAB) { aligned = true; nbits = 0; msg.clear() }
            return null
        }
        if (nbits < 8) return null
        nbits = 0
        val ch = (shreg shr 8) and 0xFF
        when {
            ch == 0xAB -> return null                     // still preamble
            ch in 0x20..0x7E -> {
                msg.append(ch.toChar())
                if (msg.length > 268) { aligned = false; return null }
                val text = msg.toString()
                if (text.endsWith("NNNN")) { aligned = false; msg.clear(); return null }
                // Full header: ZCZC-ORG-EEE-PSSCCC…+TTTT-JJJHHMM-CALLSIGN-
                if (text.startsWith("ZCZC") && text.length > 30 &&
                    text.endsWith("-") && text.contains("+")) {
                    aligned = false
                    return text
                }
            }
            else -> aligned = false                       // garbage → resync
        }
        return null
    }
}
