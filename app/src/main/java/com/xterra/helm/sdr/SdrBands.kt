package com.xterra.helm.sdr

/** How a band's audio is recovered from IQ. */
enum class Demod { WBFM, NBFM, AM }

/** A named tuning target within a band (a channel or a landmark frequency). */
data class SdrChannel(val label: String, val hz: Long)

/**
 * A receive band: a demodulator + either a fixed channel plan (FRS, GMRS, CB…)
 * or continuous tuning (broadcast FM/AM, aviation). [default] is where the band
 * lands when first selected. Frequencies below ~24 MHz (AM broadcast) fall under
 * the R820T tuner floor and are reached via direct sampling — SdrRepository sets
 * that automatically from the tuned frequency.
 */
data class SdrBand(
    val id: String,
    val label: String,
    val demod: Demod,
    val default: Long,
    val channels: List<SdrChannel> = emptyList(),
    val continuous: Boolean = false,   // show fine-tune (+ scan for FM)
    val stepKhz: Int = 25,             // fine-tune step, continuous bands
)

/** US voice-comms + broadcast band catalog for the RTL-SDR pane. */
object SdrBands {
    private fun mhz(m: Double) = (m * 1_000_000).toLong()

    // FRS/GMRS 462/467 MHz shared plan (ch1–22).
    private val FRS_1_22 = listOf(
        "1" to 462.5625, "2" to 462.5875, "3" to 462.6125, "4" to 462.6375,
        "5" to 462.6625, "6" to 462.6875, "7" to 462.7125,
        "8" to 467.5625, "9" to 467.5875, "10" to 467.6125, "11" to 467.6375,
        "12" to 467.6625, "13" to 467.6875, "14" to 467.7125,
        "15" to 462.5500, "16" to 462.5750, "17" to 462.6000, "18" to 462.6250,
        "19" to 462.6500, "20" to 462.6750, "21" to 462.7000, "22" to 462.7250,
    ).map { SdrChannel(it.first, mhz(it.second)) }

    // GMRS repeater INPUTS (monitor uplink traffic); outputs are ch15–22.
    private val GMRS_RPT = listOf(
        "RPT15" to 467.5500, "RPT16" to 467.5750, "RPT17" to 467.6000, "RPT18" to 467.6250,
        "RPT19" to 467.6500, "RPT20" to 467.6750, "RPT21" to 467.7000, "RPT22" to 467.7250,
    ).map { SdrChannel(it.first, mhz(it.second)) }

    private val CB = listOf(
        26.965, 26.975, 26.985, 27.005, 27.015, 27.025, 27.035, 27.055,
        27.065, 27.075, 27.085, 27.105, 27.115, 27.125, 27.135, 27.155,
        27.165, 27.175, 27.185, 27.205, 27.215, 27.225, 27.255, 27.235,
        27.245, 27.265, 27.275, 27.285, 27.295, 27.305, 27.315, 27.325,
        27.335, 27.345, 27.355, 27.365, 27.375, 27.385, 27.395, 27.405,
    ).mapIndexed { i, f ->
        val n = i + 1
        val tag = when (n) { 9 -> "9·EMG"; 19 -> "19·HWY"; else -> "$n" }
        SdrChannel(tag, mhz(f))
    }

    private val MURS = listOf(
        "M1" to 151.820, "M2" to 151.880, "M3" to 151.940,
        "BLUE" to 154.570, "GREEN" to 154.600,
    ).map { SdrChannel(it.first, mhz(it.second)) }

    private val MARINE = listOf(
        "16·HAIL" to 156.800, "06" to 156.300, "09" to 156.450, "13" to 156.650,
        "22A·USCG" to 157.100, "68" to 156.425, "69" to 156.475, "71" to 156.575,
        "72" to 156.625, "78A" to 156.925,
    ).map { SdrChannel(it.first, mhz(it.second)) }

    private val WX = listOf(
        "WX1" to 162.400, "WX2" to 162.425, "WX3" to 162.450, "WX4" to 162.475,
        "WX5" to 162.500, "WX6" to 162.525, "WX7" to 162.550,
    ).map { SdrChannel(it.first, mhz(it.second)) }

    private val AIR = listOf(
        "GUARD" to 121.500, "CTAF" to 122.800, "MULTI" to 122.900, "GLDR" to 123.000,
        "A-A" to 122.750, "UNICOM" to 122.700, "123.45" to 123.450,
    ).map { SdrChannel(it.first, mhz(it.second)) }

    private val HAM2M = listOf(
        "CALL" to 146.520, "146.55" to 146.550, "146.58" to 146.580,
        "147.42" to 147.420, "147.48" to 147.480, "147.585" to 147.585,
    ).map { SdrChannel(it.first, mhz(it.second)) }

    private val HAM70 = listOf(
        "CALL" to 446.000, "446.025" to 446.025, "446.05" to 446.050,
        "446.075" to 446.075, "446.10" to 446.100,
    ).map { SdrChannel(it.first, mhz(it.second)) }

    val ALL = listOf(
        SdrBand("fm", "FM", Demod.WBFM, 98_100_000, continuous = true, stepKhz = 100),
        SdrBand("am", "AM", Demod.AM, 1_000_000, continuous = true, stepKhz = 10),
        SdrBand("air", "AIR", Demod.AM, 122_800_000, AIR, continuous = true, stepKhz = 25),
        SdrBand("wx", "WX", Demod.NBFM, 162_550_000, WX),
        SdrBand("frs", "FRS", Demod.NBFM, mhz(462.5625), FRS_1_22),
        SdrBand("gmrs", "GMRS", Demod.NBFM, mhz(462.5500), FRS_1_22 + GMRS_RPT),
        SdrBand("murs", "MURS", Demod.NBFM, mhz(151.820), MURS),
        SdrBand("marine", "MARINE", Demod.NBFM, mhz(156.800), MARINE),
        SdrBand("cb", "CB", Demod.AM, mhz(27.185), CB),
        SdrBand("ham2m", "2M", Demod.NBFM, 146_520_000, HAM2M, continuous = true, stepKhz = 25),
        SdrBand("ham70", "70CM", Demod.NBFM, 446_000_000, HAM70, continuous = true, stepKhz = 25),
    )

    val DEFAULT = ALL.first()
    fun byId(id: String): SdrBand = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
