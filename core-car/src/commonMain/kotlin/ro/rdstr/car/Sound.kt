package ro.rdstr.car

import kotlinx.serialization.Serializable

/** The equalizer's presets, with the codes the sound processor takes (Iop.DspSetEqm). */
@Serializable
enum class EqPreset(val code: Int) { USER(0), FLAT(1), POP(2), ROCK(3), JAZZ(4), CLASSIC(5) }

/**
 * Where the speakers' delays focus the sound (Iop.DspSetPosMode). CUSTOM uses [SoundSettings.delays]; FRONT and REAR are
 * the centre of each row, which the processor has no mode for: they go as mode 0 with delays from [Sound.focusDelays].
 */
@Serializable
enum class ListeningSeat(val code: Int) {
    CUSTOM(0), FRONT_LEFT(1), FRONT_RIGHT(2), REAR_LEFT(3), REAR_RIGHT(4), ALL(5), FRONT(0), REAR(0)
}

/** A high-pass and a low-pass on one pair of speakers (Iop.DspSetFilter). */
@Serializable
data class SpeakerFilter(
    val highPass: Boolean = false,
    val highPassHz: Int = Sound.HPF_HZ.first(),
    val lowPass: Boolean = false,
    val lowPassHz: Int = Sound.LPF_HZ.last(),
)

/**
 * The unit's sound processor, in the units the screen shows: the equalizer in dB per band, balance and fader in steps
 * from the centre, delays in tenths of a millisecond, frequencies in Hz.
 */
@Serializable
data class SoundSettings(
    val preset: EqPreset = EqPreset.FLAT,
    /** dB, −12..+12, one per [Sound.BANDS]. */
    val bands: List<Int> = List(Sound.BANDS.size) { 0 },
    /** −7 left .. +7 right. */
    val balance: Int = 0,
    /** +7 front .. −7 rear. */
    val fader: Int = 0,
    val seat: ListeningSeat = ListeningSeat.ALL,
    /** The CUSTOM seat's delays: front left, front right, rear left, rear right; 0..200 tenths of a millisecond. */
    val delays: List<Int> = List(4) { 0 },
    val loudness: Boolean = true,
    val loudLowHz: Int = 600,
    val loudLowGain: Int = 4,
    val loudHighHz: Int = 2000,
    val loudHighGain: Int = 4,
    val bass: Boolean = false,
    /** Virtual bass, 0..12 dB. */
    val bassGain: Int = 6,
    /** Subwoofer output level, 0..15 dB, and its cut-off, 80..240 Hz. */
    val subLevel: Int = 10,
    val subHz: Int = 240,
    val front: SpeakerFilter = SpeakerFilter(),
    val rear: SpeakerFilter = SpeakerFilter(),
    /** Power on the antenna lead, for an amplified radio antenna (FtSet.SetExAmp). */
    val antennaPower: Boolean = true,
    /** The phone call's level, 0..30 like the music (the vendor's Bluetooth channel, Iop.SetVolume(1, v)). */
    val callVolume: Int = 20,
    /** The navigation voice's level, 0..100 (StSet.SetNvol). */
    val navVolume: Int = 60,
    /** The music's level while the navigation speaks, 0..100 % of its own; 100 leaves it as it is (StSet.Setmvwns). */
    val musicUnderNav: Int = 40,
)

/** One call on the vendor's sound library, as MainUI makes it: Iop.DspSetEqBand(3, 14), FtSet.SetExAmp(1). */
@Serializable
data class DspWrite(val call: String, val args: List<Int>)

/**
 * The sound processor's ranges and the calls that bring it from one setting to the next. Tables and codes are the
 * vendor's own (MainUI com.ts.set.dsp, libts50xhw): 30 equalizer bands of 0..24 with 12 flat, balance and fader on a
 * 0..14 grid with 7 the centre and 0 the left and the front, filters written in Hz at offset 4 for the front pair and 8
 * for the rear, a filter switched off written back to its widest frequency.
 */
object Sound {
    val BANDS = listOf(
        "32", "64", "128", "256", "512", "1k", "1.5k", "2k", "2.5k", "3k", "3.5k", "4k", "4.5k", "5k", "5.5k",
        "6k", "7k", "8k", "9k", "10k", "11k", "12k", "13k", "14k", "15k", "16k", "17k", "18k", "19k", "20k",
    )
    const val BAND_DB = 12
    const val BALANCE_STEPS = 7
    const val DELAY_MAX = 200
    val HPF_HZ = listOf(20, 31, 50, 63, 80, 125, 180, 250, 325, 410, 500)
    val LPF_HZ = listOf(1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 20000)
    val LOUD_LOW_HZ = 50..1000
    val LOUD_HIGH_HZ = 2000..10000
    const val GAIN_DB = 12
    const val SUB_LEVEL = 15
    const val CALL_VOLUME = 30
    const val PERCENT = 100
    val SUB_HZ = 80..240
    private const val VBASS_RAW = 400
    private const val FRONT = 4
    private const val REAR = 8

