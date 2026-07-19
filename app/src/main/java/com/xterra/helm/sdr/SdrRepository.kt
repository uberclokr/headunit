package com.xterra.helm.sdr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** SDR operation mode. FM broadcast + NOAA weather radio; the rtl pipe will
 *  later host ADS-B (1090), AIS, pager decoders as extra modes. */
enum class SdrMode(val label: String) { FM("FM"), WX("WX") }

data class SdrState(
    val running: Boolean = false,           // driver on/off (user-controlled)
    val connected: Boolean = false,         // IQ link actually up
    val mode: SdrMode = SdrMode.FM,
    val freqHz: Long = 98_100_000,
    val rssiDb: Float = -60f,
    val scanning: Boolean = false,
    val autoTuning: Boolean = false,
    val stations: List<Long> = emptyList(), // found by band scan
    val wxAlert: String? = null,            // SAME header / WAT notice
    val wxAlertAtMs: Long = 0,
)

/**
 * FM browser + spectrum source. One coroutine owns the IQ stream and
 * fans out: audio (WBFM), spectrum rows (FFT) for the waterfall, RSSI.
 * Band-scan steps 200 kHz across 88–108 MHz and records carriers above
 * threshold — tap a found station chip to tune it.
 */
class SdrRepository(private val context: android.content.Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: IqSource = RtlTcpClient()

    private val _state = MutableStateFlow(SdrState())
    val state: StateFlow<SdrState> = _state

    /** Latest FFT row (dB, fft-shifted) for the waterfall composable. */
    val spectrum = MutableStateFlow(FloatArray(FFT_SIZE))

    private var job: Job? = null
    private var track: AudioTrack? = null

    fun start(host: String = "127.0.0.1") {
        if (job != null) return
        _state.value = _state.value.copy(running = true)
        job = scope.launch {
            try {
                // Local dongle beats the network: in-app driver when present.
                client = if (RtlUsbSource.donglePresent(context))
                    RtlUsbSource(context) else RtlTcpClient(host)
                client.open()
                client.setFrequency(_state.value.freqHz)
                _state.value = _state.value.copy(connected = true)
                runLoop()
            } catch (e: Exception) {
                _state.value = _state.value.copy(connected = false)
                job = null
            }
        }
    }

    /**
     * Auto-tune: nudge around the current frequency and lock to the strongest
     * carrier. Corrects the RTL crystal's ppm drift that leaves some stations
     * a few tens of kHz off-center. Reuses the run-loop's RSSI (single reader)
     * by retuning and sampling the published value.
     */
    fun autoTune() = scope.launch {
        if (!client.connected || _state.value.autoTuning) return@launch
        _state.value = _state.value.copy(autoTuning = true)
        val center = _state.value.freqHz
        var bestF = center
        var bestR = Float.NEGATIVE_INFINITY
        var off = -100_000L
        while (off <= 100_000L) {
            val f = center + off
            client.setFrequency(f)
            delay(180)                       // let the loop retune + recompute RSSI
            val r = _state.value.rssiDb
            if (r > bestR) { bestR = r; bestF = f }
            off += 10_000L
        }
        client.setFrequency(bestF)
        _state.value = _state.value.copy(freqHz = bestF, autoTuning = false)
    }

    private suspend fun runLoop() {
        val wbfm = WbfmDemodulator(client.sampleRate)
        var nbfm = NbfmDemodulator(client.sampleRate)
        var same = SameDecoder()
        var mode = _state.value.mode
        val chunk = ByteArray(CHUNK_IQ * 2)
        val fftRow = FloatArray(FFT_SIZE)
        openAudio()
        var n = 0
        while (currentCoroutineContext().isActive) {
            if (!client.readFully(chunk, chunk.size)) break
            if (_state.value.mode != mode) {         // mode switch: fresh state
                mode = _state.value.mode
                nbfm = NbfmDemodulator(client.sampleRate)
                same = SameDecoder()
            }
            val audio = when (mode) {
                SdrMode.FM -> wbfm.process(chunk, CHUNK_IQ)
                SdrMode.WX -> nbfm.process(chunk, CHUNK_IQ).also { a ->
                    same.feed(a)?.let { alert ->
                        android.util.Log.w(TAG, "WX ALERT: $alert")
                        _state.value = _state.value.copy(
                            wxAlert = alert, wxAlertAtMs = System.currentTimeMillis())
                    }
                }
            }
            track?.write(audio, 0, audio.size)
            if (n++ % 4 == 0) {
                Fft.powerDb(chunk, FFT_SIZE, fftRow)
                spectrum.value = fftRow.copyOf()
                _state.value = _state.value.copy(rssiDb = wbfm.rssiDb(chunk, CHUNK_IQ))
            }
        }
        _state.value = _state.value.copy(connected = false)
    }

    /** Switch FM ↔ WX; remembers the last frequency used in each mode. */
    fun setMode(m: SdrMode) {
        if (m == _state.value.mode) return
        when (_state.value.mode) {
            SdrMode.FM -> lastFm = _state.value.freqHz
            SdrMode.WX -> lastWx = _state.value.freqHz
        }
        val f = when (m) { SdrMode.FM -> lastFm; SdrMode.WX -> lastWx }
        _state.value = _state.value.copy(mode = m)
        tune(f)
    }

    fun clearWxAlert() { _state.value = _state.value.copy(wxAlert = null) }

    private var lastFm = 98_100_000L
    private var lastWx = WX_CHANNELS[6]     // 162.550 — most common allocation

    fun tune(hz: Long) {
        client.setFrequency(hz)
        _state.value = _state.value.copy(freqHz = hz)
    }

    fun step(khz: Int) = tune(_state.value.freqHz + khz * 1000L)

    fun scanFmBand() = scope.launch {
        if (!client.connected) return@launch
        _state.value = _state.value.copy(scanning = true, stations = emptyList())
        val found = mutableListOf<Long>()
        val demod = WbfmDemodulator(client.sampleRate)
        val buf = ByteArray(CHUNK_IQ * 2)
        var f = 88_100_000L
        while (f <= 107_900_000L && currentCoroutineContext().isActive) {
            client.setFrequency(f); delay(60) // tuner settle
            if (client.readFully(buf, buf.size)) {
                if (demod.rssiDb(buf, CHUNK_IQ) > SCAN_THRESHOLD_DB) found += f
            }
            _state.value = _state.value.copy(stations = found.toList())
            f += 200_000
        }
        client.setFrequency(_state.value.freqHz)
        _state.value = _state.value.copy(scanning = false)
    }

    private fun openAudio() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            WbfmDemodulator.AUDIO_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(WbfmDemodulator.AUDIO_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(minBuf * 4)
            .build().also { it.play() }
    }

    fun stop() {
        job?.cancel(); job = null
        track?.stop(); track?.release(); track = null
        client.close()
        // Releases the USB dongle / rtl_tcp socket so the driver draws nothing
        // when the user isn't listening.
        _state.value = _state.value.copy(running = false, connected = false)
    }

    companion object {
        const val FFT_SIZE = 1024
        const val CHUNK_IQ = 16384         // IQ pairs per read (~16 ms @1.024M)
        const val SCAN_THRESHOLD_DB = -25f
        private const val TAG = "Helm"
        /** NWR channels WX1–WX7 (162.400–162.550, 25 kHz spacing). */
        val WX_CHANNELS = longArrayOf(
            162_400_000, 162_425_000, 162_450_000, 162_475_000,
            162_500_000, 162_525_000, 162_550_000,
        )
    }
}
