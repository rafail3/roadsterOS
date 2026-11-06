package ro.rdstr.ui.kit

import ro.rdstr.ui.tokens.Shadow
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The glisse library's own values (glisse 0.5.0, `dist/styles.css`, `:where([data-theme])`), which the Glassy
 * controls draw with. The emulator sets `--lg-fill` to the accent (glassy.css, `Glassy.lgFill`); the rest is glisse's.
 */
class GlisseTokens(
    val glass: Int,
    val edge: Int,
    val drop: Int,
    val lens: Int,
    val lensDrop: Int,
    val segmentTrack: Int,
    val segmentThumb: Int,
    val segmentLabel: Int,
    val segmentLens: Int,
    val track: Int,
    val rail: Int,
    val on: Int,
    /** `--lg-rim-shine`. */
    val rimShine: Float,
    /** `--lg-lens-shine` < 0: a light page, where the lifted lens takes the bright rim and sits under the labels. */
    val lightPage: Boolean,
) {
    companion object {
        val LIGHT = GlisseTokens(
            glass = 0x99FFFFFF.toInt(), edge = 0x14000000, drop = 0x0F000000, lens = 0x08000000, lensDrop = 0x1C000000,
            segmentTrack = 0x1F767680, segmentThumb = 0xFFFFFFFF.toInt(), segmentLabel = 0xFF000000.toInt(), segmentLens = 0x08000000,
            track = 0x4D3B3B42, rail = 0x1A000000, on = 0xFF34C759.toInt(), rimShine = 0.3f, lightPage = true,
        )
        val DARK = GlisseTokens(
            glass = 0x9E282828.toInt(), edge = 0x14FFFFFF, drop = 0, lens = 0x0EFFFFFF, lensDrop = 0,
            segmentTrack = 0, segmentThumb = 0x4DEBEBF5, segmentLabel = 0xFFFFFFFF.toInt(), segmentLens = 0,
            track = 0x4DEBEBF5, rail = 0x1AFFFFFF, on = 0xFF30D158.toInt(), rimShine = 0.13f, lightPage = false,
        )
        fun of(light: Boolean) = if (light) LIGHT else DARK

        /** `.lg-switch-thumb, .lg-slider-thumb` box-shadow, the glass knob of the switch and the slider. */
        val THUMB_SHADOW = listOf(
            Shadow(false, 0f, 6f, 24f, 0f, 0x33000000),
            Shadow(true, 0f, 0f, 0f, 1.5f, 0x80FFFFFF.toInt()),
            Shadow(true, 0f, 2f, 1f, 0f, 0x73FFFFFF),
            Shadow(true, 2f, 7f, 24f, 0f, 0x17000000),
            Shadow(true, -2f, -7f, 24f, 0f, 0x29FFFFFF),
        )

        /** Spring stiffness and damping of glisse's controls (switch.tsx, slider.tsx, segments.tsx). */
        const val SWITCH_K = 1000.0; const val SWITCH_D = 80.0
        const val LIFT_K = 2000.0; const val LIFT_D = 80.0
        val SEG_REST = doubleArrayOf(1024.0, 58.9, 676.0, 47.8)
        val SEG_DRAG = doubleArrayOf(2500.0, 94.0, 576.0, 44.2)

        /** A press counts as held, so the page no longer scrolls, once it moved or after 150 ms. */
        const val HOLD_MS = 150L
    }
}

/**
 * The lit rims glisse paints into images (`src/internal/refraction.ts` rimMap and `selectionMaps`), ported pixel for
 * pixel. Both depend only on the shape and the theme, so each is made once per size and kept.
 */
object GlisseMaps {
    private const val RIM_WIDTH = 1.22
    private const val EDGE_WIDTH = 0.5

    private fun distance(x: Double, y: Double, halfW: Double, halfH: Double, radius: Double): Double {
        val qx = abs(x) - halfW + radius
        val qy = abs(y) - halfH + radius
        return hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - radius
    }

    private fun clamp(v: Double, lo: Double, hi: Double) = min(hi, max(lo, v))

    /** Width and height in pixels of [rim]'s image. */
    fun rimSize(width: Float, height: Float, density: Float) =
        intArrayOf(max(1, (width * density).roundToInt()), max(1, (height * density).roundToInt()))