    // Cabin geometry in metres, x across the car, y along it (0 at the front speakers, negative towards the rear).
    private const val SPEAKER_X = 0.72
    private const val REAR_SPEAKER_Y = -1.25
    private const val FRONT_SEAT_Y = -0.45
    private const val REAR_SEAT_Y = -1.35
    /** Speed of sound, m/s. */
    private const val SOUND_SPEED = 343.0

    /**
     * What the processor's own presets do, ten points from 32 Hz to 20 kHz spread over the 30 bands; the unit reads the
     * real curve back after a preset, this is what the screen shows until then.
     */
    private val SHAPES = mapOf(
        EqPreset.FLAT to listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        EqPreset.POP to listOf(-1, 1, 3, 4, 3, 1, 0, -1, -1, -2),
        EqPreset.ROCK to listOf(5, 4, 2, -1, -2, -1, 1, 3, 4, 5),
        EqPreset.JAZZ to listOf(3, 2, 1, 2, -1, -1, 0, 1, 2, 3),
        EqPreset.CLASSIC to listOf(4, 3, 2, 1, -1, -1, 0, 2, 3, 4),
    )

    fun presetBands(p: EqPreset): List<Int>? = SHAPES[p]?.let { shape ->
        List(BANDS.size) { i ->
            val at = i * (shape.size - 1).toDouble() / (BANDS.size - 1)
            val lo = at.toInt().coerceAtMost(shape.size - 2)
            val f = at - lo
            kotlin.math.round(shape[lo] * (1 - f) + shape[lo + 1] * f).toInt()
        }
    }

    /** A preset picked: its curve; a band moved: the curve becomes Custom. */
    fun withPreset(s: SoundSettings, p: EqPreset) = s.copy(preset = p, bands = presetBands(p) ?: s.bands)

    fun withBand(s: SoundSettings, i: Int, db: Int) =
        s.copy(preset = EqPreset.USER, bands = s.bands.mapIndexed { j, b -> if (j == i) db.coerceIn(-BAND_DB, BAND_DB) else b })

    /** Every value inside its range: what the screen sends and what a stored text from an older version holds. */
    fun clamp(s: SoundSettings) = s.copy(
        bands = List(BANDS.size) { (s.bands.getOrNull(it) ?: 0).coerceIn(-BAND_DB, BAND_DB) },
        balance = s.balance.coerceIn(-BALANCE_STEPS, BALANCE_STEPS),
        fader = s.fader.coerceIn(-BALANCE_STEPS, BALANCE_STEPS),
        delays = List(4) { (s.delays.getOrNull(it) ?: 0).coerceIn(0, DELAY_MAX) },
        loudLowHz = s.loudLowHz.coerceIn(LOUD_LOW_HZ), loudHighHz = s.loudHighHz.coerceIn(LOUD_HIGH_HZ),
        loudLowGain = s.loudLowGain.coerceIn(0, GAIN_DB), loudHighGain = s.loudHighGain.coerceIn(0, GAIN_DB),
        bassGain = s.bassGain.coerceIn(0, GAIN_DB), subLevel = s.subLevel.coerceIn(0, SUB_LEVEL), subHz = s.subHz.coerceIn(SUB_HZ),
        callVolume = s.callVolume.coerceIn(0, CALL_VOLUME), navVolume = s.navVolume.coerceIn(0, PERCENT),
        musicUnderNav = s.musicUnderNav.coerceIn(0, PERCENT),
    )

    /** The loudness and bass pages' reset, as the vendor's buttons do it. */
    fun resetLoudness(s: SoundSettings) = s.copy(loudness = true, loudLowHz = 600, loudHighHz = 2000, loudLowGain = 4, loudHighGain = 4)

    fun resetBass(s: SoundSettings) = s.copy(bass = true, bassGain = 6, subLevel = 10, subHz = 240)

    /**
     * FRONT and REAR: the delays, in tenths of a millisecond, that make all four speakers arrive together at the centre
     * of that row; the farthest speaker plays undelayed. Null for every other seat.
     */
    fun focusDelays(seat: ListeningSeat): List<Int>? {
        val y = when (seat) {
            ListeningSeat.FRONT -> FRONT_SEAT_Y
            ListeningSeat.REAR -> REAR_SEAT_Y
            else -> return null
        }
        val d = listOf(0.0, 0.0, REAR_SPEAKER_Y, REAR_SPEAKER_Y).map { sy -> kotlin.math.hypot(SPEAKER_X, sy - y) }
        val far = d.max()
        return d.map { kotlin.math.round((far - it) / SOUND_SPEED * 10_000).toInt().coerceIn(0, DELAY_MAX) }
    }

