package ro.rdstr.ui.sound

import ro.rdstr.car.EqPreset
import ro.rdstr.car.ListeningSeat
import ro.rdstr.car.Sound
import ro.rdstr.car.SoundSettings
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

// The sound window's decisions and geometry (SoundApp.tsx, CarTop.tsx), free of Android so they run in JVM tests.

/** SoundApp.tsx `SECTIONS`: id, label, tint; the icon is the view's. */
enum class SoundSection(val label: String, val tint: Int) {
    EQ("Equalizer", 0xFF0A84FF.toInt()),
    BALANCE("Balance", 0xFF5E5CE6.toInt()),
    FIELD("Listening Position", 0xFF0071A4.toInt()),
    LOUDNESS("Loudness", 0xFFC93400.toInt()),
    BASS("Smart Bass", 0xFFD30F45.toInt()),
    FILTERS("Speaker Filters", 0xFF248A3D.toInt()),
}

object SoundModel {
    const val BAND_DB = Sound.BAND_DB
    const val BALANCE = Sound.BALANCE_STEPS
    const val BANDS_PER_PAGE = 10

    val PRESETS = listOf(
        EqPreset.FLAT to "Flat", EqPreset.POP to "Pop", EqPreset.ROCK to "Rock",
        EqPreset.JAZZ to "Jazz", EqPreset.CLASSIC to "Classic", EqPreset.USER to "Custom",
    )
    val SEATS = listOf(
        ListeningSeat.FRONT_LEFT to "Front Left", ListeningSeat.FRONT_RIGHT to "Front Right",
        ListeningSeat.REAR_LEFT to "Rear Left", ListeningSeat.REAR_RIGHT to "Rear Right",
    )
    val SPEAKERS = listOf("Front Left", "Front Right", "Rear Left", "Rear Right")

    /** SoundApp.tsx `FOCUS`: the rows of seats the delays can focus on. */
    class Focus(val seat: ListeningSeat, val label: String, val seats: List<ListeningSeat>)
    val FOCUS = listOf(
        Focus(ListeningSeat.ALL, "Center", listOf(ListeningSeat.FRONT_LEFT, ListeningSeat.FRONT_RIGHT, ListeningSeat.REAR_LEFT, ListeningSeat.REAR_RIGHT)),
        Focus(ListeningSeat.FRONT, "Front", listOf(ListeningSeat.FRONT_LEFT, ListeningSeat.FRONT_RIGHT)),
        Focus(ListeningSeat.REAR, "Rear", listOf(ListeningSeat.REAR_LEFT, ListeningSeat.REAR_RIGHT)),
        Focus(ListeningSeat.CUSTOM, "Custom", emptyList()),
    )

    private fun range(from: Int, to: Int, step: Int) = List((to - from) / step + 1) { from + it * step }
    val LOW_HZ = range(50, 1000, 50)
    val HIGH_HZ = range(2000, 10000, 500)
    val GAINS = range(0, 12, 1)
    val SUB_LEVELS = range(0, 15, 1)
    val SUB_HZ = range(80, 240, 10)
    val DELAYS = range(0, 200, 1)
    val SCALE = listOf("20", "100", "500", "2k", "8k", "20k")

    /** JavaScript's Math.round: halves go up. */
    fun jsRound(v: Double): Int = floor(v + 0.5).toInt()

