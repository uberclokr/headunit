package com.xterra.helm.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class FftTest {

    @Test fun `impulse gives flat spectrum`() {
        val n = 64
        val re = FloatArray(n); val im = FloatArray(n)
        re[0] = 1f
        Fft.transform(re, im)
        for (i in 0 until n) {
            assertEquals("re[$i]", 1f, re[i], 1e-4f)
            assertEquals("im[$i]", 0f, im[i], 1e-4f)
        }
    }

    @Test fun `dc gives energy only in bin zero`() {
        val n = 64
        val re = FloatArray(n) { 1f }; val im = FloatArray(n)
        Fft.transform(re, im)
        assertEquals(n.toFloat(), re[0], 1e-3f)
        for (i in 1 until n) assertEquals(0f, sqrt(re[i] * re[i] + im[i] * im[i]), 1e-3f)
    }

    @Test fun `real tone peaks at plus and minus bin`() {
        val n = 64; val k = 5
        val re = FloatArray(n) { cos(2.0 * PI * k * it / n).toFloat() }
        val im = FloatArray(n)
        Fft.transform(re, im)
        for (i in 0 until n) {
            val mag = sqrt(re[i] * re[i] + im[i] * im[i])
            if (i == k || i == n - k) assertEquals("bin $i", n / 2f, mag, 1e-2f)
            else assertEquals("bin $i", 0f, mag, 1e-2f)
        }
    }

    @Test fun `powerDb peak lands at shifted tone bin`() {
        val n = 256; val k = 32
        // complex exponential e^{j2πki/n} as unsigned rtl-sdr IQ bytes
        val iq = ByteArray(2 * n)
        for (i in 0 until n) {
            iq[2 * i] = (127.5 + 100.0 * cos(2.0 * PI * k * i / n)).roundToInt().toByte()
            iq[2 * i + 1] = (127.5 + 100.0 * sin(2.0 * PI * k * i / n)).roundToInt().toByte()
        }
        val out = FloatArray(n)
        Fft.powerDb(iq, n, out)
        var best = 0
        for (i in 1 until n) if (out[i] > out[best]) best = i
        assertEquals(n / 2 + k, best) // DC-centered: +k lands right of center
        // tone should stand well above bins far from the peak
        assertTrue(out[best] - out[n / 2 + k + 40] > 20f)
    }
}

class WbfmDemodulatorTest {

    private val rate = 1_024_000 // decim1 = 4, audioDecim = 8 → 32 in per audio out

    private fun toneIq(freqHz: Double, samples: Int): ByteArray {
        val iq = ByteArray(2 * samples)
        for (i in 0 until samples) {
            val ph = 2.0 * PI * freqHz * i / rate
            iq[2 * i] = (127.5 + 100.0 * cos(ph)).roundToInt().toByte()
            iq[2 * i + 1] = (127.5 + 100.0 * sin(ph)).roundToInt().toByte()
        }
        return iq
    }

    @Test fun `unmodulated carrier at dc is silence`() {
        val samples = 3200
        val audio = WbfmDemodulator(rate).process(toneIq(0.0, samples), samples)
        assertEquals(100, audio.size)
        for (s in audio) assertTrue("|$s| too loud", abs(s.toInt()) < 100)
    }

    @Test fun `frequency offset maps to dc audio level`() {
        // +32 kHz offset → per-256k-sample phase step π/4 → s = 0.25 → 5000 counts
        val samples = 6400
        val audio = WbfmDemodulator(rate).process(toneIq(32_000.0, samples), samples)
        assertEquals(200, audio.size)
        for (i in 50 until audio.size) { // skip de-emphasis settling
            assertTrue("sample $i = ${audio[i]}", audio[i] in 4500..5500)
        }
    }

    @Test fun `negative offset gives negative audio`() {
        val samples = 6400
        val audio = WbfmDemodulator(rate).process(toneIq(-32_000.0, samples), samples)
        for (i in 50 until audio.size) {
            assertTrue("sample $i = ${audio[i]}", audio[i] in -5500..-4500)
        }
    }

    @Test fun `rssi separates strong from quiet`() {
        val samples = 4096
        val strong = toneIq(10_000.0, samples)
        val quiet = ByteArray(2 * samples) { (if (it % 2 == 0) 127 else 128).toByte() }
        val d = WbfmDemodulator(rate)
        val hi = d.rssiDb(strong, samples)
        val lo = d.rssiDb(quiet, samples)
        assertTrue("hi=$hi lo=$lo", hi > lo + 20f)
    }
}
