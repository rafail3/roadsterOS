package ro.rdstr.ui.home.clock

import ro.rdstr.ui.tokens.Bezier
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

// The home clock's arithmetic (emulator/components/Clock.tsx, emulator/styles/clock.css), free of Android so the JVM
// tests run it as it is.

internal const val NONE = Long.MIN_VALUE

/** Hand angles in degrees clockwise from 12, as the dial's `rotate()`s: seconds are whole, as `Date.getSeconds()`. */
internal object HandAngles {
    fun hour(h: Int, m: Int, s: Int): Float = ((h % 12) + (m + s / 60f) / 60f) * 30f
    fun minute(m: Int, s: Int): Float = (m + s / 60f) * 6f
    fun second(s: Int): Float = s * 6f
}

/** CSS `cubic-bezier()` output at input progress [t] 0..1 (x is monotonic for CSS curves, so bisection finds it). */
internal fun Bezier.at(t: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    var lo = 0f
    var hi = 1f
    var u = t
    repeat(30) {
        if (curve(x1, x2, u) < t) lo = u else hi = u
        u = (lo + hi) / 2
    }
    return curve(y1, y2, u)
}

private fun curve(p1: Float, p2: Float, u: Float): Float {
    val v = 1 - u
    return 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u
}

/** The digit roll's keyframes (clock.css `glyph-in`, `glyph-out`); each writes [dy, scale, opacity], dy a fraction of the glyph box's height. */
internal object Roll {
    /**
     * `glyph-in` at elapsed fraction [p] of `--count`. The animation's timing function eases each keyframe segment of
     * each property: transform runs 0%..100% in one segment, opacity 0%..45%..100% in two.
     */
    fun glyphIn(p: Float, ease: Bezier, out: FloatArray) {
        val e = ease.at(p)
        out[0] = 0.34f * (1 - e)
        out[1] = 0.96f + 0.04f * e
        out[2] = if (p < 0.45f) 0.8f * ease.at(p / 0.45f) else 0.8f + 0.2f * ease.at((p - 0.45f) / 0.55f)
    }

    /** `glyph-out` (linear) at elapsed fraction [p] of its `--count * 0.6`. */
    fun glyphOut(p: Float, out: FloatArray) {
        val q = p.coerceIn(0f, 1f)
        out[0] = -0.30f * q
        out[1] = 1 - 0.03f * q
        out[2] = if (q < 0.7f) 1 - q / 0.7f else 0f
    }
}

/**
 * The big clock's cells as Clock.tsx keeps them: one per character, keyed from the right (key 1 = last character), so
 * "12:59" to "1:00" drops the first cell and keeps the minutes'. A changed character rolls: the shown glyph leaves
 * (an out glyph, drawn over the cell) and the new one comes in; never on the first text.
 * Cell `key` lives at index `key - 1`.
 */
internal class DigitCells(private val inMs: Long, private val outMs: Long) {
    val char = CharArray(MAX)
    /** When the cell's glyph started rolling in, or [NONE] at rest. */
    val inStart = LongArray(MAX) { NONE }
    val outChar = Array(MAX) { CharArray(OUTS) }
    val outStart = Array(MAX) { LongArray(OUTS) { NONE } }
    var length = 0
        private set
    private var shown = false

    fun set(text: String, now: Long, animate: Boolean) {
        require(text.length <= MAX)
        val roll = shown && animate
        for (c in text.length until MAX) {
            inStart[c] = NONE
            outStart[c].fill(NONE)
        }
        for (i in text.indices) {
            val c = text.length - i - 1
            val ch = text[i]
            val existed = c < length
            if (existed && char[c] == ch) continue
            if (existed && roll) pushOut(c, char[c], now)
            char[c] = ch
            inStart[c] = if (roll) now else NONE
        }
        length = text.length
        shown = true
    }

    private fun pushOut(c: Int, ch: Char, now: Long) {
        val starts = outStart[c]
        var slot = 0
        for (j in 0 until OUTS) {
            if (starts[j] == NONE || now - starts[j] >= outMs) { slot = j; break }
            if (starts[j] < starts[slot]) slot = j
        }
        starts[slot] = now
        outChar[c][slot] = ch
    }

