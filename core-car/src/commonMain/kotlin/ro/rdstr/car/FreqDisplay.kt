package ro.rdstr.car

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * How a frequency is drawn wherever it shows (the radio app, its home card page, the map's sound strip): written in
 * the band's unit, gliding to a new tuning instead of jumping, its digits rolling like a mechanical counter.
 */
object FreqDisplay {
    /** Each frame the shown frequency covers this share of the way left to the tuned one, and lands when this close. */
    const val GLIDE = 0.2
    const val SETTLE_KHZ = 0.5

    /** kHz on AM; MHz on FM, with a second decimal only off the 100 kHz grid. */
    fun format(band: CarEvent.Radio.Band, khz: Int): String =
        if (band == CarEvent.Radio.Band.AM) khz.toString()
        else {
            val tenths = khz % 100 == 0
            val whole = khz / 1000
            val rest = khz % 1000
            if (tenths) "$whole.${rest / 100}" else "$whole.${(rest / 10).toString().padStart(2, '0')}"
        }

    /** One frame of the glide from the frequency shown to the tuned one, in kHz, not rounded. */
    fun glide(shown: Double, target: Int): Double {
        val left = target - shown
        return if (abs(left) < SETTLE_KHZ) target.toDouble() else shown + left * GLIDE
    }

    /** One digit of the counter: its place from the right, and how far its 0-9 strip has turned (fractional). */
    data class Column(val place: Int, val offset: Double)

    /**
     * The counter for the frequency shown ([khz], gliding) on its way to [target]: [exact] is the text to draw as it is
     * when the count has settled off the FM tenths grid (95.35); otherwise [columns], with a point before the digit at
     * [decimalAt] places from the right (FM counts in tenths of a MHz). The last digit moves continuously, the ones
     * before it only while a carry passes, as 101.9 becomes 102.0.
     */
    data class Counter(val exact: String?, val label: String, val columns: List<Column>, val decimalAt: Int)

    fun counter(band: CarEvent.Radio.Band, khz: Double, target: Int): Counter {
        val label = format(band, target)
        val fm = band == CarEvent.Radio.Band.FM
        if (khz == target.toDouble() && fm && target % 100 != 0) return Counter(label, label, emptyList(), 0)
        val units = if (fm) khz / 100 else khz
        val whole = floor(units + 1e-6).toLong()
        val frac = max(0.0, units - whole)
        val digits = whole.toString()
        val columns = digits.mapIndexed { i, d ->
            val place = digits.length - 1 - i
            val carry = if (place == 0 || digits.substring(i + 1).all { it == '9' }) frac else 0.0
            Column(place, (d - '0') + carry)
        }
        return Counter(null, label, columns, if (fm) 1 else 0)
    }
}