    /** The delays the processor holds for [s]'s seat; null for the vendor's own modes, whose delays are not known. */
    fun effectiveDelays(s: SoundSettings): List<Int>? =
        if (s.seat == ListeningSeat.CUSTOM) s.delays else focusDelays(s.seat)

    /** The calls that take the processor from [a] to [b]; only what changed is written. */
    fun writes(a: SoundSettings, b: SoundSettings): List<DspWrite> = buildList {
        fun w(call: String, vararg args: Int) = add(DspWrite(call, args.toList()))
        if (b.preset != a.preset && b.preset != EqPreset.USER) w("DspSetEqm", b.preset.code)
        if (b.preset == EqPreset.USER && b.bands != a.bands) {
            // a curve of its own: the processor takes it as the User preset, band by band
            if (a.preset != EqPreset.USER) w("DspSetEqm", EqPreset.USER.code)
            b.bands.forEachIndexed { i, db -> if (a.preset != EqPreset.USER || a.bands[i] != db) w("DspSetEqBand", i, db + BAND_DB) }
        }
        if (b.balance != a.balance) w("DspBalSet", b.balance + BALANCE_STEPS)
        if (b.fader != a.fader) w("DspFadSet", BALANCE_STEPS - b.fader)
        if (b.seat.code != a.seat.code) w("DspSetPosMode", b.seat.code)
        effectiveDelays(b)?.let { now ->
            // after a vendor mode the delays are unknown: all four are written
            val before = effectiveDelays(a)
            now.forEachIndexed { ch, v -> if (before?.getOrNull(ch) != v) w("DspSetPosDelay", ch, v) }
        }
        if (b.loudness != a.loudness) w("DspSetLoud", 0, if (b.loudness) 1 else 0)
        if (b.loudLowHz != a.loudLowHz) w("DspSetLoud", 1, b.loudLowHz)
        if (b.loudHighHz != a.loudHighHz) w("DspSetLoud", 2, b.loudHighHz)
        if (b.loudLowGain != a.loudLowGain) w("DspSetLoud", 3, b.loudLowGain)
        if (b.loudHighGain != a.loudHighGain) w("DspSetLoud", 4, b.loudHighGain)
        if (b.bass != a.bass) w("DspSetVBass", 0, if (b.bass) 1 else 0)
        if (b.bassGain != a.bassGain) w("DspSetVBass", 1, b.bassGain * VBASS_RAW / GAIN_DB)
        if (b.subLevel != a.subLevel) w("SubGainSet", b.subLevel)
        if (b.subHz != a.subHz) w("SubFreqSet", b.subHz)
        filter(FRONT, a.front, b.front, ::w)
        filter(REAR, a.rear, b.rear, ::w)
        if (b.antennaPower != a.antennaPower) w("SetExAmp", if (b.antennaPower) 1 else 0)
        if (b.callVolume != a.callVolume) w("SetVolume", 1, b.callVolume)
        if (b.navVolume != a.navVolume) w("SetNvol", b.navVolume)
        if (b.musicUnderNav != a.musicUnderNav) w("Setmvwns", b.musicUnderNav)
    }

    /** cmd 0 low-pass on, 1 high-pass on, 2 low-pass Hz, 3 high-pass Hz; switched off, the frequency goes back to its widest. */
    private fun filter(offset: Int, a: SpeakerFilter, b: SpeakerFilter, w: (String, IntArray) -> Unit) {
        if (b.lowPass != a.lowPass) w("DspSetFilter", intArrayOf(offset, if (b.lowPass) 1 else 0))
        if (b.highPass != a.highPass) w("DspSetFilter", intArrayOf(offset + 1, if (b.highPass) 1 else 0))
        val lpf = if (b.lowPass) b.lowPassHz else LPF_HZ.last()
        val lpfBefore = if (a.lowPass) a.lowPassHz else LPF_HZ.last()
        if (lpf != lpfBefore || b.lowPass != a.lowPass) w("DspSetFilter", intArrayOf(offset + 2, lpf))
        val hpf = if (b.highPass) b.highPassHz else HPF_HZ.first()
        val hpfBefore = if (a.highPass) a.highPassHz else HPF_HZ.first()
        if (hpf != hpfBefore || b.highPass != a.highPass) w("DspSetFilter", intArrayOf(offset + 3, hpf))
    }
}
