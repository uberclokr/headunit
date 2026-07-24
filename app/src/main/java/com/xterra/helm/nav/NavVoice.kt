package com.xterra.helm.nav

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.xterra.helm.nav.route.Guidance
import com.xterra.helm.nav.route.Maneuver
import java.util.Locale

/**
 * Offline turn-by-turn voice prompts via Android's on-device TTS (no network —
 * the vehicle's traffic profile stays quiet). Fully event-driven: [announce] is
 * fed each fresh [Guidance] and decides when to speak, de-duplicating so each
 * maneuver is voiced at most twice — a "prime" prompt as it comes into range
 * and a "now" prompt at the turn — plus a one-shot arrival prompt.
 *
 * State is keyed off the upcoming step's polyline index, which is stable across
 * fixes and changes exactly when the next maneuver does.
 */
class NavVoice(context: Context) {
    @Volatile private var ready = false
    @Volatile var muted = false

    // Explicitly-typed nullable so the init lambda can reference it without a
    // recursive type-inference cycle; assigned before onInit ever fires.
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts?.language = Locale.US }
                ready = true
            } else Log.w("Helm", "TTS init failed ($status)")
        }
    }

    // Which step we've primed / voiced-now, and whether arrival was announced.
    private var primedKey = Int.MIN_VALUE
    private var nowKey = Int.MIN_VALUE
    private var arrivedSpoken = false

    fun announce(g: Guidance?) {
        if (g == null) return
        if (g.arrived) {
            if (!arrivedSpoken) { arrivedSpoken = true; say("You have arrived.") }
            return
        }
        arrivedSpoken = false
        val step = g.nextStep ?: return
        val key = step.polyIndex
        val onto = if (step.street.isNotBlank()) " onto ${step.street}" else ""
        val d = g.distanceToNextM
        // Prime once when the maneuver first enters ~0.3 mi; "now" at ~90 m.
        if (key != primedKey && d in 120.0..500.0) {
            primedKey = key
            if (step.maneuver != Maneuver.CONTINUE)
                say("In ${spokenDist(d)}, ${step.maneuver.phrase().lowercase()}$onto.")
        }
        if (key != nowKey && d <= 90.0) {
            nowKey = key
            say("${step.maneuver.phrase()}$onto.")
        }
    }

    /** Called on reroute so the next maneuver primes cleanly and we say it. */
    fun onReroute() {
        primedKey = Int.MIN_VALUE; nowKey = Int.MIN_VALUE
        say("Rerouting.")
    }

    /** Reset per new trip. */
    fun reset() {
        primedKey = Int.MIN_VALUE; nowKey = Int.MIN_VALUE; arrivedSpoken = false
    }

    private fun spokenDist(m: Double): String {
        val ft = m * 3.28084
        return when {
            ft < 1000 -> "${(ft / 100).toInt() * 100} feet"
            else -> "%.1f miles".format(m / 1609.344)
        }
    }

    private fun say(text: String) {
        if (!ready || muted) return
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString()) }
    }

    fun shutdown() = runCatching { tts?.stop(); tts?.shutdown() }
}