    /** A glyph of any cell is still moving at [now]. */
    fun moving(now: Long): Boolean {
        for (c in 0 until length) {
            if (inStart[c] != NONE && now - inStart[c] < inMs) return true
            for (s in outStart[c]) if (s != NONE && now - s < outMs) return true
        }
        return false
    }

    companion object {
        /** "12:59": the longest text. */
        const val MAX = 5
        /** Leaving glyphs kept per cell at once; a third replaces the oldest. */
        const val OUTS = 2
    }
}

/**
 * A dial hand with its `transition: transform 600ms`. The angle keeps turning past 360 so the hand always takes the
 * short way (59 to 00 goes forward); steps of the per-second size jump, as they move the tip by a fraction of a pixel.
 */
internal class Hand(private val ms: Long, private val ease: Bezier) {
    var target = 0f
        private set
    private var from = 0f
    private var start = NONE

    fun at(now: Long): Float {
        if (start == NONE || now - start >= ms) return target
        return from + (target - from) * ease.at((now - start).toFloat() / ms)
    }

    fun moving(now: Long) = start != NONE && now - start < ms

    /** Moves to [angle] (degrees, any turn); [animate] false places it there. */
    fun set(angle: Float, now: Long, animate: Boolean) {
        // at rest the turn count is dropped, so the angle stays small and precise
        if (!moving(now)) { target = ((target % 360f) + 360f) % 360f; start = NONE }
        val current = at(now)
        var delta = (angle - target) % 360f
        if (delta >= 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        if (delta == 0f) return
        val next = target + delta
        // ponytail: per-second steps (0.1 deg minute, 0.008 deg hour) jump instead of 36 frames a second; a 600 ms
        // ease on them moves the tip by under half a pixel
        if (animate && abs(next - current) > MIN_ANIMATED_STEP) { from = current; start = now } else start = NONE
        target = next
    }

    companion object {
        const val MIN_ANIMATED_STEP = 0.2f
    }
}

/** The dial's tick points (Clock.tsx `TICKS`): position [i] of 60 at radius [r], rounded to 2 decimals like `toFixed(2)`. */
internal object Ticks {
    fun x(i: Int, r: Int): Float = round2(240 + sin(i / 60.0 * Math.PI * 2) * r)
    fun y(i: Int, r: Int): Float = round2(240 - cos(i / 60.0 * Math.PI * 2) * r)
    private fun round2(v: Double) = (round(v * 100) / 100).toFloat()
}

/**
 * The text baseline from the top of a line box [lineHeight] tall, as Blink places it: the font's ascent and descent
 * ([ascent], [descent] per em, positive) rounded to whole pixels at [size], the leading split evenly.
 */
internal fun baseline(size: Float, lineHeight: Float, ascent: Float, descent: Float): Float {
    val a = (ascent * size).roundToInt()
    val d = (descent * size).roundToInt()
    return (lineHeight - (a + d)) / 2 + a
}

/** Colour between [a] and [b] (ARGB) at [t], interpolated with premultiplied alpha as CSS transitions do. */
internal fun mixColor(a: Int, b: Int, t: Float): Int {
    if (t <= 0f) return a
    if (t >= 1f) return b
    val aa = (a ushr 24) / 255f
    val ba = (b ushr 24) / 255f
    val alpha = aa + (ba - aa) * t
    if (alpha <= 0f) return 0
    return ((alpha * 255).roundToInt() shl 24) or (channel(a, b, 16, aa, ba, t, alpha) shl 16) or
        (channel(a, b, 8, aa, ba, t, alpha) shl 8) or channel(a, b, 0, aa, ba, t, alpha)
}

private fun channel(a: Int, b: Int, shift: Int, aa: Float, ba: Float, t: Float, alpha: Float): Int {
    val pa = ((a shr shift) and 0xFF) * aa
    val pb = ((b shr shift) and 0xFF) * ba
    return ((pa + (pb - pa) * t) / alpha).roundToInt().coerceIn(0, 255)
}

/** en-GB names as the date card shows them: short weekday uppercased (Calendar.DAY_OF_WEEK - 1), long month (Calendar.MONTH). */
internal val WEEKDAYS = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
internal val MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
