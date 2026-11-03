package ro.rdstr.car

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Two background colours taken from a cover: the average of its top edge and of its bottom edge,
 * saturated a little and darkened until white text reads on them. The screen fades from one to the other.
 */
object CoverColors {
    const val FALLBACK_TOP = 0xFF2C2C2E.toInt()
    const val FALLBACK_BOTTOM = 0xFF121214.toInt()
    const val MAX_LUMINANCE = 0.183
    private const val EDGE = 0.15
    private const val SATURATION = 1.15
    private const val DARKEN_STEP = 0.98

    /** [pixels] are ARGB, row by row, [width] wide; a small decode of the cover (about 48 px) is enough. */
    fun from(pixels: IntArray, width: Int): Pair<Int, Int> {
        if (width <= 0 || pixels.size < width) return FALLBACK_TOP to FALLBACK_BOTTOM
        val height = pixels.size / width
        val rows = max(1, (height * EDGE).roundToInt())
        val top = average(pixels, width, 0 until rows)
        val bottom = average(pixels, width, height - rows until height)
        if (top == null || bottom == null) return FALLBACK_TOP to FALLBACK_BOTTOM
        return shade(top, scale = 0.80, cap = 0.47) to shade(bottom, scale = 0.50, cap = 0.28)
    }

    fun hex(color: Int): String = "#" + (color and 0xFFFFFF).toString(16).padStart(6, '0')

    /** Relative luminance, 0 for black to 1 for white. */
    fun luminance(color: Int): Double {
        fun channel(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel((color shr 16) and 0xFF) + 0.7152 * channel((color shr 8) and 0xFF) + 0.0722 * channel(color and 0xFF)
    }

    /** Mean of the visible pixels in [rows], or null when they are all transparent. */
    private fun average(pixels: IntArray, width: Int, rows: IntRange): Int? {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in rows) for (x in 0 until width) {
            val p = pixels[y * width + x]
            if ((p ushr 24) < 128) continue
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
        if (n == 0) return null
        return rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun shade(color: Int, scale: Double, cap: Double): Int {
        val (h, s, v) = hsv(color)
        var c = fromHsv(h, min(1.0, s * SATURATION), min(cap, v * scale))
        while (luminance(c) > MAX_LUMINANCE) {
            c = rgb(
                (((c shr 16) and 0xFF) * DARKEN_STEP).toInt(),
                (((c shr 8) and 0xFF) * DARKEN_STEP).toInt(),
                ((c and 0xFF) * DARKEN_STEP).toInt(),
            )
        }
        return c
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun hsv(color: Int): Triple<Double, Double, Double> {
        val r = ((color shr 16) and 0xFF) / 255.0
        val g = ((color shr 8) and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val d = high - low
        val h = when {
            d == 0.0 -> 0.0
            high == r -> 60 * (((g - b) / d) % 6)
            high == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }.let { if (it < 0) it + 360 else it }
        return Triple(h, if (high == 0.0) 0.0 else d / high, high)
    }

    private fun fromHsv(h: Double, s: Double, v: Double): Int {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60) % 2 - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60).toInt() % 6) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }
}