    /** `hz(v)`: "600 Hz", "2 kHz", "1.25 kHz". */
    fun hz(v: Int): String =
        if (v >= 1000) BigDecimal(v).divide(BigDecimal(1000)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " kHz"
        else "$v Hz"

    fun signed(v: Int) = if (v > 0) "+$v" else "$v"
    fun db(v: Int) = "$v dB"
    /** `ms(t)`: tenths of a millisecond with one decimal. */
    fun ms(tenths: Int) = "${tenths / 10}.${tenths % 10} ms"
    /** The delay row's value: 0.1 ms is about 3.4 cm. */
    fun cm(tenths: Int) = "${jsRound(tenths * 3.4)} cm"

    /** `summary(id, s)`: what the list shows beside each section. */
    fun summary(id: SoundSection, s: SoundSettings): String = when (id) {
        SoundSection.EQ -> PRESETS.first { it.first == s.preset }.second
        SoundSection.BALANCE -> if (s.balance == 0 && s.fader == 0) "Centered" else listOfNotNull(
            s.balance.takeIf { it != 0 }?.let { "${if (it < 0) "Left" else "Right"} ${abs(it)}" },
            s.fader.takeIf { it != 0 }?.let { "${if (it > 0) "Front" else "Rear"} ${abs(it)}" },
        ).joinToString(", ")
        SoundSection.FIELD -> when (s.seat) {
            ListeningSeat.ALL -> "Center"
            ListeningSeat.FRONT -> "Front Row"
            ListeningSeat.REAR -> "Rear Row"
            ListeningSeat.CUSTOM -> "Custom"
            else -> SEATS.first { it.first == s.seat }.second
        }
        SoundSection.LOUDNESS -> if (s.loudness) "On" else "Off"
        SoundSection.BASS -> if (s.bass) "+${s.bassGain} dB" else "Off"
        SoundSection.FILTERS -> listOf(s.front.highPass, s.front.lowPass, s.rear.highPass, s.rear.lowPass).count { it }.let { if (it > 0) "$it On" else "Off" }
    }

    /** The balance readout: "Center", "Left 3", "Right 2". */
    fun balanceText(b: Int) = if (b == 0) "Center" else "${if (b < 0) "Left" else "Right"} ${abs(b)}"
    fun faderText(f: Int) = if (f == 0) "Center" else "${if (f > 0) "Front" else "Rear"} ${abs(f)}"

    /** `Steps`: the index of the value nearest [value] (the first of equals), where a stored value off the list sits. */
    fun nearest(values: List<Int>, value: Int): Int {
        var best = 0
        for (k in values.indices) if (abs(values[k] - value) < abs(values[best] - value)) best = k
        return best
    }

    /** The bands-shown options: "32–3k Hz", "3.5k–10k Hz", "11k–20k Hz". */
    fun pages(labels: List<String> = Sound.BANDS): List<String> {
        val n = (labels.size + BANDS_PER_PAGE - 1) / BANDS_PER_PAGE
        return List(n) { p -> "${labels[p * BANDS_PER_PAGE]}–${labels[minOf(labels.size, (p + 1) * BANDS_PER_PAGE) - 1]} Hz" }
    }

    /** `BandSlider` `at(y)`: the band's dB at [f], the fraction of the track from its bottom. */
    fun bandAt(f: Float): Int = jsRound(f.coerceIn(0f, 1f) * 2.0 * BAND_DB - BAND_DB)

    /** Where the knob sits on the track, 0 at the bottom. */
    fun bandPos(v: Int): Float = (v + BAND_DB) / (2f * BAND_DB)

    /** `Balance` `at(x, y)`: balance and fader at the fractions of the pad [fx] (from its left) and [fy] (from its top). */
    fun balanceAt(fx: Float, fy: Float): Pair<Int, Int> {
        fun step(f: Float) = jsRound((f.coerceIn(0f, 1f) * 2.0 - 1) * BALANCE)
        return step(fx) to -step(fy)
    }

    /** The dot's place on the pad, fractions from its left and top. */
    fun dotX(balance: Int) = (balance + BALANCE) / (2f * BALANCE)
    fun dotY(fader: Int) = (BALANCE - fader) / (2f * BALANCE)

    /** `lit(seat)`: the row's seats when the seat is one of FOCUS, else that one seat. */
    fun lit(s: SoundSettings, seat: ListeningSeat): Boolean =
        FOCUS.firstOrNull { it.seat == s.seat }?.seats?.contains(seat) ?: (s.seat == seat)

    // Curve: viewBox 1000 x 160, the bands' line and fill

    const val CURVE_W = 1000f
    const val CURVE_H = 160f

    /** The curve's points: x, y of each band in the viewBox (into [out], 2 per band). */
    fun curvePoints(values: List<Int>, out: FloatArray) {
        val n = values.size
        for (i in 0 until n) {
            out[2 * i] = (i + 0.5f) * (CURVE_W / n)
            out[2 * i + 1] = CURVE_H / 2 - (values[i].toFloat() / BAND_DB) * (CURVE_H / 2 - 10)
        }
    }

    /** `Response`: x of a frequency on the log axis, 20 Hz at 0 and 20 kHz at 1000. */
    fun responseX(hz: Int): Float = (log10(hz / 20.0) / 3 * 1000).toFloat()

    /** The response's corners: where the high-pass and the low-pass bend (far outside when off). */
    fun responseLo(highPass: Boolean, hz: Int) = if (highPass) responseX(hz) else -400f
    fun responseHi(lowPass: Boolean, hz: Int) = if (lowPass) responseX(hz) else 1400f

    // CarTop.tsx: the drawing's space and the speakers' levels

    const val CAR_W = 459f
    const val CAR_H = 800f
    const val PICTURE_X = 50f
    const val PICTURE_W = 359f
    /** CAR_SEATS: x, y, w, h per seat in the speaker order. */
    val CAR_SEATS = listOf(
        floatArrayOf(172f, 336f, 54f, 80f), floatArrayOf(234f, 336f, 54f, 80f),
        floatArrayOf(172f, 464f, 54f, 80f), floatArrayOf(234f, 464f, 54f, 80f),
    )
    val CAR_SPEAKERS = listOf(floatArrayOf(100f, 382f), floatArrayOf(359f, 382f), floatArrayOf(100f, 512f), floatArrayOf(359f, 512f))
    const val SPEAKER_W = 54f
    const val SPEAKER_H = 57f
    /** The waves' half-angle, π/4.2, in degrees. */
    const val WAVE_HALF_DEG = 180f / 4.2f

    /** `speakerLevels(balance, fader)`: 1 in the centre, towards 0 away from it; FL, FR, RL, RR. */
    fun speakerLevels(balance: Int, fader: Int): FloatArray {
        val left = if (balance > 0) 1f - balance / 7f else 1f
        val right = if (balance < 0) 1f + balance / 7f else 1f
        val front = if (fader < 0) 1f + fader / 7f else 1f
        val rear = if (fader > 0) 1f - fader / 7f else 1f
        return floatArrayOf(left * front, right * front, left * rear, right * rear)
    }
}