    /**
     * The rim of `.lg-glass`: white light along the edges facing the top-left and bottom-right, over a hairline of
     * [edge] (`--lg-edge`), for a [width] x [height] box of corner [radius], at [density] pixels to the px. ARGB.
     */
    fun rim(width: Float, height: Float, radius: Float, shine: Float, density: Float, edge: Int): IntArray {
        val (columns, rows) = rimSize(width, height, density).let { it[0] to it[1] }
        val out = IntArray(columns * rows)
        val e = doubleArrayOf((edge shr 16 and 0xFF).toDouble(), (edge shr 8 and 0xFF).toDouble(), (edge and 0xFF).toDouble(), (edge ushr 24) / 255.0)
        val w = width.toDouble(); val h = height.toDouble(); val d = density.toDouble()
        val halfW = w / 2; val halfH = h / 2
        val corner = clamp(radius.toDouble(), 0.0, min(halfW, halfH))
        val half = 0.5 / d
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = (column + 0.5) / columns * w - halfW
            val y = (row + 0.5) / rows * h - halfH
            val depth = -distance(x, y, halfW, halfH, corner)
            if (depth < -half || depth > 2 * RIM_WIDTH) continue
            val qx = abs(x) - halfW + corner
            val qy = abs(y) - halfH + corner
            var nx = 0.0
            var ny = 0.0
            if (qx > 0 && qy > 0) {
                val length = hypot(qx, qy)
                nx = sign(x) * qx / length
                ny = sign(y) * qy / length
            } else if (qx > qy) nx = sign(x) else ny = sign(y)
            val facing = abs(nx + ny)
            val along = max(0.0, depth) / (RIM_WIDTH * sqrt(max(1.0, facing)))
            val cover = clamp((depth + half) * d, 0.0, 1.0)
            val lined = clamp((min(EDGE_WIDTH, depth + half) - max(0.0, depth - half)) * d, 0.0, 1.0)
            val light = if (along < 1) shine * min(1.0, facing).pow(1.5) * (1 - along.pow(1.5)) * cover else 0.0
            val line = e[3] * lined * (1 - light)
            val alpha = light + line
            if (alpha <= 0) continue
            fun ch(c: Int) = ((255 * light + e[c] * line) / alpha).roundToInt().coerceIn(0, 255)
            out[row * columns + column] = ((255 * alpha).roundToInt().coerceIn(0, 255) shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
        return out
    }

    /** Width and height in pixels of [selection]'s image. */
    fun selectionSize(width: Float, height: Float, density: Float) =
        intArrayOf(ceil(width * min(density, 2f)).toInt(), ceil(height * min(density, 2f)).toInt())

    /**
     * The rim of a lifted selection lens (`selectionMaps`): banded highlights inside the edge, or with [bright] the
     * brighter one glisse gives it on a light page. ARGB.
     */
    fun selection(width: Float, height: Float, radius: Float, density: Float, bright: Boolean): IntArray {
        val (cw, ch) = selectionSize(width, height, density).let { it[0] to it[1] }
        val out = IntArray(cw * ch)
        val w = width.toDouble(); val h = height.toDouble(); val r = radius.toDouble()
        for (y in 0 until ch) for (x in 0 until cw) {
            val px = (x + 0.5) * w / cw - w / 2
            val py = (y + 0.5) * h / ch - h / 2
            val qx = abs(px) - (w / 2 - r)
            val qy = abs(py) - (h / 2 - r)
            val ox = max(qx, 0.0)
            val oy = max(qy, 0.0)
            val length = hypot(ox, oy)
            val dist = r - (length + min(max(qx, qy), 0.0))
            val nx = if (length > 0) ox / length * sign(px) else if (qx > qy) sign(px) else 0.0
            val ny = if (length > 0) oy / length * sign(py) else if (qx > qy) 0.0 else sign(py)
            if (dist < 0) continue
            val lightDir = -(nx + ny) * sqrt(0.5)
            val glint = max(lightDir, 0.0).pow(3)
            val reflected = max(-lightDir, 0.0).pow(3)
            out[y * cw + x] = if (!bright) {
                val bands = doubleArrayOf(0.18, 0.42, 0.66).map { exp(-((dist - it) / 0.22).pow(2)) }
                val peak = bands.max()
                val alpha = clamp(peak * (0.06 + 0.8 * glint + 0.35 * reflected) * 0.85, 0.0, 1.0)
                fun c(i: Int) = (255 * (0.65 + 0.35 * (if (peak > 0) bands[i] / peak else 0.0))).roundToInt()
                ((alpha * 255).roundToInt() shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
            } else {
                val wide = doubleArrayOf(0.4, 0.5, 0.6).map { exp(-((dist - it) / 0.4).pow(2)) }
                val top = wide.max()
                val alpha = clamp(top * (0.55 + 0.3 * glint + 0.15 * reflected), 0.0, 1.0)
                fun c(i: Int) = (255 * (0.85 + 0.15 * (if (top > 0) wide[i] / top else 0.0))).roundToInt()
                ((alpha * 255).roundToInt() shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
            }
        }
        return out
    }
}
